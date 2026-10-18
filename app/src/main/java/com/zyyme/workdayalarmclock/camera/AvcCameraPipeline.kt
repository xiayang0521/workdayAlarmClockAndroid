package com.zyyme.workdayalarmclock.camera

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import com.zyyme.workdayalarmclock.MeSettings
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
internal class AvcCameraPipeline(
    private val context: Context,
    override val key: CameraStreamKey,
    private val log: (String) -> Unit
) : CameraStreamPipeline {
    companion object {
        private const val START_TIMEOUT_SECONDS = 10L
        private const val ANALYSIS_WIDTH = 320
        private const val ANALYSIS_HEIGHT = 240

        private data class CameraTarget(
            val logicalCameraId: String,
            val physicalCameraId: String?
        )

        fun cameraCount(context: Context): Int {
            return try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                enumerateTargets(manager).size
            } catch (_: Exception) {
                0
            }
        }

        private fun enumerateTargets(manager: CameraManager): List<CameraTarget> {
            return manager.cameraIdList.flatMap { logicalId ->
                val targets = mutableListOf(CameraTarget(logicalId, null))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        val characteristics = manager.getCameraCharacteristics(logicalId)
                        val physicalIds = characteristics
                            .getPhysicalCameraIds()
                            .toList()
                            .sorted()
                        physicalIds.forEach { physicalId ->
                            if (physicalId != logicalId) {
                                targets.add(CameraTarget(logicalId, physicalId))
                            }
                        }
                    } catch (_: Exception) {
                        // Keep the logical camera when physical metadata is unavailable.
                    }
                }
                targets
            }
        }
    }

    private data class Selection(
        val cameraId: String,
        val physicalCameraId: String?,
        val size: Size,
        val fps: Int,
        val aeRange: Range<Int>?
    )

    private val frameHub = CameraFrameHub(maxPackets = 120, keepLatestOnly = false)
    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val startResolved = AtomicBoolean(false)
    private val startSucceeded = AtomicBoolean(false)
    private val startLatch = CountDownLatch(1)
    private val codecConfigMonitor = Object()

    private var avcStreamConfig: AvcStreamConfig? = null
    @Volatile private var annexbNative = false
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var codec: MediaCodec? = null
    private var codecSurface: Surface? = null
    private var overlayRenderer: OverlayRenderer? = null
    private var cameraTargetSurface: Surface? = null
    private var drainThread: Thread? = null
    private var selectedWidth = 0
    private var selectedHeight = 0
    private var selectedFps = 0
    private var analysisReader: ImageReader? = null
    private var motionDetector: MotionDetector? = null
    private var baseBitrate = 0

    /** 实际使用的编码类型：选择 H.265 但设备不支持时自动退回 H.264。 */
    @Volatile private var codecMime: String = MediaFormat.MIMETYPE_VIDEO_AVC

    /** 按设置创建编码器；H.265 创建失败时降级到 H.264 并提示。 */
    private fun createEncoder(): MediaCodec {
        val wantHevc = MeSettings.getRecordCodec(context) == MeSettings.CODEC_H265 &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
        if (wantHevc) {
            try {
                val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                codecMime = MediaFormat.MIMETYPE_VIDEO_HEVC
                return encoder
            } catch (e: Exception) {
                log("本机不支持 H.265(HEVC) 硬件编码，已自动改用 H.264：${e.message}")
            }
        }
        codecMime = MediaFormat.MIMETYPE_VIDEO_AVC
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    }

    override val description: String
        get() = "${if (codecMime == MediaFormat.MIMETYPE_VIDEO_HEVC) "H.265" else "H.264"} ${selectedWidth}x${selectedHeight} ${selectedFps}fps"

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        try {
            cameraThread = HandlerThread("camera-avc-${key.cameraIndex}").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)

            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val encoder = createEncoder()
            codec = encoder
            val selection = selectConfiguration(cameraManager, encoder)
                ?: throw IllegalStateException("没有可用的编码输出规格")
            selectedWidth = selection.size.width
            selectedHeight = selection.size.height
            selectedFps = selection.fps

            // 静止检测分析流：仅在启用抽帧/低码率策略时创建
            if (MeSettings.getRecordStaticPolicy(context) != MeSettings.STATIC_POLICY_ALL) {
                try {
                    val reader = ImageReader.newInstance(
                        ANALYSIS_WIDTH, ANALYSIS_HEIGHT, ImageFormat.YUV_420_888, 2
                    )
                    analysisReader = reader
                    motionDetector = MotionDetector(
                        reader,
                        MeSettings.motionThresholdRatio(context)
                    ) { moving ->
                        log(if (moving) "画面变动，恢复正常录像" else "画面静止，进入省空间录像")
                    }
                } catch (e: Exception) {
                    log("静止检测初始化失败：${e.message}")
                    analysisReader = null
                    motionDetector = null
                }
            }

            encoder.configure(
                createEncoderFormat(),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            codecSurface = encoder.createInputSurface()
            // 录像画面时间水印：GL 叠加"日期时间+移动/静止"标签；失败自动降级为无水印直连接
            try {
                val renderer = OverlayRenderer(
                    codecSurface!!, selectedWidth, selectedHeight,
                    MeSettings.getRecordRotation(context)
                ) {
                    motionDetector?.isMoving ?: true
                }
                overlayRenderer = renderer
                cameraTargetSurface = renderer.cameraSurface() ?: codecSurface
                if (renderer.cameraSurface() == null) {
                    log("时间水印初始化失败，使用无水印模式")
                } else {
                    log("录像时间水印已开启")
                }
            } catch (e: Exception) {
                log("时间水印初始化失败，使用无水印模式：${e.message}")
                overlayRenderer = null
                cameraTargetSurface = codecSurface
            }
            encoder.start()
            startDrainThread(encoder)

            cameraManager.openCamera(
                selection.cameraId,
                cameraStateCallback(selection),
                cameraHandler
            )
        } catch (e: Exception) {
            log("${if (codecMime == MediaFormat.MIMETYPE_VIDEO_HEVC) "H.265" else "H.264"}摄像头启动失败：${e.message}")
            resolveStart(false)
        }

        val completed = try {
            startLatch.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!completed || !startSucceeded.get()) {
            stop()
            return false
        }
        return true
    }

    private fun selectConfiguration(
        cameraManager: CameraManager,
        encoder: MediaCodec
    ): Selection? {
        val target = enumerateTargets(cameraManager).getOrNull(key.cameraIndex) ?: return null
        val targetFps = MeSettings.getRecordFps(context)
        val characteristics = cameraManager.getCameraCharacteristics(
            target.physicalCameraId ?: target.logicalCameraId
        )
        val streamMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return null
        // ImageFormat.PRIVATE is the camera output format used by an encoder input Surface.
        // The integer overload is available on API 21, unlike getOutputSizes(Class), which
        // was added later and would break Android 5.0/5.1 devices at runtime.
        val sizes = streamMap.getOutputSizes(ImageFormat.PRIVATE)?.toList().orEmpty()
        if (sizes.isEmpty()) return null

        val codecCapabilities = encoder.codecInfo.getCapabilitiesForType(codecMime)
        if (!codecCapabilities.colorFormats.contains(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
        ) return null
        val videoCapabilities = codecCapabilities.videoCapabilities ?: return null
        val aeRanges = characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        ).orEmpty().toList()
        val targetAeRange = selectFpsRangeContaining(aeRanges, targetFps)

        val usableSizes = sizes.filter { size ->
            try {
                videoCapabilities.isSizeSupported(size.width, size.height)
            } catch (_: Exception) {
                false
            }
        }
        if (usableSizes.isEmpty()) return null

        val resolutionIndex = key.resolutionIndex
        val targetHeight = key.targetHeight
        val chosenSize = when {
            targetHeight != null -> selectSizeByTargetHeight(usableSizes, targetHeight)
            resolutionIndex != null -> sortedSizes(usableSizes).getOrNull(resolutionIndex) ?: return null
            else -> {
            // 默认取摄像头最高像素：优先选择能跑满目标帧率的最大尺寸，
            // 都不支持目标帧率时退回最大可用尺寸（帧率由后续逻辑自动压低）。
            val preferredSizes = usableSizes.filter { size ->
                if (targetAeRange == null) return@filter false
                val cameraDuration = try {
                    streamMap.getOutputMinFrameDuration(ImageFormat.PRIVATE, size)
                } catch (_: Exception) {
                    0L
                }
                val cameraSupportsTarget = cameraDuration <= 0L ||
                    cameraDuration <= 1_000_000_000L / targetFps
                val encoderSupportsTarget = try {
                    videoCapabilities.areSizeAndRateSupported(
                        size.width,
                        size.height,
                        targetFps.toDouble()
                    )
                } catch (_: Exception) {
                    false
                }
                cameraSupportsTarget && encoderSupportsTarget
            }
            largestSize(if (preferredSizes.isNotEmpty()) preferredSizes else usableSizes)
            }
        }

        val cameraDuration = try {
            streamMap.getOutputMinFrameDuration(ImageFormat.PRIVATE, chosenSize)
        } catch (_: Exception) {
            0L
        }
        val cameraMaxFps = if (cameraDuration > 0L) {
            floor(1_000_000_000.0 / cameraDuration).toInt().coerceAtLeast(1)
        } else {
            targetFps
        }
        val encoderMaxFps = try {
            floor(
                videoCapabilities.getSupportedFrameRatesFor(
                    chosenSize.width,
                    chosenSize.height
                ).upper
            ).toInt().coerceAtLeast(1)
        } catch (_: Exception) {
            targetFps
        }
        val outputFps = min(targetFps, min(cameraMaxFps, encoderMaxFps)).coerceAtLeast(1)
        val aeRange = selectFpsRange(aeRanges, outputFps)
        val actualFps = min(outputFps, aeRange?.upper ?: outputFps).coerceAtLeast(1)
        return Selection(
            target.logicalCameraId,
            target.physicalCameraId,
            chosenSize,
            actualFps,
            aeRange
        )
    }

    private fun createEncoderFormat(): MediaFormat {
        return MediaFormat.createVideoFormat(
            codecMime,
            selectedWidth,
            selectedHeight
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            baseBitrate = max(128_000, selectedWidth * selectedHeight * 2)
            setInteger(MediaFormat.KEY_BIT_RATE, baseBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, selectedFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
    }

    private fun largestSize(sizes: List<Size>): Size {
        return sortedSizes(sizes).first()
    }

    private fun sortedSizes(sizes: List<Size>): List<Size> {
        return sizes.sortedWith(
            compareByDescending<Size> { it.width.toLong() * it.height }
                .thenByDescending { it.width }
        )
    }

    private fun selectSizeByTargetHeight(sizes: List<Size>, targetHeight: Int): Size {
        fun Size.shorterEdge() = min(width, height)
        val meeting = sizes.filter { it.shorterEdge() >= targetHeight }
        if (meeting.isNotEmpty()) {
            return meeting.minWithOrNull(
                compareBy<Size> { it.shorterEdge() }
                    .thenBy { it.width.toLong() * it.height }
            )!!
        }
        return sizes.maxWithOrNull(
            compareBy<Size> { it.shorterEdge() }
                .thenBy { it.width.toLong() * it.height }
        )!!
    }

    private fun selectFpsRange(ranges: List<Range<Int>>, targetFps: Int): Range<Int>? {
        val containing = selectFpsRangeContaining(ranges, targetFps)
        if (containing != null) return containing
        return ranges.maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
    }

    private fun selectFpsRangeContaining(
        ranges: List<Range<Int>>,
        targetFps: Int
    ): Range<Int>? {
        return ranges
            .filter { it.lower <= targetFps && it.upper >= targetFps }
            .sortedWith(compareByDescending<Range<Int>> { it.lower }.thenBy { it.upper - it.lower })
            .firstOrNull()
    }

    private fun cameraStateCallback(selection: Selection): CameraDevice.StateCallback {
        return object : CameraDevice.StateCallback() {
            override fun onOpened(openedCamera: CameraDevice) {
                if (!running.get()) {
                    openedCamera.close()
                    resolveStart(false)
                    return
                }
                cameraDevice = openedCamera
                val surface = cameraTargetSurface
                if (surface == null) {
                    resolveStart(false)
                    return
                }
                try {
                    createCaptureSession(openedCamera, surface, selection)
                } catch (e: Exception) {
                    log("创建H.264采集会话失败：${e.message}")
                    resolveStart(false)
                }
            }

            override fun onDisconnected(disconnectedCamera: CameraDevice) {
                disconnectedCamera.close()
                if (cameraDevice === disconnectedCamera) cameraDevice = null
                resolveStart(false)
                closeStreamAfterRuntimeFailure()
            }

            override fun onError(errorCamera: CameraDevice, error: Int) {
                errorCamera.close()
                if (cameraDevice === errorCamera) cameraDevice = null
                log("H.264摄像头错误：$error")
                resolveStart(false)
                closeStreamAfterRuntimeFailure()
            }
        }
    }

    @Suppress("NewApi")
    private fun createCaptureSession(
        openedCamera: CameraDevice,
        surface: Surface,
        selection: Selection
    ) {
        val callback = captureSessionCallback(openedCamera, surface, selection)
        val analysisSurface = analysisReader?.surface
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            selection.physicalCameraId != null
        ) {
            val output = OutputConfiguration(surface).apply {
                setPhysicalCameraId(selection.physicalCameraId)
            }
            val outputs = mutableListOf(output)
            if (analysisSurface != null) outputs.add(OutputConfiguration(analysisSurface))
            val executor = Executor { command ->
                val handler = cameraHandler
                if (handler != null) handler.post(command) else command.run()
            }
            openedCamera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    executor,
                    callback
                )
            )
        } else {
            val surfaces = mutableListOf(surface)
            if (analysisSurface != null) surfaces.add(analysisSurface)
            @Suppress("DEPRECATION")
            openedCamera.createCaptureSession(surfaces, callback, cameraHandler)
        }
    }

    private fun captureSessionCallback(
        openedCamera: CameraDevice,
        surface: Surface,
        selection: Selection
    ): CameraCaptureSession.StateCallback {
        return object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (!running.get()) {
                    session.close()
                    resolveStart(false)
                    return
                }
                try {
                    val request = openedCamera.createCaptureRequest(
                        CameraDevice.TEMPLATE_RECORD
                    ).apply {
                        addTarget(surface)
                        analysisReader?.surface?.let { addTarget(it) }
                        set(
                            CaptureRequest.CONTROL_MODE,
                            CaptureRequest.CONTROL_MODE_AUTO
                        )
                        selection.aeRange?.let {
                            set(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                it
                            )
                        }
                    }
                    captureSession = session
                    session.setRepeatingRequest(request.build(), null, cameraHandler)
                    motionDetector?.start()
                    log("摄像头流已准备：$description")
                    resolveStart(true)
                } catch (e: Exception) {
                    log("启动H.264采集失败：${e.message}")
                    resolveStart(false)
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                session.close()
                log("H.264采集会话配置失败")
                resolveStart(false)
            }
        }
    }

    private fun resolveStart(success: Boolean) {
        if (startResolved.compareAndSet(false, true)) {
            startSucceeded.set(success)
            startLatch.countDown()
        }
    }

    private fun startDrainThread(encoder: MediaCodec) {
        drainThread = Thread({
            val bufferInfo = MediaCodec.BufferInfo()
            try {
                while (running.get()) {
                    when (val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            updateCodecConfig(encoder.outputFormat)
                        }
                        else -> if (outputIndex >= 0) {
                            val outputBuffer = encoder.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val bytes = ByteArray(bufferInfo.size)
                                outputBuffer.get(bytes)
                                // 编码器输出形态随厂商而异（AVCC/AnnexB/嵌套/起始码分段内
                                // 又带长度前缀的混合形态），统一交给 NalUnits 递归展平为
                                // 干净 AnnexB，供直播与录像共用。早期本地实现不剥段内长度
                                // 前缀，是录像 L4(L4(IDR)) 黑屏有声的根因，已收敛到此工具。
                                val normalized = NalUnits.toAnnexB(bytes)
                                val isConfig = bufferInfo.flags and
                                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                if (isConfig) {
                                    updateCodecConfig(normalized)
                                } else {
                                    val keyFrame = bufferInfo.flags and
                                        MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 ||
                                        (codecMime == MediaFormat.MIMETYPE_VIDEO_AVC &&
                                            NalUnits.containsAvcIdr(normalized))
                                    frameHub.publish(
                                        normalized,
                                        keyFrame,
                                        bufferInfo.presentationTimeUs
                                    )
                                }
                            }
                            encoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) log("H.264编码输出失败：${e.message}")
                closeStreamAfterRuntimeFailure()
            }
        }, "camera-avc-encoder-${key.cameraIndex}").apply { start() }
    }

    private fun updateCodecConfig(format: MediaFormat) {
        val output = ByteArrayOutputStream()
        listOf("csd-0", "csd-1", "csd-2").forEach { name ->
            val buffer = format.getByteBuffer(name) ?: return@forEach
            val duplicate = buffer.duplicate()
            val bytes = ByteArray(duplicate.remaining())
            duplicate.get(bytes)
            // 配置缓冲自带起始码 = AnnexB 原生栈（系统 MediaMuxer 会自动转 AVCC）。
            if (hasStartCode(bytes, 0)) annexbNative = true
            output.write(NalUnits.toAnnexB(bytes))
        }
        updateCodecConfig(output.toByteArray())
    }

    private fun updateCodecConfig(annexBConfig: ByteArray) {
        if (hasStartCode(annexBConfig, 0)) annexbNative = true
        val parsed = FragmentedMp4Muxer.Companion.parseAvcConfig(
            annexBConfig,
            selectedWidth,
            selectedHeight,
            selectedFps
        ) ?: return
        synchronized(codecConfigMonitor) {
            avcStreamConfig = parsed
            codecConfigMonitor.notifyAll()
        }
    }

    private fun hasStartCode(data: ByteArray, offset: Int): Boolean {
        if (offset + 3 >= data.size) return false
        return data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
            (data[offset + 2] == 1.toByte() ||
                (data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()))
    }

    override fun awaitPacket(afterSequence: Long): CameraStreamPacket? {
        return frameHub.awaitNext(afterSequence)
    }

    override fun isAnnexbNative(): Boolean = annexbNative

    override fun awaitAvcConfig(timeoutMillis: Long): AvcStreamConfig? {        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        synchronized(codecConfigMonitor) {
            while (running.get() && avcStreamConfig == null) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                try {
                    codecConfigMonitor.wait(remaining)
                } catch (_: InterruptedException) {
                    break
                }
            }
            return avcStreamConfig
        }
    }

    override fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Exception) {
        }
    }

    override fun isMoving(): Boolean = motionDetector?.isMoving ?: true

    override fun hasMotionDetection(): Boolean = motionDetector != null

    override fun setDynamicBitrate(bitsPerSecond: Int) {
        val codec = codec ?: return
        val target = if (bitsPerSecond > 0) bitsPerSecond else baseBitrate
        try {
            codec.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, target)
            })
        } catch (_: Exception) {
        }
    }

    private fun closeStreamAfterRuntimeFailure() {
        running.set(false)
        frameHub.close()
        synchronized(codecConfigMonitor) {
            codecConfigMonitor.notifyAll()
        }
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        running.set(false)
        frameHub.close()
        resolveStart(false)
        synchronized(codecConfigMonitor) {
            codecConfigMonitor.notifyAll()
        }

        motionDetector?.stop()
        motionDetector = null
        try {
            analysisReader?.close()
        } catch (_: Exception) {
        }
        analysisReader = null

        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {
        }
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null
        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        cameraDevice = null

        drainThread?.interrupt()
        try {
            drainThread?.join(500L)
        } catch (_: InterruptedException) {
        }
        drainThread = null
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        try {
            overlayRenderer?.release()
        } catch (_: Exception) {
        }
        overlayRenderer = null
        cameraTargetSurface = null
        try {
            codecSurface?.release()
        } catch (_: Exception) {
        }
        codecSurface = null

        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }
}
