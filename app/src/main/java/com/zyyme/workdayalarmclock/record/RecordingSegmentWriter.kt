package com.zyyme.workdayalarmclock.record

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.zyyme.workdayalarmclock.camera.NalUnits
import java.io.File
import java.nio.ByteBuffer

/**
 * 单个分钟录像分段：把 H.264 AnnexB 视频帧与裸 AAC 音频帧合成到一个 MP4 文件。
 *
 * 分段内时间戳从 0 重新开始：每路轨道以首个样本时间为各自基准。音频在视频首帧
 * 之前到达时直接丢弃，保证合成文件不会因音频早于视频而无法起播。
 */
internal class RecordingSegmentWriter(
    val file: File,
    private val videoFormat: MediaFormat,
    private val audioFormat: MediaFormat?,
    private val durationLimitUs: Long,
    /**
     * 录像画面旋转角度（0/90/180/270）。通过 muxer.setOrientationHint 写入 MP4
     * 方向矩阵（须在 addTrack 之前调用），播放器读取矩阵后自动转正画面，
     * 无需重新编码。摄像头倒挂安装时设为 180 即可。
     */
    private val rotationDegrees: Int = 0,
    /**
     * 视频是否为 H.265/HEVC。决定打包时剥除哪些参数集（HEVC 是 VPS/SPS/PPS，
     * AVC 是 SPS/PPS）以及关键帧内容判定方式。默认 false 即 H.264。
     */
    private val isHevc: Boolean = false
) {
    private val lock = Any()

    private val partFile = File(file.parentFile, file.name + ".part")

    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var finished = false

    private var videoBaseUs = Long.MIN_VALUE
    private var audioBaseUs = Long.MIN_VALUE
    private var firstVideoWallMs = 0L
    private var lastVideoUs = 0L
    private var videoFrames = 0

    val key: String = file.name

    fun beginWallTime(wallMs: Long) {
        firstVideoWallMs = wallMs
    }

    /**
     * 写入一帧 H.264（AnnexB）。返回 true 表示本帧已写入；返回 false 表示写入器已结束。
     */
    fun writeVideo(data: ByteArray, presentationTimeUs: Long, keyFrame: Boolean): Boolean {
        synchronized(lock) {
            if (finished) return false
            ensureMuxer()
            if (videoTrack < 0) {
                videoTrack = muxer!!.addTrack(videoFormat)
            }
            if (videoBaseUs == Long.MIN_VALUE) videoBaseUs = presentationTimeUs
            maybeStart()
            if (!started) return true
            val relativeUs = normalize(presentationTimeUs - videoBaseUs)
            if (relativeUs < 0L) return true
            // 关键：录像样本必须喂「AnnexB」，由系统 MediaMuxer 自己完成 AnnexB→AVCC。
            //
            // 这台一加/ColorOS 设备的硬编是「AnnexB 原生栈」：管线 publish 的 packet.data
            // 用 00 00 00 01 起始码分帧，而系统 MediaMuxer.writeSampleData 会把 AnnexB
            // 规范化成单层 AVCC 再落盘（老版本/原仓库直喂 AnnexB 即产出可正常播放的录像）。
            //
            // 曾经的错误做法是在 App 侧先用 NalUnits.toAvccSample 把帧转成 AVCC 再喂给
            // MediaMuxer；结果系统又包一层长度前缀，落盘样本变成 L4(L4(NAL)) 双前缀，
            // 解码器逐帧报 "missing picture in access unit"，表现为整段黑屏、只有声音。
            //
            // 因此这里只做「归一化成干净 AnnexB」——NalUnits.toAnnexB 会递归剥掉厂商
            // 可能叠加的长度前缀/混合封装，再统一补回起始码——绝不在 App 侧转 AVCC。
            // 归一化后一个 NAL 都解不出来就丢帧，绝不把垃圾写进文件。
            val units = NalUnits.extract(data)
            if (units.isEmpty()) return true
            val annexB = NalUnits.toAnnexB(data)
            // 关键帧标记必须以样本内容为准：上游 keyFrame 标记在部分编码器路径上会与实际
            // NAL 不一致，导致整段 MP4 一个关键帧都没有，解码器找不到起播锚点而逐帧报
            // "missing picture"。AVC 直接检测 IDR(type5)；HEVC 关键帧类型不同，沿用上游标记。
            val effectiveKey = keyFrame || (!isHevc && NalUnits.containsAvcIdr(data))
            writeSample(videoTrack, annexB, relativeUs, effectiveKey)
            lastVideoUs = relativeUs
            videoFrames++
            return true
        }
    }

    /**
     * 写入一帧裸 AAC。视频尚未开始或时间戳早于视频起点时丢弃。
     */
    fun writeAudio(data: ByteArray, presentationTimeUs: Long) {
        synchronized(lock) {
            if (finished || audioFormat == null) return
            ensureMuxer()
            if (videoTrack < 0 || videoBaseUs == Long.MIN_VALUE) return
            if (audioTrack < 0) {
                audioTrack = muxer!!.addTrack(audioFormat)
                maybeStart()
            }
            if (audioBaseUs == Long.MIN_VALUE) {
                audioBaseUs = presentationTimeUs
            }
            if (!started) return
            val relativeUs = normalize(presentationTimeUs - audioBaseUs)
            if (relativeUs < 0L) return
            writeSample(audioTrack, data, relativeUs, true)
        }
    }

    /**
     * 达到分段时长后，是否应在此关键帧处滚动到下一段。
     */
    fun shouldRotateAtKeyFrame(presentationTimeUs: Long): Boolean {
        synchronized(lock) {
            if (videoBaseUs == Long.MIN_VALUE) return false
            return presentationTimeUs - videoBaseUs >= durationLimitUs
        }
    }

    /**
     * 关键帧长时间不来时的兜底滚动时间（分段已写入内容的墙钟时长）。
     */
    fun overdueByWall(wallMs: Long, graceMs: Long): Boolean {
        synchronized(lock) {
            if (videoBaseUs == Long.MIN_VALUE || firstVideoWallMs == 0L) return false
            return wallMs - firstVideoWallMs >= durationLimitUs / 1000L + graceMs
        }
    }

    fun hasVideo(): Boolean = synchronized(lock) { videoFrames > 0 }

    /**
     * 结束分段。有视频帧时保留文件并返回文件信息，否则删除文件返回 null。
     */
    fun finish(): SegmentFile? {
        synchronized(lock) {
            if (finished) return null
            finished = true
            val frames = videoFrames
            val durationUs = if (frames > 0) lastVideoUs else 0L
            val wallMs = firstVideoWallMs
            try {
                if (started) {
                    muxer?.stop()
                }
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            muxer = null
            started = false
            if (frames <= 0) {
                try {
                    partFile.delete()
                } catch (_: Exception) {
                }
                return null
            }
            if (file.exists()) {
                file.delete()
            }
            val renamed = try {
                partFile.renameTo(file)
            } catch (_: Exception) {
                false
            }
            if (!renamed) {
                try {
                    partFile.delete()
                } catch (_: Exception) {
                }
                return null
            }
            return SegmentFile(file, key, wallMs, durationUs)
        }
    }

    private fun ensureMuxer() {
        if (muxer == null) {
            file.parentFile?.mkdirs()
            muxer = MediaMuxer(partFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270) {
                try {
                    muxer!!.setOrientationHint(rotationDegrees)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun maybeStart() {
        if (started || videoTrack < 0) return
        if (audioFormat == null || audioTrack >= 0) {
            muxer!!.start()
            started = true
        }
    }

    private fun normalize(value: Long): Long {
        return if (value < 0L) 0L else value
    }

    private fun writeSample(track: Int, data: ByteArray, relativeUs: Long, keyFrame: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        bufferInfo.offset = 0
        bufferInfo.size = data.size
        bufferInfo.presentationTimeUs = relativeUs
        bufferInfo.flags = if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        try {
            muxer!!.writeSampleData(track, ByteBuffer.wrap(data), bufferInfo)
        } catch (_: Exception) {
        }
    }
}

internal data class SegmentFile(
    val file: File,
    val key: String,
    val startWallMs: Long,
    val durationUs: Long
)
