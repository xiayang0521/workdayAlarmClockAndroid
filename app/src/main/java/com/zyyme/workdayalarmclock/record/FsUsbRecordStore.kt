package com.zyyme.workdayalarmclock.record

import android.os.StatFs
import java.io.File

/**
 * 基于真实文件路径的 USB / 可移动存储写入后端（免 SAF 目录授权）。
 *
 * 适用场景：无屏相机设备无法弹出系统目录选择器，由 viewer 远程指定卷与子目录，
 * App 凭已持有的 MANAGE_EXTERNAL_STORAGE（所有文件访问）权限直接在挂载点写文件。
 * 典型路径形如 /storage/1A2B-3C4D/workday。
 *
 * 与 [UsbRecordStore]（SAF）实现同一 [UsbRecordTarget]，投递、配额、重试逻辑完全复用。
 * 所有访问异常都安静降级为 false/空列表，由上层按"拔盘重试"处理，绝不抛进录像主线程。
 */
internal class FsUsbRecordStore(
    rootPath: String
) : UsbRecordTarget {

    private val root: File = File(rootPath)

    override fun isAvailable(): Boolean {
        return try {
            // 目录存在且可写才算就绪；目录缺失不在这里创建，避免误写到错误挂载点。
            root.exists() && root.isDirectory && root.canWrite()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 确保目标目录存在（保存配置时由相机端调用一次预建）。
     * 已存在返回 true；创建失败（卷只读 / 未插 / 权限不足）返回 false。
     */
    fun ensureDirectory(): Boolean {
        return try {
            if (root.exists()) root.isDirectory else root.mkdirs()
        } catch (_: Exception) {
            false
        }
    }

    override fun listRecordings(): List<UsbRecordTarget.RecordedFile> {
        if (!isAvailable()) return emptyList()
        return try {
            root.listFiles()
                ?.filter {
                    it.isFile &&
                        !it.name.endsWith(UsbRecordTarget.PART_SUFFIX) &&
                        RecordingNames.isRecording(it.name)
                }
                ?.map {
                    UsbRecordTarget.RecordedFile(
                        name = it.name,
                        size = it.length(),
                        handle = it
                    )
                }
                ?.sortedBy { it.name }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun copyIn(localFile: File): Boolean {
        if (!localFile.isFile) return false
        if (!ensureDirectory()) return false
        val finalTarget = File(root, localFile.name)
        val partTarget = File(root, localFile.name + UsbRecordTarget.PART_SUFFIX)
        return try {
            // 幂等清理上次残留，避免 rename 失败或读到旧的不完整文件。
            if (partTarget.exists()) partTarget.delete()
            localFile.inputStream().use { input ->
                partTarget.outputStream().use { output ->
                    input.copyTo(output, COPY_BUFFER_SIZE)
                }
            }
            // 先删同名正式文件（可能内容不完整），再原子改名。
            if (finalTarget.exists()) finalTarget.delete()
            if (!partTarget.renameTo(finalTarget)) {
                // 个别 ROM 跨目录 rename 不稳，退化为同目录内直接复制最终名。
                partTarget.delete()
                localFile.inputStream().use { input ->
                    finalTarget.outputStream().use { output ->
                        input.copyTo(output, COPY_BUFFER_SIZE)
                    }
                }
            }
            finalTarget.isFile && finalTarget.length() > 0L
        } catch (_: Exception) {
            runCatching { partTarget.delete() }
            false
        }
    }

    override fun delete(file: UsbRecordTarget.RecordedFile): Boolean {
        val target = file.handle as? File ?: return false
        return try {
            target.delete()
        } catch (_: Exception) {
            false
        }
    }

    override fun availableBytes(): Long? {
        return try {
            if (!root.exists()) null else StatFs(root.absolutePath).availableBytes
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 64 * 1024

        /**
         * 判断一个绝对路径是否安全地指向可移动存储或外部存储（防止 viewer 下发任意路径）。
         * 仅允许 /storage、/mnt/media_rw 下的常规外部存储位置；私有目录、系统目录一律拒绝。
         */
        fun isAllowedBasePath(path: String): Boolean {
            val normalized = try {
                File(path).canonicalPath
            } catch (_: Exception) {
                return false
            }
            val allowedPrefixes = arrayOf(
                "/storage/emulated/",
                "/storage/self/",
                "/mnt/media_rw/"
            )
            // /storage/<卷号>（卷号形如 1A2B-3C4D）也允许，但排除 /storage/emulated 下越权到他处。
            val underStorage = normalized.startsWith("/storage/") &&
                normalized.removePrefix("/storage/").substringBefore('/').let { vol ->
                    vol == "emulated" || vol.matches(Regex("[0-9A-Za-z-]{2,}"))
                }
            return underStorage || allowedPrefixes.any { normalized.startsWith(it) }
        }
    }
}
