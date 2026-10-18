package com.zyyme.workdayalarmclock.record

import android.media.MediaFormat
import android.os.Build
import com.zyyme.workdayalarmclock.MeSettings
import com.zyyme.workdayalarmclock.camera.AacAudioPipeline
import com.zyyme.workdayalarmclock.camera.CameraHttpServer
import com.zyyme.workdayalarmclock.camera.CameraStreamFormat
import com.zyyme.workdayalarmclock.camera.CameraStreamKey
import com.zyyme.workdayalarmclock.camera.NalUnits
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
    val segmentSeconds: Int = 60,
    val rotationDegrees: Int = 0,
    /** 静止策略：0 全录 1 抽帧 2 低码率 3 A+B（见 MeSettings.STATIC_POLICY_*）。 */
    val staticPolicy: Int = 0,
    /** "h264" 或 "h265"。 */
    val codec: String = "h264"
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
    // 动态时间标签（相对当前分段的毫秒偏移区间）
    private val motionIntervals = ArrayList<LongArray>()
    private var motionOpenStartUs = -1L
    private var motionBaseUs = Long.MIN_VALUE
    private var lastVideoUs = 0L
    private var recordMotion = false

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
            log("录像等待视频参数超时")
            return
        }
        try {
            videoPipeline.requestKeyFrame()
        } catch (_: Exception) {
        }

        // 以实际编码流为准（H.265 在设备不支持时已自动降级为 H.264）
        val actualHevc = avcConfig.codec == "hevc"
        val mime = if (actualHevc) {
            MediaFormat.MIMETYPE_VIDEO_HEVC
        } else {
            MediaFormat.MIMETYPE_VIDEO_AVC
        }
        val videoFormat = MediaFormat.createVideoFormat(
            mime,
            avcConfig.width,
            avcConfig.height
        ).apply {
            if (actualHevc && avcConfig.vps.isNotEmpty()) {
                // HEVC：csd-0 = 长度前缀拼接的 VPS+SPS+PPS（MediaMuxer 据此生成 hvcC）
                val csd = ByteBuffer.allocate(
                    12 + avcConfig.vps.size + avcConfig.sps.size + avcConfig.pps.size
                )
                fun putNal(nal: ByteArray) {
                    csd.putInt(nal.size)
                    csd.put(nal)
                }
                putNal(avcConfig.vps)
                putNal(avcConfig.sps)
                putNal(avcConfig.pps)
                csd.clear()
                setByteBuffer("csd-0", csd)
            } else {
                // MediaMuxer 依据 csd 生成 avcC：标准做法是 csd-0 只放 SPS、csd-1 只放 PPS，
                // 各自带 AnnexB 起始码（与 MediaCodec 编码器 outputFormat 给出的形态一致）。
                setByteBuffer("csd-0", buildCsdAnnexB(avcConfig.sps))
                setByteBuffer("csd-1", buildCsdAnnexB(avcConfig.pps))
            }
        }
        val rotation = normalizeRotation(config.rotationDegrees)
        val audioFormat = audioPipeline?.let { buildAudioFormat(it) }

        val audioThread = if (audioPipeline != null) {
            startAudioConsumer(audioPipeline)
        } else {
            null
        }

        val segmentLimitUs = config.segmentSeconds * 1_000_000L
        // 静止策略：A=抽帧（只写关键帧→约1fps），B=低码率（动态降到 100kbps）
        val timelapseWhenStatic = config.staticPolicy == MeSettings.STATIC_POLICY_TIMELAPSE ||
            config.staticPolicy == MeSettings.STATIC_POLICY_BOTH
        val lowBitrateWhenStatic = config.staticPolicy == MeSettings.STATIC_POLICY_LOW_BITRATE ||
            config.staticPolicy == MeSettings.STATIC_POLICY_BOTH
        var currentMoving = true
        var videoSequence = 0L
        var forceRotate = false
        // 动态时间标签：只在具备静止检测时记录
        recordMotion = videoPipeline.hasMotionDetection()
        motionIntervals.clear()
        motionOpenStartUs = -1L
        motionBaseUs = Long.MIN_VALUE
        lastVideoUs = 0L
        try {
            while (running.get() && !token.invalid) {
                val packet = videoPipeline.awaitPacket(videoSequence) ?: break
                videoSequence = packet.sequence
                lastVideoUs = packet.presentationTimeUs
                if (recordMotion) {
                    if (motionBaseUs == Long.MIN_VALUE) motionBaseUs = packet.presentationTimeUs
                    if (videoPipeline.isMoving()) {
                        if (motionOpenStartUs < 0) motionOpenStartUs = packet.presentationTimeUs
                    } else if (motionOpenStartUs >= 0) {
                        motionIntervals.add(
                            longArrayOf(
                                (motionOpenStartUs - motionBaseUs) / 1000,
                                (packet.presentationTimeUs - motionBaseUs) / 1000
                            )
                        )
                        motionOpenStartUs = -1
                    }
                }
                // 静止检测状态切换：变动→请求关键帧恢复正常；静止→按需降码率/抽帧
                if (timelapseWhenStatic || lowBitrateWhenStatic) {
                    val moving = videoPipeline.isMoving()
                    if (moving != currentMoving) {
                        currentMoving = moving
                        try {
                            videoPipeline.requestKeyFrame()
                        } catch (_: Exception) {
                        }
                        if (lowBitrateWhenStatic) {
                            try {
                                videoPipeline.setDynamicBitrate(if (moving) 0 else 100_000)
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
                // 关键帧与分段边界一律以样本内容为准：上游 packet.keyFrame 在部分编码器
                // 路径上会与实际 NAL 不一致，导致 MP4 里一个关键帧都没有，
                // 解码器找不到起播锚点而逐帧报 missing picture（只出一帧后一直转圈）。
                // NalUnits 递归展平后再判 IDR，能正确识别段内嵌套长度前缀的关键帧。
                val realKeyFrame = packet.keyFrame ||
                    (!actualHevc && NalUnits.containsAvcIdr(packet.data))
                if (timelapseWhenStatic && !currentMoving && !realKeyFrame) continue
                var writer = activeWriter
                val now = System.currentTimeMillis()
                if (writer == null) {
                    if (!realKeyFrame) continue
                    writer = createWriter(
                        now, videoFormat, audioFormat, segmentLimitUs, rotation, actualHevc
                    )
                    activeWriter = writer
                    motionBaseUs = packet.presentationTimeUs
                    motionOpenStartUs = -1
                    forceRotate = false
                } else if (realKeyFrame &&
                    (forceRotate || writer.shouldRotateAtKeyFrame(packet.presentationTimeUs))
                ) {
                    finishCurrentWriter()
                    writer = createWriter(
                        now, videoFormat, audioFormat, segmentLimitUs, rotation, actualHevc
                    )
                    activeWriter = writer
                    motionBaseUs = packet.presentationTimeUs
                    motionOpenStartUs = -1
                    forceRotate = false
                } else if (writer.overdueByWall(now, SEGMENT_GRACE_MS)) {
                    forceRotate = true
                }
                writer.writeVideo(packet.data, packet.presentationTimeUs, realKeyFrame)
            }
        } finally {
            audioThread?.shutdown()
        }
    }

    /**
     * 把单个参数集（SPS 或 PPS）包装成带 AnnexB 起始码的 csd ByteBuffer。
     */
    private fun buildCsdAnnexB(parameterSet: ByteArray): ByteBuffer {
        val buffer = ByteBuffer.allocate(ANNEX_B_START.size + parameterSet.size)
        buffer.put(ANNEX_B_START).put(parameterSet)
        buffer.clear()
        return buffer
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
        segmentLimitUs: Long,
        rotationDegrees: Int,
        isHevc: Boolean
    ): RecordingSegmentWriter {
        val file = File(outputDir, RecordingNames.fileNameFor(wallMs, config.cameraIndex))
        return RecordingSegmentWriter(
            file,
            videoFormat,
            audioFormat,
            segmentLimitUs,
            rotationDegrees,
            isHevc
        ).apply { beginWallTime(wallMs) }
    }

    private fun normalizeRotation(value: Int): Int {
        return if (value == 90 || value == 180 || value == 270) value else 0
    }

    private fun finishCurrentWriter() {
        closeMotionInterval()
        val writer = activeWriter ?: return
        activeWriter = null
        val segment = try {
            writer.finish()
        } catch (e: Exception) {
            log("录像分段收尾失败：${e.message}")
            null
        }
        val snapshot = motionIntervalsSnapshot()
        motionIntervals.clear()
        motionBaseUs = Long.MIN_VALUE
        motionOpenStartUs = -1
        if (segment != null) {
            try {
                MotionMarks.write(segment.file, snapshot)
            } catch (e: Exception) {
                log("动态标签写入失败：${e.message}")
            }
            try {
                onSegmentReady(segment)
            } catch (e: Exception) {
                log("录像分段入队失败：${e.message}")
            }
        }
    }

    /** 关闭进行中的动态区间（以最近一包为结束）。 */
    private fun closeMotionInterval() {
        if (motionOpenStartUs >= 0 && motionBaseUs != Long.MIN_VALUE && recordMotion) {
            val start = (motionOpenStartUs - motionBaseUs) / 1000
            val end = (lastVideoUs - motionBaseUs) / 1000
            if (end > start) motionIntervals.add(longArrayOf(start, end))
        }
        motionOpenStartUs = -1
    }

    private fun motionIntervalsSnapshot(): List<LongArray> = ArrayList(motionIntervals)

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
