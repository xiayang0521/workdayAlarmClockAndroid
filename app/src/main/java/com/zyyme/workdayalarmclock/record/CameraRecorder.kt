package com.zyyme.workdayalarmclock.record

import android.media.MediaFormat
import android.os.Build
import com.zyyme.workdayalarmclock.camera.AacAudioPipeline
import com.zyyme.workdayalarmclock.camera.CameraHttpServer
import com.zyyme.workdayalarmclock.camera.CameraStreamFormat
import com.zyyme.workdayalarmclock.camera.CameraStreamKey
import com.zyyme.workdayalarmclock.camera.CameraStreamPacket
import com.zyyme.workdayalarmclock.camera.CameraStreamToken
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal data class RecordConfig(
    val cameraIndex: Int,
    val targetHeight: Int,
    val withAudio: Boolean,
    val segmentSeconds: Int = 60
)

internal object RecordingNames {
    private val NAME_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private const val PREFIX = "rec_"
    private const val SUFFIX = ".mp4"

    fun fileNameFor(wallMs: Long, cameraIndex: Int): String {
        return PREFIX + NAME_FORMAT.format(Date(wallMs)) + "_c" + cameraIndex + SUFFIX
    }

    fun isRecording(fileName: String): Boolean {
        return fileName.startsWith(PREFIX) && fileName.endsWith(SUFFIX)
    }

