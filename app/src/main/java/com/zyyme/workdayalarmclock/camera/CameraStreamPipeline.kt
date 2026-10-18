package com.zyyme.workdayalarmclock.camera

import java.util.ArrayDeque

internal enum class CameraStreamFormat {
    MJPEG,
    AVC
}

internal data class CameraStreamKey(
    val format: CameraStreamFormat,
    val cameraIndex: Int,
    val resolutionIndex: Int? = null,
    val targetHeight: Int? = null
)

internal data class CameraStreamPacket(
    val sequence: Long,
    val data: ByteArray,
    val keyFrame: Boolean = true,
    val presentationTimeUs: Long = 0L
)

internal data class AvcStreamConfig(
    val sps: ByteArray,
    val pps: ByteArray,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val codecString: String,
    /** "avc" 或 "hevc"。 */
    val codec: String = "avc",
    /** HEVC 视频参数集（AVC 为空）。 */
    val vps: ByteArray = ByteArray(0)
)

internal interface CameraStreamPipeline {
    val key: CameraStreamKey
    val description: String

    fun start(): Boolean
    fun stop()
    fun awaitPacket(afterSequence: Long): CameraStreamPacket?
    fun awaitAvcConfig(timeoutMillis: Long): AvcStreamConfig? = null
    fun requestKeyFrame() = Unit
    /** 设备栈是否为 AnnexB 原生（见 AvcStreamConfig.annexbNative）。 */
    fun isAnnexbNative(): Boolean = false
    /** 画面是否有变动（静止检测结果；无检测能力时恒为 true）。 */
    fun isMoving(): Boolean = true
    /** 是否具备静止检测能力（决定是否需要记录动态时间标签）。 */
    fun hasMotionDetection(): Boolean = false
    /** 动态调整编码码率（<=0 恢复默认）。 */
    fun setDynamicBitrate(bitsPerSecond: Int) = Unit
}

internal class CameraFrameHub(
    private val maxPackets: Int,
    private val keepLatestOnly: Boolean
) {
    private val monitor = Object()
    private val packets = ArrayDeque<CameraStreamPacket>()
    private var sequence = 0L
    private var closed = false

    fun publish(
        data: ByteArray,
        keyFrame: Boolean = true,
        presentationTimeUs: Long = 0L
    ) {
        synchronized(monitor) {
            if (closed) return
            sequence++
            if (keepLatestOnly) packets.clear()
            packets.addLast(
                CameraStreamPacket(sequence, data, keyFrame, presentationTimeUs)
            )
            while (packets.size > maxPackets) packets.removeFirst()
            monitor.notifyAll()
        }
    }

    fun awaitNext(afterSequence: Long): CameraStreamPacket? {
        synchronized(monitor) {
            while (!closed) {
                val iterator = packets.iterator()
                while (iterator.hasNext()) {
                    val packet = iterator.next()
                    if (packet.sequence > afterSequence) return packet
                }
                try {
                    monitor.wait(3_000L)
                } catch (_: InterruptedException) {
                    return null
                }
            }
            return null
        }
    }

    fun close() {
        synchronized(monitor) {
            closed = true
            packets.clear()
            monitor.notifyAll()
        }
    }
}
