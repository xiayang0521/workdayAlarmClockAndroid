package com.zyyme.workdayalarmclock.record

import android.content.Context
import com.zyyme.workdayalarmclock.MeSettings
import java.io.File

/**
 * USB / 本机持久录像目录的统一写入视图，屏蔽两种后端差异：
 * - SAF 后端（[UsbRecordStore]）：靠本机目录授权弹窗拿到的 tree uri，适合有屏一次性授权；
 * - 真实路径后端（[FsUsbRecordStore]）：靠 MANAGE_EXTERNAL_STORAGE 直接写 /storage 挂载点，
 *   适合无屏设备由 viewer 远程指定卷与子目录。
 *
 * 上层 [UsbRecordSink]、HTTP 统计只依赖此接口，按当前配置用 [UsbRecordTargets.create] 取实现，
 * 因此两种方式可共存、随时切换，投递/配额/重试逻辑完全复用。
 */
internal interface UsbRecordTarget {

    /** 一个已落地的正式录像条目。[handle] 由具体后端解释（SAF 的 Uri 或 File）。 */
    data class RecordedFile(
        val name: String,
        val size: Long,
        val handle: Any
    )

    /** 目标目录当前是否可访问（U 盘未插 / 授权失效 / 路径不存在时为 false）。 */
    fun isAvailable(): Boolean

    /** 列出目录下的正式录像（已过滤 .part 临时文件与非录像文件）。 */
    fun listRecordings(): List<RecordedFile>

    /** 把本地暂存分段复制进目标目录（先写 .part 再改名，断电不留半截 mp4）。 */
    fun copyIn(localFile: File): Boolean

    /** 删除一个由 [listRecordings] 返回的条目。 */
    fun delete(file: RecordedFile): Boolean

    /** 所在卷可用字节数；取不到返回 null（保留空间配额模式会据此跳过）。 */
    fun availableBytes(): Long?

    companion object {
        /** 复制过程中的临时段后缀，正式落地前对列举/配额不可见。 */
        const val PART_SUFFIX = ".part"
    }
}

/**
 * 按当前存储配置构造对应的 [UsbRecordTarget]。
 * 远程真实路径（fsPath）优先：它是无屏设备的主路径；未配置时回退 SAF treeUri。
 * 两者都没有时返回 null，上层安静跳过 USB 投递。
 */
internal object UsbRecordTargets {

    fun create(context: Context): UsbRecordTarget? {
        val appContext = context.applicationContext
        val fsPath = MeSettings.getUsbRecordFsPath(appContext)
        if (fsPath.isNotEmpty()) {
            return FsUsbRecordStore(fsPath)
        }
        val treeUri = MeSettings.getUsbRecordTreeUri(appContext)
        if (treeUri.isNotEmpty()) {
            return UsbRecordStore(appContext, treeUri)
        }
        return null
    }
}