    fun timestampOf(fileName: String): Long {
        if (!isRecording(fileName)) return 0L
        return try {
            NAME_FORMAT.parse(fileName.substring(PREFIX.length, PREFIX.length + 15))?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}

/**
 * 设备端录像器：
 * 1. 经 CameraHttpServer 取得与直播共享的 H.264/AAC 管线；
 * 2. 视频线程在关键帧边界按分钟滚动 MediaMuxer 分段；
 * 3. 音频线程把裸 AAC 帧写入当前分段；
 * 4. 成片回调给上传队列；会话失效（服务停止/换密码/切清晰度）时自动重连。
 */
internal class CameraRecorder(
    private val serverProvider: () -> CameraHttpServer?,
    private val config: RecordConfig,
    private val outputDir: File,
    private val onSegmentReady: (SegmentFile) -> Unit,
    private val log: (String) -> Unit
) {
    companion object {
        private const val SEGMENT_GRACE_MS = 5_000L
        private const val RETRY_INTERVAL_MS = 1_000L
        private const val CONFIG_WAIT_MS = 5_000L
        private val ANNEX_B_START = byteArrayOf(0, 0, 0, 1)
    }

    private val running = AtomicBoolean(false)
    private var supervisorThread: Thread? = null

    @Volatile private var activeToken: CameraStreamToken? = null
    @Volatile private var activeWriter: RecordingSegmentWriter? = null

    fun isPlatformSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    fun start(): Boolean {
        if (!isPlatformSupported()) {
            log("录像需要 Android 5.0 以上系统")
            return false
        }
        if (!running.compareAndSet(false, true)) return true
        outputDir.mkdirs()
        supervisorThread = Thread({ supervisorLoop() }, "camera-recorder").apply { start() }
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val token = activeToken
        activeToken = null
        token?.let {
            try {
                serverProvider()?.releaseRecordingSession(it)
            } catch (_: Exception) {
            }
        }
        supervisorThread?.interrupt()
        try {
            supervisorThread?.join(2_000L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        supervisorThread = null
    }

    fun matches(other: RecordConfig): Boolean = config == other

    private fun supervisorLoop() {
        val streamKey = CameraStreamKey(
            format = CameraStreamFormat.AVC,
            cameraIndex = config.cameraIndex,
            targetHeight = config.targetHeight
        )
        while (running.get()) {
            val server = awaitServer() ?: break
            val token = try {
                server.acquireRecordingSession(streamKey, config.withAudio)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                null
            }
            if (token == null) {
                sleepQuietly(RETRY_INTERVAL_MS)
                continue
            }
            activeToken = token
            try {
                runSession(token)
            } catch (e: Exception) {
                if (running.get()) log("录像会话异常：${e.message}")
            } finally {
                finishCurrentWriter()
                activeToken = null
                try {
                    server.releaseRecordingSession(token)
                } catch (_: Exception) {
                }
            }
            if (running.get()) sleepQuietly(RETRY_INTERVAL_MS)
        }
    }

    private fun awaitServer(): CameraHttpServer? {
        while (running.get()) {
            val server = try {
                serverProvider()
            } catch (_: Exception) {
                null
            }
            if (server != null) return server
            if (!sleepQuietly(RETRY_INTERVAL_MS)) return null
        }
        return null
    }

    private fun runSession(token: CameraStreamToken) {
        val videoPipeline = token.videoPipeline ?: return
        val audioPipeline: AacAudioPipeline? = token.audioPipeline
        val avcConfig = videoPipeline.awaitAvcConfig(CONFIG_WAIT_MS)
        if (avcConfig == null) {
            log("录像等待H.264参数超时")
            return
        }
        try {
            videoPipeline.requestKeyFrame()
        } catch (_: Exception) {
        }

        val videoFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            avcConfig.width,
            avcConfig.height
        ).apply {
            val csd = ByteBuffer.allocate(
                ANNEX_B_START.size + avcConfig.sps.size + ANNEX_B_START.size + avcConfig.pps.size
            )
            csd.put(ANNEX_B_START).put(avcConfig.sps).put(ANNEX_B_START).put(avcConfig.pps)
            csd.clear()
            setByteBuffer("csd-0", csd)
        }
        val audioFormat = audioPipeline?.let { buildAudioFormat(it) }

        val audioThread = if (audioPipeline != null) {
            startAudioConsumer(audioPipeline)
        } else {
            null
        }

        val segmentLimitUs = config.segmentSeconds * 1_000_000L
        var videoSequence = 0L
        var forceRotate = false
        try {
            while (running.get() && !token.invalid) {
                val packet = videoPipeline.awaitPacket(videoSequence) ?: break
                videoSequence = packet.sequence
                var writer = activeWriter
                val now = System.currentTimeMillis()
                if (writer == null) {
                    if (!packet.keyFrame) continue
                    writer = createWriter(now, videoFormat, audioFormat, segmentLimitUs)
                    activeWriter = writer
                } else if (packet.keyFrame &&
                    (forceRotate || writer.shouldRotateAtKeyFrame(packet.presentationTimeUs))
                ) {
                    finishCurrentWriter()
                    writer = createWriter(now, videoFormat, audioFormat, segmentLimitUs)
                    activeWriter = writer
                    forceRotate = false
                } else if (writer.overdueByWall(now, SEGMENT_GRACE_MS)) {
                    forceRotate = true
                }
                writer.writeVideo(packet.data, packet.presentationTimeUs, packet.keyFrame)
            }
        } finally {
            audioThread?.shutdown()
        }
    }

    private fun buildAudioFormat(audioPipeline: AacAudioPipeline): MediaFormat {
        val audioConfig = audioPipeline.config
        return MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            audioConfig.sampleRate,
            audioConfig.channelCount
        ).apply {
            val csd = ByteBuffer.allocate(audioConfig.audioSpecificConfig.size)
            csd.put(audioConfig.audioSpecificConfig)
            csd.clear()
            setByteBuffer("csd-0", csd)
        }
    }

    private fun createWriter(
        wallMs: Long,
        videoFormat: MediaFormat,
        audioFormat: MediaFormat?,
        segmentLimitUs: Long
    ): RecordingSegmentWriter {
        val file = File(outputDir, RecordingNames.fileNameFor(wallMs, config.cameraIndex))
        return RecordingSegmentWriter(
            file,
            videoFormat,
            audioFormat,
            segmentLimitUs
        ).apply { beginWallTime(wallMs) }
    }

    private fun finishCurrentWriter() {
        val writer = activeWriter ?: return
        activeWriter = null
        val segment = try {
            writer.finish()
        } catch (e: Exception) {
            log("录像分段收尾失败：${e.message}")
            null
        }
        if (segment != null) {
            try {
                onSegmentReady(segment)
            } catch (e: Exception) {
                log("录像分段入队失败：${e.message}")
            }
        }
    }

    private fun startAudioConsumer(audioPipeline: AacAudioPipeline): AudioConsumer {
        return AudioConsumer(
            audioPipeline = audioPipeline,
            currentWriter = { activeWriter },
            isActive = { running.get() && activeToken?.invalid == false }
        ).also { it.start() }
    }

    private fun sleepQuietly(durationMs: Long): Boolean {
        return try {
            Thread.sleep(durationMs)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private class AudioConsumer(
        private val audioPipeline: AacAudioPipeline,
        private val currentWriter: () -> RecordingSegmentWriter?,
        private val isActive: () -> Boolean
    ) {
        private val running = AtomicBoolean(false)
        private var thread: Thread? = null

        fun start() {
            running.set(true)
            thread = Thread({ consumeLoop() }, "camera-recorder-audio").apply { start() }
        }

        fun shutdown() {
            running.set(false)
            thread?.interrupt()
            try {
                thread?.join(1_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            thread = null
        }

        private fun consumeLoop() {
            var sequence = 0L
            while (running.get() && isActive()) {
                val packet: CameraStreamPacket = try {
                    audioPipeline.awaitPacket(sequence)
                } catch (_: Exception) {
                    null
                } ?: break
                sequence = packet.sequence
                try {
                    currentWriter()?.writeAudio(packet.data, packet.presentationTimeUs)
                } catch (_: Exception) {
                }
            }
        }
    }
}
