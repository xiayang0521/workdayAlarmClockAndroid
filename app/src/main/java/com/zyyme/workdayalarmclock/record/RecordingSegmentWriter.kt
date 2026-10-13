package com.zyyme.workdayalarmclock.record

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
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
    private val durationLimitUs: Long
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
            writeSample(videoTrack, data, relativeUs, keyFrame)
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
