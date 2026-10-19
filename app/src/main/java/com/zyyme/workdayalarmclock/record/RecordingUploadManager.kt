package com.zyyme.workdayalarmclock.record

import android.content.Context
import com.zyyme.workdayalarmclock.MeSettings
import com.zyyme.workdayalarmclock.nas.WebDavClient
import com.zyyme.workdayalarmclock.nas.WebDavEntry
import com.zyyme.workdayalarmclock.nas.LanDomainResolver
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

internal data class RecordingInfo(
    val name: String,
    val size: Long,
    val timestampMs: Long,
    val local: Boolean,
    /** 动态（有画面变动）时间区间，相对 timestampMs 的毫秒偏移。 */
    val motion: List<LongArray> = emptyList()
)

/**
 * 录像上传与保留策略：
 * - 串行上传本地分钟分段到 WebDAV，断网/失败自动重试，应用重启补传；
 * - 本地缓存达到上限时仅删除已上传的最旧文件；
 * - NAS 侧两种空间策略：保留可用空间（默认 1G）或占用空间上限，
 *   超限按文件名时间戳删除最早录像。
 */
internal class RecordingUploadManager(
    context: Context,
    private val localDir: File,
    private val tracker: SegmentDeliveryTracker,
    private val janitor: LocalCacheJanitor,
    private val log: (String) -> Unit
) : RecordingAccess {
    private val appContext: Context = context.applicationContext
    companion object {
        private const val RETRY_DELAY_MS = 15_000L
        private const val IDLE_POLL_MS = 10_000L
        private const val MAX_DELETE_PER_ROUND = 30
        // 持久库全量列举的缓存窗口：平衡时间轴新鲜度与上千文件扫描的 HTTP 响应耗时。
        private const val PERSISTENT_LIST_TTL_MS = 5_000L
    }

    private val running = AtomicBoolean(false)
    private val pending = LinkedBlockingQueue<File>()
    private val uploadedNames = java.util.HashSet<String>()
    private val pendingNames = java.util.HashSet<String>()
    private val stateLock = Any()

    private var workerThread: Thread? = null

    // 持久库（用户授权的本机/USB 投递目录）解析结果按 treeUri 缓存；设置变更（uri 变化）时重建。
    @Volatile private var persistentTreeUri: String = ""
    @Volatile private var persistentLibrary: PersistentLibrary? = null

    /**
     * 取当前配置对应的持久库视图。未开启本机投递、未选目录或无法映射为真实路径时返回 null，
     * 调用方安静回退到"只看暂存 + NAS"。treeUri 是长期值，按其缓存避免每次列举都重解析。
     */
    private fun persistent(): PersistentLibrary? {
        if (!MeSettings.isUsbRecordConfigured(appContext)) return null
        // fs 直写模式（无屏远程指定的真实目录）优先，直接用 File 视图。
        val fsPath = MeSettings.getUsbRecordFsPath(appContext)
        if (fsPath.isNotEmpty()) {
            if (fsPath != persistentTreeUri) {
                synchronized(stateLock) {
                    if (fsPath != persistentTreeUri) {
                        val dir = java.io.File(fsPath)
                        persistentLibrary =
                            if (dir.exists() && dir.isDirectory) PersistentLibrary.forDirectory(dir)
                            else null
                        persistentTreeUri = fsPath
                    }
                }
            }
            return persistentLibrary
        }
        val treeUri = MeSettings.getUsbRecordTreeUri(appContext)
        if (treeUri.isEmpty()) return null
        if (treeUri != persistentTreeUri) {
            synchronized(stateLock) {
                if (treeUri != persistentTreeUri) {
                    val candidate = PersistentLibrary(appContext, treeUri)
                    persistentLibrary = if (candidate.isAvailable()) candidate else null
                    persistentTreeUri = treeUri
                }
            }
        }
        return persistentLibrary
    }

    // 持久库可能有上千段，逐文件 stat（含 motion.json）较慢；用短 TTL 缓存聚合结果。
    // 最新分段始终由暂存目录实时提供，故这里缓存几秒不会影响"刚录的段立即可见"。
    @Volatile private var persistentInfoCache: List<RecordingInfo> = emptyList()
    @Volatile private var persistentInfoCacheAt: Long = 0L

    private fun persistentInfos(): List<RecordingInfo> {
        val library = persistent() ?: run {
            persistentInfoCache = emptyList()
            persistentInfoCacheAt = 0L
            return emptyList()
        }
        val now = System.currentTimeMillis()
        if (now - persistentInfoCacheAt < PERSISTENT_LIST_TTL_MS && persistentInfoCache.isNotEmpty()) {
            return persistentInfoCache
        }
        val infos = library.listRecordings().map { file ->
            // 持久库由投递器只复制 mp4（不复制 .motion.json），本就没有动态标记；
            // 这里不逐文件 stat，避免上千段时把首次 /rec/list 拖过客户端读超时。
            RecordingInfo(
                file.name,
                file.length(),
                RecordingNames.timestampOf(file.name),
                true
            )
        }
        persistentInfoCache = infos
        persistentInfoCacheAt = now
        return infos
    }


    fun start() {
        if (!running.compareAndSet(false, true)) return
        localDir.mkdirs()
        cleanupPartFiles()
        enqueueExisting()
        workerThread = Thread({ workerLoop() }, "record-upload").apply { start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        workerThread?.interrupt()
        try {
            workerThread?.join(1_500L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        workerThread = null
    }

    fun enqueue(segment: SegmentFile) {
        if (!running.get()) return
        synchronized(stateLock) {
            if (uploadedNames.contains(segment.key) || pendingNames.contains(segment.key)) return
            if (!segment.file.exists()) return
            pendingNames.add(segment.key)
            pending.offer(segment.file)
        }
    }

    override fun listRecordings(): List<RecordingInfo> {
        val result = LinkedHashMap<String, RecordingInfo>()
        // 1) 暂存目录：含最新分段及其动态标记，优先收录。
        synchronized(stateLock) {
            localDir.listFiles()?.forEach { file ->
                if (file.isFile && RecordingNames.isRecording(file.name)) {
                    result[file.name] = RecordingInfo(
                        file.name,
                        file.length(),
                        RecordingNames.timestampOf(file.name),
                        true,
                        motion = MotionMarks.readFor(file)
                    )
                }
            }
        }
        // 2) 持久库（/video 等）：暂存已被缓存清理滚动删除的全天旧段从这里补回。
        //    同名以暂存为准（暂存是最新原件），持久库只补缺，保证时间轴覆盖全天。
        persistentInfos().forEach { info ->
            if (!result.containsKey(info.name)) {
                result[info.name] = info
            }
        }
        // 3) NAS：本机与持久库都没有的段再走远端（local=false）。
        tryListRemote()?.forEach { entry ->
            val name = remoteBasename(entry.path)
            if (isRecordingName(name) && !result.containsKey(name)) {
                result[name] = RecordingInfo(
                    name,
                    entry.size,
                    RecordingNames.timestampOf(name),
                    false
                )
            }
        }
        return result.values.sortedByDescending { it.timestampMs }
    }

    /**
     * 打开录像用于在线播放/下载，三级回退：
     * 暂存目录（最新原件）→ 持久库（全天旧段）→ NAS 代理。
     * 前两者都映射成本地 File，复用 HTTP 层的 RandomAccessFile + Range，支持拖动。
     */
    override fun openForRead(name: String, rangeHeader: String?): RecordingReadHandle? {
        if (!isSafeName(name)) return null
        synchronized(stateLock) {
            val local = File(localDir, name)
            if (local.isFile) {
                return RecordingReadHandle(local = local, connection = null)
            }
        }
        persistent()?.openForRead(name)?.let { persisted ->
            return RecordingReadHandle(local = persisted, connection = null)
        }
        val client = createConfiguredClient() ?: return null
        return try {
            val connection = client.openRead(remotePath(name), rangeHeader)
            RecordingReadHandle(local = null, connection = connection)
        } catch (e: Exception) {
            log("读取NAS录像失败：${e.message}")
            null
        }
    }

    private fun workerLoop() {
        while (running.get()) {
            val file = try {
                pending.poll(IDLE_POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (file == null) continue
            uploadOne(file)
        }
    }

    private fun markFailed(file: File) {
        synchronized(stateLock) { pendingNames.remove(file.name) }
    }

    private fun uploadOne(file: File) {
        if (!file.exists() || !file.isFile) {
            markFailed(file)
            return
        }
        // 只配置了 USB、未配置 NAS 时，该分段无需上传：标记 NAS 不参与并交还本地缓存清理判断。
        if (!MeSettings.isNasConfigured(appContext)) {
            markFailed(file)
            return
        }
        val context = appContext
        val baseUrl = MeSettings.getWebDavUrl(context)
        val username = MeSettings.getWebDavUsername(context)
        val password = MeSettings.getWebDavPassword(context)
        val remoteDir = MeSettings.getWebDavDir(context)
        // 域名（workdayXXXXXX.local）先解析成 IP 再连；失败会清缓存，下次重试自动重新解析。
        val client = WebDavClient(LanDomainResolver.resolveUrl(context, baseUrl).first, username, password)
        try {
            client.mkdirs(remoteDir)
            client.upload(file, joinPath(remoteDir, file.name))
            synchronized(stateLock) {
                pendingNames.remove(file.name)
                uploadedNames.add(file.name)
            }
            tracker.markDone(file.name, DeliveryTarget.NAS)
            val cacheBytes = MeSettings.getLocalCacheMb(context).toLong() * 1024L * 1024L
            janitor.enforce(tracker, cacheBytes)
            enforceRemoteQuota(client, remoteDir, context)
        } catch (e: Exception) {
            if (running.get()) log("录像上传失败：${e.message}")
            LanDomainResolver.evictUrl(baseUrl)
            requeue(file)
            sleep(RETRY_DELAY_MS)
        }
    }

    private fun requeue(file: File) {
        if (!running.get()) {
            markFailed(file)
            return
        }
        Thread({
            if (sleep(RETRY_DELAY_MS) && running.get() && file.exists()) {
                synchronized(stateLock) {
                    if (file.isFile &&
                        !uploadedNames.contains(file.name) &&
                        pendingNames.add(file.name)
                    ) {
                        pending.offer(file)
                    } else {
                        pendingNames.remove(file.name)
                    }
                }
            } else {
                markFailed(file)
            }
        }, "record-retry").apply { isDaemon = true }.start()
    }

    private fun enforceRemoteQuota(
        client: WebDavClient,
        remoteDir: String,
        context: android.content.Context
    ) {
        val mode = MeSettings.getQuotaMode(context)
        val entries = tryListRemote(client, remoteDir) ?: return
        val recordings = entries
            .map { entry -> remoteBasename(entry.path) to entry }
            .filter { isRecordingName(it.first) }
            .sortedBy { RecordingNames.timestampOf(it.first) }

        if (mode == MeSettings.QUOTA_MODE_LIMIT) {
            val limitBytes = MeSettings.getQuotaLimitMb(context).toLong() * 1024L * 1024L
            var used = recordings.sumOf { it.second.size }
            var deleted = 0
            for ((name, entry) in recordings) {
                if (used <= limitBytes || deleted >= MAX_DELETE_PER_ROUND) break
                if (deleteRemote(client, remoteDir, name)) {
                    used -= entry.size
                    deleted++
                }
            }
            return
        }

        val reserveBytes = MeSettings.getQuotaReserveMb(context).toLong() * 1024L * 1024L
        var deleted = 0
        while (deleted < MAX_DELETE_PER_ROUND) {
            val quota = client.queryQuota(remoteDir) ?: break
            if (quota.first >= reserveBytes) break
            val target = recordings.getOrNull(deleted) ?: break
            if (!deleteRemote(client, remoteDir, target.first)) break
            deleted++
        }
    }

    private fun deleteRemote(client: WebDavClient, remoteDir: String, name: String): Boolean {
        return try {
            client.delete(joinPath(remoteDir, name))
            log("NAS空间清理：已删除最早录像 $name")
            true
        } catch (e: Exception) {
            log("NAS录像删除失败：${e.message}")
            false
        }
    }

    private fun tryListRemote(
        client: WebDavClient? = null,
        remoteDir: String? = null
    ): List<WebDavEntry>? {
        if (!MeSettings.isNasConfigured(appContext)) return null
        val actualClient = client ?: createConfiguredClient() ?: return null
        val actualDir = remoteDir ?: MeSettings.getWebDavDir(appContext)
        return try {
            actualClient.list(actualDir).filter { entry ->
                !entry.isDirectory && isRecordingName(remoteBasename(entry.path))
            }
        } catch (e: Exception) {
            if (client == null) log("读取NAS录像列表失败：${e.message}")
            null
        }
    }

    private fun createConfiguredClient(): WebDavClient? {
        if (!MeSettings.isNasConfigured(appContext)) return null
        return WebDavClient(
            LanDomainResolver.resolveUrl(appContext, MeSettings.getWebDavUrl(appContext)).first,
            MeSettings.getWebDavUsername(appContext),
            MeSettings.getWebDavPassword(appContext)
        )
    }

    private fun enqueueExisting() {
        val files = localDir.listFiles()?.toList().orEmpty()
            .filter { it.isFile && RecordingNames.isRecording(it.name) }
            .sortedBy { RecordingNames.timestampOf(it.name) }
        synchronized(stateLock) {
            files.forEach { file ->
                if (pendingNames.add(file.name)) {
                    pending.offer(file)
                }
            }
        }
    }

    private fun cleanupPartFiles() {
        localDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".part")) {
                try {
                    file.delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun remotePath(name: String): String {
        return joinPath(MeSettings.getWebDavDir(appContext), name)
    }

    private fun joinPath(dir: String, name: String): String {
        val cleanDir = dir.trim('/')
        return if (cleanDir.isEmpty()) name else "$cleanDir/$name"
    }

    private fun remoteBasename(path: String): String {
        val decoded = path.trimEnd('/')
        val index = decoded.lastIndexOf('/')
        return if (index >= 0) decoded.substring(index + 1) else decoded
    }

    private fun isRecordingName(name: String): Boolean {
        return RecordingNames.isRecording(name) && isSafeName(name)
    }

    private fun isSafeName(name: String): Boolean {
        return name.length in 15..80 &&
            !name.contains('/') &&
            !name.contains('\\') &&
            !name.contains("..")
    }

    private fun sleep(durationMs: Long): Boolean {
        return try {
            Thread.sleep(durationMs)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}

internal data class RecordingReadHandle(
    val local: File?,
    val connection: HttpURLConnection?
) {
    fun close() {
        connection?.disconnect()
    }
}
