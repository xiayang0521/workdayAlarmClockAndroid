package com.zyyme.workdayalarmclock.record

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * 录像持久库（USB/本机存储投递目录）的真实文件系统视图。
 *
 * 背景：分钟分段先写入 App 私有暂存目录 filesDir/recordings，再由 [UsbRecordSink] 经 SAF
 * 复制到用户授权的持久目录（本机上即 /storage/emulated/0/video），随后暂存目录会按
 * 本地缓存上限滚动清理旧段。若 HTTP 回放只扫描暂存目录，就只能看到最近约一个缓存窗口
 * （默认 512MB，约 90 分钟）的录像，更早的全天录像虽在持久库中却无法回看。
 *
 * 本类把 SAF treeUri 尽力解析回真实 [File] 路径，让回放服务能直接用 RandomAccessFile +
 * Range 读取持久库中的全天录像。App 持有 MANAGE_EXTERNAL_STORAGE 权限，primary 卷可直接
 * 用真实路径访问；可移动卷同样按 /storage/<卷号> 映射。解析失败（授权丢失/非文件系统
 * 提供者）时 [isAvailable] 返回 false，上层安静回退到"只看暂存 + NAS"的旧行为。
 */
internal class PersistentLibrary private constructor(
    private val directRoot: File?
) {
    // SAF treeUri 构造路径专用：为 null 时表示本实例是 [forDirectory] 直接目录模式。
    private var treeUri: String? = null
    private var appContext: Context? = null

    // 走 SAF treeUri 解析的旧构造（有屏授权目录）。
    constructor(context: Context, treeUri: String) : this(directRoot = null) {
        this.treeUri = treeUri
        this.appContext = context.applicationContext
    }

    /**
     * 真实路径直写目录的视图（无屏设备远程指定的 /storage/<卷>/子目录）。
     * 目录在录像写入前已由相机端预建，这里直接用 File，无需 DocumentProvider。
     */
    companion object {
        fun forDirectory(directory: File): PersistentLibrary =
            PersistentLibrary(directRoot = directory)
    }

    /**
     * 缓存解析结果：treeUri 长期不变，没有必要每次列举都重新走 DocumentProvider。
     * rootFile 为 null 表示当前不可用或无法映射为真实路径。
     */
    @Volatile
    private var rootFile: File? = directRoot

    @Volatile
    private var resolved: Boolean = directRoot != null

    private fun resolveRoot(): File? {
        if (resolved) return rootFile
        if (directRoot != null) {
            rootFile = directRoot
            resolved = true
            return rootFile
        }
        val uri = treeUri ?: return null
        val context = appContext ?: return null
        synchronized(this) {
            if (resolved) return rootFile
            rootFile = try {
                val tree = Uri.parse(uri)
                val rootId = DocumentsContract.getTreeDocumentId(tree)
                // rootId 形如 "primary:video/sub" 或 "1A2B-3C4D:video"，冒号后为卷内相对路径。
                val volumeId = rootId.substringBefore(':', missingDelimiterValue = "")
                val relative = rootId.substringAfter(':', missingDelimiterValue = "")
                val volumeRoots = when {
                    volumeId.equals("primary", ignoreCase = true) ->
                        listOf("/storage/emulated/0", "/sdcard")
                    volumeId.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}.*")) ->
                        listOf("/storage/$volumeId")
                    else -> emptyList()
                }
                volumeRoots
                    .map { base -> if (relative.isEmpty()) File(base) else File(base, relative) }
                    .firstOrNull { it.exists() && it.isDirectory }
            } catch (_: Exception) {
                null
            }
            resolved = true
            return rootFile
        }
    }

    fun isAvailable(): Boolean = resolveRoot() != null

    /**
     * 列举持久库中的正式录像（过滤 .part 临时文件），按文件名升序，由上层再按时间排序。
     * 任何访问异常都返回空列表，绝不影响回放主流程。
     */
    fun listRecordings(): List<File> {
        val root = resolveRoot() ?: return emptyList()
        return try {
            root.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(UsbRecordStore.PART_SUFFIX) &&
                    RecordingNames.isRecording(it.name) }
                ?.sortedBy { it.name }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 按文件名精确取一段持久库录像，不存在返回 null。 */
    fun openForRead(name: String): File? {
        val root = resolveRoot() ?: return null
        return try {
            val file = File(root, name)
            if (file.isFile && file.length() > 0L) file else null
        } catch (_: Exception) {
            null
        }
    }
}
