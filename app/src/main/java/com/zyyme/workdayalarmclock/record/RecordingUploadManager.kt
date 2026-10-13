package com.zyyme.workdayalarmclock.record

import android.content.Context
import com.zyyme.workdayalarmclock.MeSettings
import com.zyyme.workdayalarmclock.nas.WebDavClient
import com.zyyme.workdayalarmclock.nas.WebDavEntry
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

internal data class RecordingInfo(
    val name: String,
    val size: Long,
    val timestampMs: Long,
    val local: Boolean
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
    private val log: (String) -> Unit
) : RecordingAccess {
    private val appContext: Context = context.applicationContext
    companion object {
        private const val RETRY_DELAY_MS = 15_000L
        private const val IDLE_POLL_MS = 10_000L
        private const val MAX_DELETE_PER_ROUND = 30
    }

    private val running = AtomicBoolean(false)
    private val pending = LinkedBlockingQueue<File>()
    private val uploadedNames = java.util.HashSet<String>()
    private val pendingNames = java.util.HashSet<String>()
    private val stateLock = Any()

    private var workerThread: Thread? = null

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
        synchronized(stateLock) {
            localDir.listFiles()?.forEach { file ->
                if (file.isFile && RecordingNames.isRecording(file.name)) {
                    result[file.name] = RecordingInfo(
                        file.name,
                        file.length(),
                        RecordingNames.timestampOf(file.name),
                        true
                    )
                }
            }
        }
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
     * 打开录像用于在线播放/下载：本地存在则本地优先，否则经 WebDAV 代理。
     */
    override fun openForRead(name: String, rangeHeader: String?): RecordingReadHandle? {
        if (!isSafeName(name)) return null
        synchronized(stateLock) {
            val local = File(localDir, name)
            if (local.isFile) {
                return RecordingReadHandle(local = local, connection = null)
            }
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
        if (!MeSettings.isRecordConfigured(appContext)) {
            requeue(file)
            sleep(RETRY_DELAY_MS)
            return
        }
        val context = appContext
        val baseUrl = MeSettings.getWebDavUrl(context)
        val username = MeSettings.getWebDavUsername(context)
        val password = MeSettings.getWebDavPassword(context)
        val remoteDir = MeSettings.getWebDavDir(context)
        val client = WebDavClient(baseUrl, username, password)
        try {
            client.mkdirs(remoteDir)
            client.upload(file, joinPath(remoteDir, file.name))
            synchronized(stateLock) {
                pendingNames.remove(file.name)
                uploadedNames.add(file.name)
            }
            enforceLocalCache(context)
            enforceRemoteQuota(client, remoteDir, context)
        } catch (e: Exception) {
            if (running.get()) log("录像上传失败：${e.message}")
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

    private fun enforceLocalCache(context: android.content.Context) {
        val cacheBytes = MeSettings.getLocalCacheMb(context).toLong() * 1024L * 1024L
        synchronized(stateLock) {
            val files = localDir.listFiles()?.toMutableList()
                ?.filter { it.isFile && RecordingNames.isRecording(it.name) }
                ?.sortedBy { RecordingNames.timestampOf(it.name) }
                ?: return
            var total = files.sumOf { it.length() }
            val iterator = files.iterator()
            while (total > cacheBytes && iterator.hasNext()) {
                val file = iterator.next()
                if (!uploadedNames.contains(file.name)) continue
                val length = file.length()
                if (file.delete()) total -= length
            }
        }
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
        if (!MeSettings.isRecordConfigured(appContext)) return null
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
        if (!MeSettings.isRecordConfigured(appContext)) return null
        return WebDavClient(
            MeSettings.getWebDavUrl(appContext),
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
