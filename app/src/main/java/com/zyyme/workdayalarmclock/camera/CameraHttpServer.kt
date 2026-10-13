package com.zyyme.workdayalarmclock.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Camera
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.util.DisplayMetrics
import android.graphics.Point
import android.view.Surface
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.zyyme.workdayalarmclock.ClockActivity
import com.zyyme.workdayalarmclock.DeskActivity
import com.zyyme.workdayalarmclock.MeService
import com.zyyme.workdayalarmclock.record.RecordingAccess
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.Charset
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class CameraStreamToken(
    @Volatile var videoPipeline: CameraStreamPipeline?,
    @Volatile var audioPipeline: AacAudioPipeline?,
    @Volatile var invalid: Boolean = false
)

@Suppress("DEPRECATION")
internal class CameraHttpServer(
    context: Context,
    private val brightness: AmbientBrightnessController,
    private val log: (String) -> Unit,
    private val cameraEnabled: () -> Boolean,
    private val speakerEnabled: () -> Boolean,
    private val framebufferFrame: (ByteArray, Int) -> Unit,
    private val framebufferStreamState: (Boolean) -> Unit
) {
    companion object {
        const val PORT = 8880
        private const val MAX_JPEG_FRAME_BYTES = 16 * 1024 * 1024
        private val HTTP_CHARSET: Charset = Charset.forName("US-ASCII")
        private val HTML_CHARSET: Charset = Charset.forName("UTF-8")
    }

    private val appContext = context.applicationContext
    private val meBgCamCtrl = MeBgCamCtrl(appContext)
    private val running = AtomicBoolean(false)
    private val stateLock = Any()
    private val sessionChangeLock = Any()
    private val audioSessionChangeLock = Any()
    private val streamLockState = Any()
    private val clients = LinkedHashMap<Socket, CameraStreamPipeline>()
    private val audioClients = LinkedHashSet<Socket>()
    private var recordingVideoToken: CameraStreamToken? = null
    private var recordingAudio = false
    private val framebufferStreamActive = AtomicBoolean(false)
    @Volatile private var password = ""
    @Volatile private var recordingAccess: RecordingAccess? = null
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var activeFramebufferSocket: Socket? = null
    private var activePipeline: CameraStreamPipeline? = null
    private var activeAudioPipeline: AacAudioPipeline? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var streamLockUsers = 0

    fun handleSensorKeyEvent(keyCode: Int, isDown: Boolean): Boolean =
        meBgCamCtrl.handleSensorKeyEvent(keyCode, isDown)

    fun start(cameraPassword: String): Boolean {
        val cleanPassword = normalizePassword(cameraPassword)
        if (running.get()) {
            updatePassword(cleanPassword)
            return true
        }
        password = cleanPassword
        if (!running.compareAndSet(false, true)) return true

        return try {
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
            serverSocket = socket
            acceptThread = Thread({ acceptLoop(socket) }, "camera-http-accept").apply { start() }
            log("摄像头HTTP服务已启动，端口$PORT")
            true
        } catch (e: Exception) {
            running.set(false)
            serverSocket = null
            log("摄像头HTTP服务启动失败：${e.message}")
            false
        }
    }

    fun setRecordingAccess(access: RecordingAccess?) {
        recordingAccess = access
    }

    fun updatePassword(cameraPassword: String) {
        val cleanPassword = normalizePassword(cameraPassword)
        if (password == cleanPassword) return
        password = cleanPassword
        closeActiveSession()
        closeActiveAudioSession()
        log("摄像头密码已更新，现有媒体流已断开")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        try {
            activeFramebufferSocket?.close()
        } catch (_: Exception) {
        }
        closeActiveSession()
        closeActiveAudioSession()
        acceptThread?.interrupt()
        acceptThread = null
        log("摄像头HTTP服务已停止")
    }

    private fun normalizePassword(value: String): String {
        return value.trim().trim('/')
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (running.get()) {
            try {
                val socket = listener.accept().apply {
                    tcpNoDelay = true
                    soTimeout = 5_000
                }
                Thread({ handleClient(socket) }, "camera-http-client").start()
            } catch (e: SocketException) {
                if (running.get()) log("摄像头HTTP监听失败：${e.message}")
            } catch (e: Exception) {
                if (running.get()) log("摄像头HTTP连接失败：${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        var pipeline: CameraStreamPipeline? = null
        var audioPipeline: AacAudioPipeline? = null
        try {
            val request = readRequest(socket) ?: return
            val path = request.path
            if ((request.method == "POST" || request.method == "PUT") && path == "/aplay") {
                if (!speakerEnabled()) {
                    writeEmptyResponse(socket, 404, "Not Found")
                    return
                }
                if (request.expectContinue) {
                    socket.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(HTTP_CHARSET))
                    socket.getOutputStream().flush()
                }
                socket.soTimeout = 0
                streamPcm(socket, request)
                return
            }
            if (path == "/fbinfo") {
                if (request.method == "GET") {
                    writeFramebufferInfo(socket)
                } else {
                    writeEmptyResponse(socket, 405, "Method Not Allowed")
                }
                return
            }
            if (path == "/fb") {
                if (request.method != "POST" && request.method != "PUT") {
                    writeEmptyResponse(socket, 405, "Method Not Allowed")
                    return
                }
                streamFramebuffer(socket, request)
                return
            }
            if (path?.let { meBgCamCtrl.matches(it) } == true) {
                meBgCamCtrl.handle(socket, request.method, request.uri)
                return
            }
            if (handleRecordingRoute(socket, request, path)) {
                return
            }
            if (!cameraEnabled()) {
                writeEmptyResponse(socket, 404, "Not Found")
                return
            }
            if (path == "/" && request.method == "GET") {
                writePlayerPage(socket)
                return
            }
            if (path?.let { isAvcCapabilityRoute(it) } == true) {
                writeAvcCapability(socket)
                return
            }
            if (path?.let { isBrightnessRoute(it) } == true) {
                writeBrightness(socket)
                return
            }
            if (path?.let { isAacRoute(it) } == true) {
                if (!canStreamMicrophone()) {
                    writeEmptyResponse(socket, 204, "No Content")
                    return
                }
                audioPipeline = acquireAudioPipeline(socket)
                if (audioPipeline == null) {
                    writeEmptyResponse(socket, 503, "Service Unavailable")
                    return
                }
                socket.soTimeout = 0
                streamAac(socket, audioPipeline)
                return
            }
            val route = path?.let { parseRoute(it) }
            if (route == null || !cameraExists(route)) {
                writeEmptyResponse(socket, 404, "Not Found")
                return
            }

            pipeline = acquirePipeline(route, socket)
            if (pipeline == null) {
                writeEmptyResponse(socket, 503, "Service Unavailable")
                return
            }
            socket.soTimeout = 0
            when (route.format) {
                CameraStreamFormat.MJPEG -> streamMjpeg(socket, pipeline)
                CameraStreamFormat.AVC -> {
                    val config = pipeline.awaitAvcConfig(10_000L)
                    if (config == null) {
                        writeEmptyResponse(socket, 503, "Service Unavailable")
                    } else {
                        streamAvc(socket, pipeline, config)
                    }
                }
            }
        } catch (e: Exception) {
            print2LogView("HTTP客户端处理失败：${e.javaClass.simpleName}: ${e.message ?: "无错误信息"}")
        } finally {
            releaseClient(socket, pipeline)
            releaseAudioClient(socket, audioPipeline)
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val uri: Uri,
        val body: InputStream,
        val expectContinue: Boolean,
        val contentType: String?,
        val rangeHeader: String?
    )

    private fun readRequest(socket: Socket): HttpRequest? {
        val input = socket.getInputStream()
        val requestLine = readHttpLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts[0] !in setOf("GET", "POST", "PUT")) return null

        var headerCount = 0
        var chunked = false
        var expectContinue = false
        var contentType: String? = null
        var rangeHeader: String? = null
        while (true) {
            val line = readHttpLine(input) ?: return null
            if (line.isEmpty()) break
            headerCount++
            if (headerCount > 100) return null
            val headerName = line.substringBefore(':').trim()
            val headerValue = line.substringAfter(':', "").trim()
            if (headerName.equals("Transfer-Encoding", ignoreCase = true)) {
                chunked = headerValue.split(',')
                    .any { it.trim().equals("chunked", ignoreCase = true) }
            }
            if (headerName.equals("Expect", ignoreCase = true)) {
                expectContinue = headerValue.equals("100-continue", ignoreCase = true)
            }
            if (headerName.equals("Content-Type", ignoreCase = true)) {
                contentType = headerValue
            }
            if (headerName.equals("Range", ignoreCase = true)) {
                rangeHeader = headerValue
            }
        }

        val uri = try {
            Uri.parse(parts[1])
        } catch (_: Exception) {
            return null
        }
        if (uri.fragment != null) return null
        val body = if (chunked) ChunkedInputStream(input) else input
        return HttpRequest(
            parts[0],
            uri.path.toString(),
            uri,
            body,
            expectContinue,
            contentType,
            rangeHeader
        )
    }

    private fun writeFramebufferInfo(socket: Socket) {
        val displaySize = activeActivityDisplaySize()
        val metrics = DisplayMetrics()
        if (displaySize == null) {
            val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.defaultDisplay.getMetrics(metrics)
        }
        val width = (displaySize?.x ?: metrics.widthPixels).coerceAtLeast(1)
        val height = (displaySize?.y ?: metrics.heightPixels).coerceAtLeast(1)
        val stride = width.toLong() * 4L
        val frameSize = stride * height
        val body = org.json.JSONObject()
            .put("device", "Android display")
            .put("width", width)
            .put("height", height)
            .put("width_virtual", width)
            .put("height_virtual", height)
            .put("bits_per_pixel", 32)
            .put("format", "xrgb8888")
            .put("stride", stride)
            .put("frame_size", frameSize)
            .put("memory_size", frameSize.coerceAtMost(0xffffffffL))
            .put("y_pan_step", 0)
            .put("double_buffer", false)
            .put("red_offset", 16)
            .put("red_length", 8)
            .put("green_offset", 8)
            .put("green_length", 8)
            .put("blue_offset", 0)
            .put("blue_length", 8)
            .put("alpha_offset", 0)
            .put("alpha_length", 0)
            .toString()
            .toByteArray(HTML_CHARSET)
        writeJsonResponse(socket, 200, "OK", body)
    }

    private fun activeActivityDisplaySize(): Point? {
        val activity = ClockActivity.me?.takeIf { it.isActivityStarted }
            ?: DeskActivity.me?.takeIf { it.isActivityStarted }
            ?: return null
        val content = activity.findViewById<android.view.View>(android.R.id.content)
            ?: return null
        if (content.width <= 0 || content.height <= 0) return null
        val decorView = activity.window.decorView
        val contentDrawsBehindStatusBar =
            (decorView.systemUiVisibility and
                (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)) != 0
        val statusBars = WindowInsetsCompat.Type.statusBars()
        val statusBarInset = if (contentDrawsBehindStatusBar) {
            0
        } else {
            ViewCompat.getRootWindowInsets(decorView)?.getInsets(statusBars)?.top ?: 0
        }
        return Point(content.width, content.height + statusBarInset)
    }

    private fun streamFramebuffer(socket: Socket, request: HttpRequest) {
        val boundary = parseMultipartBoundary(request.contentType)
        if (boundary == null) {
            writeJsonResponse(
                socket,
                400,
                "Bad Request",
                """{"error":"Content-Type must be multipart/x-mixed-replace with a boundary"}"""
                    .toByteArray(HTML_CHARSET)
            )
            return
        }
        if (!framebufferStreamActive.compareAndSet(false, true)) {
            writeEmptyResponse(socket, 503, "Service Unavailable")
            return
        }
        activeFramebufferSocket = socket

        var frameCount = 0L
        var jpegBytes = 0L
        var streamStarted = false
        try {
            if (request.expectContinue) {
                socket.getOutputStream().write(
                    "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(HTTP_CHARSET)
                )
                socket.getOutputStream().flush()
            }
            socket.soTimeout = 0
            framebufferStreamState(true)
            streamStarted = true
            val boundaryLine = "--$boundary"
            while (running.get() && !socket.isClosed) {
                val line = readHttpLine(request.body)
                    ?: throw java.io.EOFException("missing MJPEG boundary")
                if (line == "$boundaryLine--") break
                if (line != boundaryLine) {
                    throw IllegalArgumentException("invalid MJPEG boundary")
                }

                var contentLength: Int? = null
                var partContentType: String? = null
                var headerCount = 0
                while (true) {
                    val header = readHttpLine(request.body)
                        ?: throw java.io.EOFException("truncated MJPEG part headers")
                    if (header.isEmpty()) break
                    headerCount++
                    if (headerCount > 32) {
                        throw IllegalArgumentException("too many MJPEG part headers")
                    }
                    val name = header.substringBefore(':').trim()
                    val value = header.substringAfter(':', "").trim()
                    when {
                        name.equals("Content-Length", ignoreCase = true) ->
                            contentLength = value.toIntOrNull()
                        name.equals("Content-Type", ignoreCase = true) ->
                            partContentType = value
                    }
                }
                if (partContentType != null &&
                    !partContentType.startsWith("image/jpeg", ignoreCase = true)
                ) {
                    throw IllegalArgumentException("MJPEG part is not image/jpeg")
                }
                val frameLength = contentLength
                    ?: throw IllegalArgumentException("MJPEG part is missing Content-Length")
                if (frameLength <= 0 || frameLength > MAX_JPEG_FRAME_BYTES) {
                    throw IllegalArgumentException("invalid JPEG frame size: $frameLength")
                }

                val jpeg = FramebufferJpegBufferPool.acquire(frameLength)
                var frame: ByteArray? = jpeg
                try {
                    var offset = 0
                    while (offset < frameLength) {
                        val count = request.body.read(jpeg, offset, frameLength - offset)
                        if (count < 0) throw java.io.EOFException("truncated JPEG frame")
                        if (count == 0) continue
                        offset += count
                    }
                    if (readHttpLine(request.body) != "") {
                        throw IllegalArgumentException("missing MJPEG part terminator")
                    }
                    framebufferFrame(jpeg, frameLength)
                    frame = null
                } finally {
                    frame?.let(FramebufferJpegBufferPool::release)
                }
                frameCount++
                jpegBytes += frameLength
            }
            if (frameCount == 0L) {
                throw IllegalArgumentException("empty MJPEG stream")
            }
            val response = """{"ok":true,"frames":$frameCount,"jpeg_bytes":$jpegBytes}"""
                .toByteArray(HTML_CHARSET)
            writeJsonResponse(socket, 200, "OK", response)
            log("桌面画面串流已结束：$frameCount 帧，$jpegBytes 字节")
        } catch (e: Exception) {
            val status = if (frameCount == 0L) 400 else 502
            val reason = if (status == 400) "Bad Request" else "Bad Gateway"
            log("桌面画面串流失败：${e.javaClass.simpleName}: ${e.message ?: "无错误信息"}")
            val response = """{"error":"MJPEG stream failed","frames":$frameCount,"jpeg_bytes":$jpegBytes}"""
                .toByteArray(HTML_CHARSET)
            writeJsonResponse(socket, status, reason, response)
        } finally {
            if (streamStarted) framebufferStreamState(false)
            if (activeFramebufferSocket === socket) activeFramebufferSocket = null
            framebufferStreamActive.set(false)
        }
    }

    private fun parseMultipartBoundary(contentType: String?): String? {
        if (contentType == null) return null
        val parameters = contentType.split(';')
        val mediaType = parameters.firstOrNull()?.trim()
        if (!mediaType.equals("multipart/x-mixed-replace", ignoreCase = true) &&
            !mediaType.equals("multipart/mixed", ignoreCase = true)
        ) {
            return null
        }
        val boundary = parameters.drop(1).firstNotNullOfOrNull { parameter ->
            if (parameter.substringBefore('=').trim().equals("boundary", ignoreCase = true)) {
                parameter.substringAfter('=', "").trim().removeSurrounding("\"")
            } else {
                null
            }
        }
        return boundary?.takeIf {
            it.isNotEmpty() && it.length <= 200 && '\r' !in it && '\n' !in it
        }
    }

    private fun writeJsonResponse(socket: Socket, status: Int, reason: String, body: ByteArray) {
        val header = "HTTP/1.0 $status $reason\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-cache, no-store\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        val output = socket.getOutputStream()
        output.write(header.toByteArray(HTTP_CHARSET))
        output.write(body)
        output.flush()
    }

    private class ChunkedInputStream(private val input: InputStream) : InputStream() {
        private var remaining = 0
        private var finished = false

        override fun read(): Int {
            if (!ensureChunk()) return -1
            val value = input.read()
            if (value < 0) throw java.io.EOFException("truncated chunk")
            remaining--
            if (remaining == 0) consumeCrlf()
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (!ensureChunk()) return -1
            val count = input.read(buffer, offset, minOf(length, remaining))
            if (count < 0) throw java.io.EOFException("truncated chunk")
            if (count == 0) return 0
            remaining -= count
            if (remaining == 0) consumeCrlf()
            return count
        }

        private fun ensureChunk(): Boolean {
            if (finished) return false
            if (remaining > 0) return true
            val line = readLine() ?: throw java.io.EOFException("missing chunk header")
            val sizeText = line.substringBefore(';').trim()
            remaining = sizeText.toIntOrNull(16)
                ?: throw IllegalArgumentException("invalid chunk size: $line")
            if (remaining == 0) {
                while (true) {
                    val trailer = readLine() ?: throw java.io.EOFException("missing chunk trailer")
                    if (trailer.isEmpty()) break
                }
                finished = true
                return false
            }
            return true
        }

        private fun consumeCrlf() {
            if (input.read() != '\r'.code || input.read() != '\n'.code) {
                throw IllegalArgumentException("invalid chunk terminator")
            }
        }

        private fun readLine(): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0) return null
                if (value == '\n'.code) return bytes.toString(HTTP_CHARSET.name()).trimEnd('\r')
                if (bytes.size() >= 8192) throw IllegalArgumentException("chunk line too long")
                bytes.write(value)
            }
        }
    }

    private fun readHttpLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString(HTTP_CHARSET.name())
            if (value == '\n'.code) return bytes.toString(HTTP_CHARSET.name()).trimEnd('\r')
            if (bytes.size() >= 8192) return null
            bytes.write(value)
        }
    }

    private fun parseRoute(path: String): CameraStreamKey? {
        val prefix = if (password.isEmpty()) "" else "/$password"

        val avcPrefix = "$prefix/avc/"
        if (path.startsWith(avcPrefix)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
            val indices = parseCameraIndices(path.substring(avcPrefix.length)) ?: return null
            return CameraStreamKey(CameraStreamFormat.AVC, indices.first, indices.second)
        }

        val mjpegPrefix = "$prefix/"
        if (!path.startsWith(mjpegPrefix)) return null
        val indices = parseCameraIndices(path.substring(mjpegPrefix.length)) ?: return null
        return CameraStreamKey(CameraStreamFormat.MJPEG, indices.first, indices.second)
    }

    private fun isAvcCapabilityRoute(path: String): Boolean {
        val prefix = if (password.isEmpty()) "" else "/$password"
        return path == "$prefix/avc/capability"
    }

    private fun isBrightnessRoute(path: String): Boolean {
        val prefix = if (password.isEmpty()) "" else "/$password"
        return path == "$prefix/brightness"
    }

    private fun isAacRoute(path: String): Boolean {
        val prefix = if (password.isEmpty()) "" else "/$password"
        return path == "$prefix/aac"
    }

    private fun print2LogView(s: String) {
        MeService.me?.print2LogView("CameraHttp: $s")
    }

    private fun releasePcmTrack(track: AudioTrack?) {
        if (track == null) return

        // AudioTrack 初始化失败时 state == STATE_UNINITIALIZED，
        // 此时不能调用 stop()/flush()，否则会抛：
        // "stop() called on uninitialized AudioTrack"。
        if (track.state == AudioTrack.STATE_INITIALIZED) {
            try {
                track.stop()
            } catch (_: Exception) {
            }
            try {
                // 丢掉尚未播放的数据，断开连接时不要留下播放尾巴。
                track.flush()
            } catch (_: Exception) {
            }
        }

        try {
            track.release()
        } catch (_: Exception) {
        }
    }

    private fun streamPcm(socket: Socket, request: HttpRequest) {
        var track: AudioTrack? = null
        try {
            val rate = request.uri.getQueryParameter("rate")?.toIntOrNull() ?: 44100
            val channels = request.uri.getQueryParameter("channels")?.toIntOrNull() ?: 2
            if (rate !in 8000..192000 || channels !in 1..2) {
                print2LogView("电脑音箱参数不支持：rate=$rate channels=$channels")
                writeEmptyResponse(socket, 400, "Unsupported")
                return
            }

            val channelMask = if (channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }
            val minBufferFallback = AudioTrack.getMinBufferSize(
                rate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val minBuffer = minBufferFallback / 2
            if (minBuffer <= 0) {
                print2LogView("电脑音箱无法获取缓冲区：rate=$rate channels=$channels result=$minBuffer")
                writeEmptyResponse(socket, 415, "Unsupported Media Type")
                return
            } else {
                print2LogView("电脑音箱连接 缓冲：$minBuffer")
            }

            track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuffer)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                val legacyTrack = try {
                    AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        rate,
                        channelMask,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuffer,
                        AudioTrack.MODE_STREAM
                    )
                } catch (e: IllegalArgumentException) {
                    print2LogView("小缓冲区不可用：$minBuffer，回退到 $minBufferFallback：${e.message}")
                    AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        rate,
                        channelMask,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBufferFallback,
                        AudioTrack.MODE_STREAM
                    )
                }
                legacyTrack
            }

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                print2LogView("电脑音箱初始化失败：rate=$rate channels=$channels state=${track.state}")
                releasePcmTrack(track)
                track = null
                writeEmptyResponse(socket, 415, "Unsupported Media Type")
                return
            }
            socket.getOutputStream().write(
                "HTTP/1.0 200 OK\r\nConnection: close\r\n\r\n".toByteArray(HTTP_CHARSET)
            )
            socket.getOutputStream().flush()
            track.play()
            val frameBytes = channels * 2
            val buffer = ByteArray(minBuffer.coerceAtLeast(2048) + frameBytes)
            var pending = 0
            while (true) {
                val count = request.body.read(buffer, pending, buffer.size - pending)
                if (count < 0) break
                if (count == 0) continue
                val total = pending + count
                val writable = total - (total % frameBytes)
                var offset = 0
                while (offset < writable) {
                    val length = writable - offset
                    val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        track.write(buffer, offset, length, AudioTrack.WRITE_BLOCKING)
                    } else {
                        @Suppress("DEPRECATION")
                        track.write(buffer, offset, length)
                    }
                    if (written < 0) {
                        throw IllegalStateException("AudioTrack.write failed: $written")
                    }
                    if (written == 0) {
                        Thread.sleep(1L)
                    } else {
                        offset += written
                    }
                }
                pending = total - writable
                if (pending > 0) {
                    System.arraycopy(buffer, writable, buffer, 0, pending)
                }
            }
        } catch (e: Exception) {
            print2LogView("电脑音箱播放失败：${e.javaClass.simpleName}: ${e.message ?: "无错误信息"}")
            throw e
        } finally {
            // 每个 Socket 都只回收自己的 AudioTrack。
            // 因此多个设备可以同时播放；旧连接即使晚于新连接退出，
            // 也只会释放旧连接自己的 Track，不会碰新连接。
            releasePcmTrack(track)
            track = null
        }
    }

    private fun canStreamMicrophone(): Boolean {
        if (!AacAudioPipeline.isPlatformSupported()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun writeBrightness(socket: Socket) {
        val body = brightness.level.toString().toByteArray(HTTP_CHARSET)
        val header = "HTTP/1.0 200 OK\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-cache, no-store\r\n" +
            "Content-Type: text/plain; charset=us-ascii\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        val output = socket.getOutputStream()
        output.write(header.toByteArray(HTTP_CHARSET))
        output.write(body)
        output.flush()
    }

    private fun writeAvcCapability(socket: Socket) {
        val supported = isAvcEncoderUsable()
        val body = "{\"supported\":$supported}"
            .toByteArray(HTTP_CHARSET)
        val header = "HTTP/1.0 200 OK\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-cache, no-store\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        val output = socket.getOutputStream()
        output.write(header.toByteArray(HTTP_CHARSET))
        output.write(body)
        output.flush()
    }

    private fun isAvcEncoderUsable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false

        var encoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var started = false
        return try {
            val testEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = testEncoder
            val capabilities = testEncoder.codecInfo.getCapabilitiesForType(
                MediaFormat.MIMETYPE_VIDEO_AVC
            )
            if (!capabilities.colorFormats.contains(
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
            ) return false
            val videoCapabilities = capabilities.videoCapabilities ?: return false
            val testSize = listOf(
                Pair(640, 480),
                Pair(1280, 720),
                Pair(320, 240),
                Pair(1920, 1080),
                Pair(176, 144)
            ).firstOrNull { (width, height) ->
                try {
                    videoCapabilities.areSizeAndRateSupported(width, height, 15.0)
                } catch (_: Exception) {
                    false
                }
            } ?: return false
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                testSize.first,
                testSize.second
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 512_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 15)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            testEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = testEncoder.createInputSurface()
            testEncoder.start()
            started = true
            true
        } catch (_: Exception) {
            false
        } finally {
            if (started) {
                try {
                    encoder?.stop()
                } catch (_: Exception) {
                }
            }
            try {
                inputSurface?.release()
            } catch (_: Exception) {
            }
            try {
                encoder?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun parseCameraIndices(value: String): Pair<Int, Int?>? {
        val parts = value.split('/')
        if (parts.size !in 1..2) return null
        val cameraIndex = parseOneBasedIndex(parts[0]) ?: return null
        val resolutionIndex = if (parts.size == 2) {
            parseOneBasedIndex(parts[1]) ?: return null
        } else {
            null
        }
        return Pair(cameraIndex - 1, resolutionIndex?.minus(1))
    }

    private fun parseOneBasedIndex(value: String): Int? {
        if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
        val index = value.toIntOrNull() ?: return null
        return if (index > 0) index else null
    }

    private fun cameraExists(key: CameraStreamKey): Boolean {
        val count = try {
            when (key.format) {
                CameraStreamFormat.MJPEG -> Camera.getNumberOfCameras()
                CameraStreamFormat.AVC -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) 0
                    else AvcCameraPipeline.cameraCount(appContext)
                }
            }
        } catch (_: Exception) {
            0
        }
        return key.cameraIndex in 0 until count
    }

    private fun acquirePipeline(
        key: CameraStreamKey,
        socket: Socket
    ): CameraStreamPipeline? {
        synchronized(sessionChangeLock) {
            if (!running.get()) return null

            val current = synchronized(stateLock) { activePipeline }
            if (current != null) {
                val recorderToken = recordingVideoToken
                if (current.key == key || recorderToken?.videoPipeline === current) {
                    synchronized(stateLock) { clients[socket] = current }
                    return current
                }
            }

            closeActiveSessionLocked()
            brightness.onIpCameraStarting()
            if (!running.get()) return null
            val newPipeline = createPipeline(key)
            if (!newPipeline.start()) {
                newPipeline.stop()
                brightness.onIpCameraStopped()
                return null
            }
            if (!running.get()) {
                newPipeline.stop()
                return null
            }
            synchronized(stateLock) {
                activePipeline = newPipeline
                clients[socket] = newPipeline
            }
            acquireStreamLocks()
            return newPipeline
        }
    }

    private fun createPipeline(key: CameraStreamKey): CameraStreamPipeline {
        return when (key.format) {
            CameraStreamFormat.MJPEG -> {
                brightness.beginCameraWarmup()
                MjpegCameraPipeline(key, log, brightness::updateLuma)
            }
            CameraStreamFormat.AVC -> AvcCameraPipeline(appContext, key, log)
        }
    }

    /**
     * 录像专用会话获取：不依赖 Socket，不受 HTTP 观看者断开影响。
     * 启动后会抢占/复用当前视频管线；返回的 token 用于释放。
     */
    fun acquireRecordingSession(key: CameraStreamKey, withAudio: Boolean): CameraStreamToken? {
        val token = CameraStreamToken(null, null)
        var startedNewVideo = false
        synchronized(sessionChangeLock) {
            if (!running.get()) return null
            val current = synchronized(stateLock) { activePipeline }
            if (current != null && current.key == key) {
                token.videoPipeline = current
            } else {
                closeActiveSessionLocked()
                brightness.onIpCameraStarting()
                if (!running.get()) return null
                val newPipeline = createPipeline(key)
                if (!newPipeline.start()) {
                    newPipeline.stop()
                    brightness.onIpCameraStopped()
                    return null
                }
                if (!running.get()) {
                    newPipeline.stop()
                    brightness.onIpCameraStopped()
                    return null
                }
                synchronized(stateLock) {
                    activePipeline = newPipeline
                    token.videoPipeline = newPipeline
                }
                acquireStreamLocks()
                startedNewVideo = true
            }
            recordingVideoToken = token
        }

        if (withAudio) {
            val audioPipeline = acquireRecordingAudio()
            if (audioPipeline == null) {
                synchronized(sessionChangeLock) {
                    if (recordingVideoToken === token) recordingVideoToken = null
                    token.invalid = true
                    if (startedNewVideo) {
                        val pipeline = token.videoPipeline
                        if (pipeline != null && clients.isEmpty()) {
                            synchronized(stateLock) { activePipeline = null }
                            pipeline.stop()
                            releaseStreamLocks()
                            brightness.onIpCameraStopped()
                        }
                    }
                }
                return null
            }
            token.audioPipeline = audioPipeline
        }
        return token
    }

    private fun acquireRecordingAudio(): AacAudioPipeline? {
        synchronized(audioSessionChangeLock) {
            if (!running.get() || !canStreamMicrophone()) return null
            val existing = synchronized(stateLock) { activeAudioPipeline }
            if (existing != null) {
                recordingAudio = true
                return existing
            }
            val pipeline = AacAudioPipeline(log)
            if (!pipeline.start()) {
                pipeline.stop()
                return null
            }
            if (!running.get()) {
                pipeline.stop()
                return null
            }
            synchronized(stateLock) {
                activeAudioPipeline = pipeline
                recordingAudio = true
            }
            acquireStreamLocks()
            return pipeline
        }
    }

    fun releaseRecordingSession(token: CameraStreamToken) {
        synchronized(sessionChangeLock) {
            if (recordingVideoToken === token) {
                recordingVideoToken = null
            }
            token.invalid = true
            val pipeline = token.videoPipeline
            if (pipeline != null && activePipeline === pipeline && clients.isEmpty()) {
                synchronized(stateLock) { activePipeline = null }
                pipeline.stop()
                releaseStreamLocks()
                brightness.onIpCameraStopped()
            }
        }
        synchronized(audioSessionChangeLock) {
            if (recordingAudio && token.audioPipeline != null) {
                recordingAudio = false
                val pipeline = synchronized(stateLock) { activeAudioPipeline }
                if (pipeline != null && audioClients.isEmpty()) {
                    synchronized(stateLock) { activeAudioPipeline = null }
                    pipeline.stop()
                    releaseStreamLocks()
                }
            }
        }
    }

    /**
     * 录像运行中切换"是否录音"。
     */
    fun setRecordingAudioEnabled(token: CameraStreamToken, withAudio: Boolean) {
        if (withAudio && token.audioPipeline == null) {
            token.audioPipeline = acquireRecordingAudio()
        } else if (!withAudio && token.audioPipeline != null) {
            synchronized(audioSessionChangeLock) {
                if (recordingAudio) recordingAudio = false
                val pipeline = activeAudioPipeline
                token.audioPipeline = null
                if (pipeline != null && audioClients.isEmpty()) {
                    synchronized(stateLock) { activeAudioPipeline = null }
                    pipeline.stop()
                    releaseStreamLocks()
                }
            }
        }
    }

    private fun acquireAudioPipeline(socket: Socket): AacAudioPipeline? {
        synchronized(audioSessionChangeLock) {
            if (!running.get() || !canStreamMicrophone()) return null
            synchronized(stateLock) {
                activeAudioPipeline?.let { pipeline ->
                    audioClients.add(socket)
                    return pipeline
                }
            }

            val pipeline = AacAudioPipeline(log)
            if (!pipeline.start()) {
                pipeline.stop()
                return null
            }
            if (!running.get()) {
                pipeline.stop()
                return null
            }
            synchronized(stateLock) {
                activeAudioPipeline = pipeline
                audioClients.add(socket)
            }
            acquireStreamLocks()
            return pipeline
        }
    }

    private fun releaseAudioClient(socket: Socket, pipeline: AacAudioPipeline?) {
        if (pipeline == null) return
        synchronized(audioSessionChangeLock) {
            var shouldStop = false
            synchronized(stateLock) {
                audioClients.remove(socket)
                if (activeAudioPipeline === pipeline &&
                    audioClients.isEmpty() && !recordingAudio
                ) {
                    activeAudioPipeline = null
                    shouldStop = true
                }
            }
            if (shouldStop) {
                pipeline.stop()
                releaseStreamLocks()
            }
        }
    }

    private fun closeActiveAudioSession() {
        synchronized(audioSessionChangeLock) {
            val session = synchronized(stateLock) {
                val pipeline = activeAudioPipeline
                activeAudioPipeline = null
                val sockets = audioClients.toList()
                audioClients.clear()
                Pair(pipeline, sockets)
            }
            recordingVideoToken?.audioPipeline = null
            recordingAudio = false
            session.second.forEach { socket ->
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
            session.first?.let { pipeline ->
                pipeline.stop()
                releaseStreamLocks()
            }
        }
    }

    private fun releaseClient(socket: Socket, pipeline: CameraStreamPipeline?) {
        if (pipeline == null) return
        synchronized(sessionChangeLock) {
            var shouldStop = false
            synchronized(stateLock) {
                clients.remove(socket)
                val recorderKeeps = recordingVideoToken != null &&
                    recordingVideoToken?.videoPipeline === pipeline
                if (activePipeline === pipeline && clients.isEmpty() && !recorderKeeps) {
                    activePipeline = null
                    shouldStop = true
                }
            }
            if (shouldStop) {
                pipeline.stop()
                releaseStreamLocks()
                brightness.onIpCameraStopped()
            }
        }
    }

    private fun closeActiveSession() {
        synchronized(sessionChangeLock) {
            closeActiveSessionLocked()
        }
    }

    private fun closeActiveSessionLocked() {
        val session = synchronized(stateLock) {
            val pipeline = activePipeline
            activePipeline = null
            val sockets = clients.keys.toList()
            clients.clear()
            Pair(pipeline, sockets)
        }
        recordingVideoToken?.let { token ->
            token.invalid = true
            token.videoPipeline = null
            recordingVideoToken = null
        }
        session.second.forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        val pipeline = session.first
        if (pipeline != null) {
            pipeline.stop()
            brightness.onIpCameraStopped()
            releaseStreamLocks()
        }
    }

    private fun streamMjpeg(socket: Socket, pipeline: CameraStreamPipeline) {
        val output = socket.getOutputStream()
        output.write(
            ("HTTP/1.0 200 OK\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Pragma: no-cache\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n")
                .toByteArray(HTTP_CHARSET)
        )
        output.flush()

        var sequence = 0L
        while (running.get() && !socket.isClosed) {
            val packet = pipeline.awaitPacket(sequence) ?: break
            sequence = packet.sequence
            output.write("--frame\r\n".toByteArray(HTTP_CHARSET))
            output.write("Content-Type: image/jpeg\r\n".toByteArray(HTTP_CHARSET))
            output.write("Content-Length: ${packet.data.size}\r\n\r\n".toByteArray(HTTP_CHARSET))
            output.write(packet.data)
            output.write("\r\n".toByteArray(HTTP_CHARSET))
            output.flush()
        }
    }

    private fun streamAvc(
        socket: Socket,
        pipeline: CameraStreamPipeline,
        config: AvcStreamConfig
    ) {
        val muxer = FragmentedMp4Muxer(config)
        val output = socket.getOutputStream()
        output.write(
            ("HTTP/1.0 200 OK\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Pragma: no-cache\r\n" +
                "Content-Type: video/mp4\r\n" +
                "X-Video-Codec: ${config.codecString}\r\n\r\n")
                .toByteArray(HTTP_CHARSET)
        )
        output.write(muxer.initializationSegment())
        output.flush()

        pipeline.requestKeyFrame()
        var sequence = 0L
        var started = false
        while (running.get() && !socket.isClosed) {
            val packet = pipeline.awaitPacket(sequence) ?: break
            sequence = packet.sequence
            if (!started) {
                if (!packet.keyFrame) continue
                started = true
            }
            val fragment = muxer.mediaFragment(packet) ?: continue
            output.write(fragment)
            output.flush()
        }
    }

    private fun streamAac(socket: Socket, pipeline: AacAudioPipeline) {
        val config = pipeline.config
        val muxer = FragmentedAacMp4Muxer(config)
        val output = socket.getOutputStream()
        output.write(
            ("HTTP/1.0 200 OK\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Pragma: no-cache\r\n" +
                "Content-Type: audio/mp4\r\n" +
                "X-Audio-Codec: ${config.codecString}\r\n\r\n")
                .toByteArray(HTTP_CHARSET)
        )
        output.write(muxer.initializationSegment())
        output.flush()

        var sequence = 0L
        while (running.get() && !socket.isClosed) {
            val packet = pipeline.awaitPacket(sequence) ?: break
            sequence = packet.sequence
            output.write(muxer.mediaFragment(packet))
            output.flush()
        }
    }

    private fun handleRecordingRoute(
        socket: Socket,
        request: HttpRequest,
        path: String?
    ): Boolean {
        if (path == null) return false
        if (request.method != "GET") {
            if (path == "/recordings" ||
                path.startsWith("/recordings/") ||
                path.endsWith("/rec/list") ||
                path.contains("/rec/file/")
            ) {
                writeEmptyResponse(socket, 405, "Method Not Allowed")
                return true
            }
            return false
        }
        if (path == "/recordings") {
            writeRecordingsPage(socket)
            return true
        }
        val prefix = if (password.isEmpty()) "" else "/$password"
        if (path == "$prefix/rec/list") {
            writeRecordingList(socket)
            return true
        }
        val filePrefix = "$prefix/rec/file/"
        if (path.startsWith(filePrefix)) {
            val name = Uri.decode(path.substring(filePrefix.length))
            val asDownload = request.uri.getQueryParameter("download") == "1"
            writeRecordingFile(socket, name, request.rangeHeader, asDownload)
            return true
        }
        return false
    }

    private fun writeRecordingList(socket: Socket) {
        val access = recordingAccess
        if (access == null) {
            writeEmptyResponse(socket, 404, "Not Found")
            return
        }
        val array = org.json.JSONArray()
        access.listRecordings().forEach { info ->
            array.put(
                org.json.JSONObject()
                    .put("name", info.name)
                    .put("size", info.size)
                    .put("time", info.timestampMs)
                    .put("local", info.local)
            )
        }
        val body = array.toString().toByteArray(HTML_CHARSET)
        val header = "HTTP/1.0 200 OK\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-cache, no-store\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        val output = socket.getOutputStream()
        output.write(header.toByteArray(HTTP_CHARSET))
        output.write(body)
        output.flush()
    }

    private fun writeRecordingFile(
        socket: Socket,
        name: String,
        rangeHeader: String?,
        asDownload: Boolean
    ) {
        val access = recordingAccess
        if (access == null) {
            writeEmptyResponse(socket, 404, "Not Found")
            return
        }
        val handle = access.openForRead(name, rangeHeader)
        if (handle == null) {
            writeEmptyResponse(socket, 404, "Not Found")
            return
        }
        try {
            socket.soTimeout = 30_000
            if (handle.local != null) {
                streamLocalRecording(socket, handle.local, rangeHeader, name, asDownload)
            } else {
                streamRemoteRecording(socket, handle.connection!!, name, asDownload)
            }
        } finally {
            handle.close()
        }
    }

    private fun streamLocalRecording(
        socket: Socket,
        file: File,
        rangeHeader: String?,
        name: String,
        asDownload: Boolean
    ) {
        val length = file.length()
        if (length <= 0L) {
            writeEmptyResponse(socket, 404, "Not Found")
            return
        }
        val range = parseByteRange(rangeHeader, length)
        var raf: RandomAccessFile? = null
        try {
            raf = RandomAccessFile(file, "r")
            val output = socket.getOutputStream()
            val disposition = if (asDownload) "attachment" else "inline"
            if (range == null) {
                val header = "HTTP/1.0 200 OK\r\n" +
                    "Connection: close\r\n" +
                    "Content-Type: video/mp4\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Content-Disposition: $disposition; filename=\"$name\"\r\n" +
                    "Content-Length: $length\r\n\r\n"
                output.write(header.toByteArray(HTTP_CHARSET))
                copyStream(raf, output, 0L, length)
            } else {
                val start = range.first
                val end = range.second
                val contentLength = end - start + 1
                val header = "HTTP/1.0 206 Partial Content\r\n" +
                    "Connection: close\r\n" +
                    "Content-Type: video/mp4\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Content-Disposition: $disposition; filename=\"$name\"\r\n" +
                    "Content-Range: bytes $start-$end/${length - 1}\r\n" +
                    "Content-Length: $contentLength\r\n\r\n"
                output.write(header.toByteArray(HTTP_CHARSET))
                copyStream(raf, output, start, contentLength)
            }
            output.flush()
        } finally {
            try {
                raf?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun copyStream(
        raf: RandomAccessFile,
        output: OutputStream,
        start: Long,
        count: Long
    ) {
        raf.seek(start)
        val buffer = ByteArray(32 * 1024)
        var remaining = count
        while (remaining > 0L) {
            val toRead = if (remaining > buffer.size) buffer.size else remaining.toInt()
            val read = raf.read(buffer, 0, toRead)
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun streamRemoteRecording(
        socket: Socket,
        connection: java.net.HttpURLConnection,
        name: String,
        asDownload: Boolean
    ) {
        val statusCode = connection.responseCode
        val input = try {
            connection.inputStream
        } catch (_: Exception) {
            connection.errorStream
        }
        if (input == null) {
            writeEmptyResponse(socket, 502, "Bad Gateway")
            return
        }
        val reason = if (statusCode == 206) "Partial Content" else "OK"
        val contentLength = connection.getHeaderField("Content-Length") ?: "-1"
        val contentRange = connection.getHeaderField("Content-Range")
        val disposition = if (asDownload) "attachment" else "inline"
        var header = "HTTP/1.0 $statusCode $reason\r\n" +
            "Connection: close\r\n" +
            "Content-Type: video/mp4\r\n" +
            "Accept-Ranges: bytes\r\n" +
            "Content-Disposition: $disposition; filename=\"$name\"\r\n" +
            "Content-Length: $contentLength\r\n"
        if (contentRange != null) {
            header += "Content-Range: $contentRange\r\n"
        }
        val output = socket.getOutputStream()
        output.write((header + "\r\n").toByteArray(HTTP_CHARSET))
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        output.flush()
        try {
            input.close()
        } catch (_: Exception) {
        }
    }

    private fun parseByteRange(rangeHeader: String?, length: Long): Pair<Long, Long>? {
        if (rangeHeader == null) return null
        val value = rangeHeader.trim()
        if (!value.startsWith("bytes=")) return null
        val spec = value.substring("bytes=".length).substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()
        val start: Long
        var end: Long
        if (startText.isEmpty()) {
            val suffix = endText.toLongOrNull() ?: return null
            if (suffix <= 0L) return null
            start = (length - suffix).coerceAtLeast(0L)
            end = length - 1
        } else {
            start = startText.toLongOrNull() ?: return null
            end = if (endText.isEmpty()) length - 1 else (endText.toLongOrNull() ?: return null)
            if (start < 0L || start >= length) return null
            end = end.coerceAtMost(length - 1)
        }
        if (end < start) return null
        return start to end
    }

    private fun writeRecordingsPage(socket: Socket) {
        val body = RECORDINGS_PAGE.toByteArray(HTML_CHARSET)
        val header = "HTTP/1.0 200 OK\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-cache, no-store\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        val output = socket.getOutputStream()
        output.write(header.toByteArray(HTTP_CHARSET))
        output.write(body)
        output.flush()
    }

    private fun writePlayerPage(socket: Socket) {
        val page = if (meBgCamCtrl.enabled) {
            PLAYER_PAGE.replace("</body>", "${meBgCamCtrl.playerUi()}\n</body>")
        } else {
            PLAYER_PAGE
        }
        val body = page.toByteArray(HTML_CHARSET)
        val header = "HTTP/1.0 200 OK\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-cache, no-store\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        val output = socket.getOutputStream()
        output.write(header.toByteArray(HTTP_CHARSET))
        output.write(body)
        output.flush()
    }

    private fun writeEmptyResponse(socket: Socket, status: Int, reason: String) {
        val response = "HTTP/1.0 $status $reason\r\n" +
            "Connection: close\r\n" +
            "Content-Length: 0\r\n\r\n"
        socket.getOutputStream().write(response.toByteArray(HTTP_CHARSET))
        socket.getOutputStream().flush()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireStreamLocks() {
        synchronized(streamLockState) {
            streamLockUsers++
            if (streamLockUsers > 1) return
            if (wakeLock == null) {
                try {
                    wakeLock = (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK,
                            "workDayAlarmClock:CameraStream"
                        ).apply {
                            setReferenceCounted(false)
                            acquire()
                        }
                } catch (e: Exception) {
                    log("摄像头CPU唤醒锁获取失败：${e.message}")
                }
            }
            if (wifiLock == null) {
                try {
                    wifiLock = (appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                        .createWifiLock(
                            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                            "workDayAlarmClock:CameraStream"
                        ).apply {
                            setReferenceCounted(false)
                            acquire()
                        }
                } catch (e: Exception) {
                    log("摄像头Wi-Fi锁获取失败：${e.message}")
                }
            }
        }
    }

    private fun releaseStreamLocks() {
        synchronized(streamLockState) {
            if (streamLockUsers == 0) return
            streamLockUsers--
            if (streamLockUsers > 0) return
            try {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            } catch (_: Exception) {
            }
            wakeLock = null
            try {
                if (wifiLock?.isHeld == true) wifiLock?.release()
            } catch (_: Exception) {
            }
            wifiLock = null
        }
    }

    private val PLAYER_PAGE = """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>咩IP摄像头</title>
<style>
*{box-sizing:border-box}body{font-family:sans-serif;max-width:1100px;margin:0 auto;padding:16px;background:#000;color:#eee}
#controls{display:flex;flex-wrap:wrap;gap:8px;align-items:end}.field{display:flex;flex:1 1 180px;min-width:0;flex-direction:column;gap:4px;font-size:14px;color:#bbb}
input,button{width:100%;min-height:42px;font:inherit;font-size:16px;padding:8px 10px;border:1px solid #555;border-radius:4px}input{background:#222;color:#eee}
button{flex:0 1 110px;cursor:pointer;background:#333;color:#eee}button[type=submit]{background:#1769aa;border-color:#278bd2}
#modes{display:flex;gap:14px;align-items:center;min-height:42px;padding:0 8px}.mode{display:flex;gap:7px;align-items:center;color:#eee;font-size:16px}.mode input{width:20px;min-height:20px;margin:0;padding:0}
#videoFrame{width:100%;max-width:100vw;max-height:100vh;aspect-ratio:16/9;margin:16px auto 0;display:flex;align-items:center;justify-content:center;overflow:hidden;background:#000}
video,#mjpeg{display:block;width:100%;height:100%;object-fit:contain;transform-origin:center center}audio{display:block;width:100%;margin-top:12px}
.status{color:#aaa;margin-top:8px;min-height:1.4em}[hidden]{display:none!important}
@media (max-width:520px){body{padding:10px}#controls{display:grid;grid-template-columns:1fr 1fr;gap:8px}.field,#modes{grid-column:span 2}button{flex:auto;width:auto}#videoFrame{margin-top:10px}}
</style>
</head>
<body>
<form id="controls">
<label class="field">密码 <input id="password" type="text" autocomplete="off"></label>
<label class="field">摄像头 <input id="camera" type="number" min="1" value="1"></label>
<label class="field">分辨率档位 <input id="resolution" type="number" min="1" placeholder="自动"></label>
<div id="modes">
<label class="mode"><input id="playVideo" type="checkbox" checked>视频</label>
<label class="mode"><input id="playAudio" type="checkbox" checked>声音</label>
</div>
<button id="playStop" type="submit">播放</button>
<button id="rotate" type="button" aria-label="旋转画面">旋转 0°</button>
<a href="/recordings" style="flex:0 1 110px;min-height:42px;display:flex;align-items:center;justify-content:center;border:1px solid #278bd2;border-radius:4px;background:#1769aa;color:#eee;text-decoration:none;font-size:16px">录像</a>
</form>
<div id="videoFrame"><video id="video" controls autoplay muted playsinline></video></div>
<div id="videoStatus" class="status"></div>
<audio id="audio" controls autoplay></audio>
<div id="audioStatus" class="status"></div>
<script>
const video=document.getElementById('video'),audio=document.getElementById('audio');
const videoFrame=document.getElementById('videoFrame'),form=document.getElementById('controls');
const playStopButton=document.getElementById('playStop'),rotateButton=document.getElementById('rotate');
const videoStatus=document.getElementById('videoStatus'),audioStatus=document.getElementById('audioStatus');
const playVideo=document.getElementById('playVideo'),playAudio=document.getElementById('playAudio');
let runId=0,rotation=0,playbackActive=false,mjpegImage=null;
let videoAbort=null,videoRetry=null,videoUrl=null,audioAbort=null,audioRetry=null,audioUrl=null;
function setVideoStatus(value){videoStatus.textContent=value;}
function setAudioStatus(value){audioStatus.textContent=value;}
function setPlaybackState(active){playbackActive=active;playStopButton.textContent=active?'停止':'播放';}
function waitEvent(target,event){return new Promise(resolve=>target.addEventListener(event,resolve,{once:true}));}
async function appendChunk(media,sourceBuffer,data){
  if(!data||!data.byteLength)return;
  while(sourceBuffer.updating)await waitEvent(sourceBuffer,'updateend');
  sourceBuffer.appendBuffer(data);
  await waitEvent(sourceBuffer,'updateend');
  if(media.buffered.length&&media.currentTime>10&&!sourceBuffer.updating){
    const removeEnd=media.currentTime-10;
    if(removeEnd>media.buffered.start(0)){sourceBuffer.remove(0,removeEnd);await waitEvent(sourceBuffer,'updateend');}
  }
}
function clearVideo(){
  if(videoRetry){clearTimeout(videoRetry);videoRetry=null;}
  if(videoAbort){videoAbort.abort();videoAbort=null;}
  if(mjpegImage){mjpegImage.remove();mjpegImage=null;}
  video.onerror=null;video.removeAttribute('src');video.load();video.style.display='block';
  if(videoUrl){URL.revokeObjectURL(videoUrl);videoUrl=null;}
}
function clearAudio(){
  if(audioRetry){clearTimeout(audioRetry);audioRetry=null;}
  if(audioAbort){audioAbort.abort();audioAbort=null;}
  audio.onerror=null;audio.removeAttribute('src');audio.load();
  if(audioUrl){URL.revokeObjectURL(audioUrl);audioUrl=null;}
}
function stopPlayback(showStatus){
  runId++;setPlaybackState(false);clearVideo();clearAudio();
  if(showStatus){setVideoStatus(playVideo.checked?'已停止':'');setAudioStatus(playAudio.checked?'已停止':'');}
}
function updateVideoShape(){
  const width=video.videoWidth||16,height=video.videoHeight||9,rotated=rotation%180!==0;
  videoFrame.style.aspectRatio=(rotated?height/width:width/height)+' / 1';
  video.style.width=rotated?(width/height*100)+'%':'100%';
  video.style.height=rotated?(height/width*100)+'%':'100%';
  video.style.transform='rotate('+rotation+'deg)';rotateButton.textContent='旋转 '+rotation+'°';
}
function showMjpegFallback(id,password,camera,resolution){
  if(id!==runId)return;clearVideo();video.style.display='none';
  const prefix=password?encodeURIComponent(password)+'/':'',resolutionPath=resolution===null?'':'/'+encodeURIComponent(resolution);
  mjpegImage=document.createElement('img');mjpegImage.id='mjpeg';mjpegImage.alt='MJPEG摄像头画面';
  mjpegImage.src='/'+prefix+encodeURIComponent(camera)+resolutionPath;
  mjpegImage.onload=()=>setVideoStatus('正在使用MJPEG播放摄像头 '+camera+(resolution===null?' 自动':' 档位'+resolution));
  mjpegImage.onerror=()=>setVideoStatus('MJPEG连接失败');videoFrame.appendChild(mjpegImage);
  setVideoStatus('正在切换到MJPEG ...');
}
async function getAvcCapability(password){
  const prefix=password?encodeURIComponent(password)+'/':'';
  try{
    const response=await fetch('/'+prefix+'avc/capability',{cache:'no-store'});
    if(response.status===404)return 'password-error';if(!response.ok)return 'unsupported';
    const result=await response.json();return result&&result.supported===true?'supported':'unsupported';
  }catch(error){return 'unsupported';}
}
async function connectVideo(id,password,camera,resolution){
  if(id!==runId)return;
  const capability=await getAvcCapability(password);if(id!==runId)return;
  if(capability==='password-error'){setVideoStatus('密码错误');return;}
  if(capability!=='supported'){showMjpegFallback(id,password,camera,resolution);return;}
  const prefix=password?encodeURIComponent(password)+'/':'',resolutionPath=resolution===null?'':'/'+encodeURIComponent(resolution);
  const path='/'+prefix+'avc/'+encodeURIComponent(camera)+resolutionPath;
  if(!window.MediaSource){
    video.onerror=()=>showMjpegFallback(id,password,camera,resolution);video.src=path;video.load();
    setVideoStatus('正在播放摄像头 '+camera+' ...');video.play().catch(()=>{});return;
  }
  clearVideo();const mediaSource=new MediaSource();videoUrl=URL.createObjectURL(mediaSource);video.src=videoUrl;video.play().catch(()=>{});
  try{
    await waitEvent(mediaSource,'sourceopen');if(id!==runId)return;
    videoAbort=new AbortController();const response=await fetch(path,{cache:'no-store',signal:videoAbort.signal});
    if(!response.ok){const error=new Error('HTTP '+response.status);error.status=response.status;throw error;}
    const codec=response.headers.get('X-Video-Codec'),mime='video/mp4; codecs="'+codec+'"';
    if(!codec||!MediaSource.isTypeSupported(mime))throw new Error('浏览器不支持视频编码');
    const sourceBuffer=mediaSource.addSourceBuffer(mime),reader=response.body.getReader();
    setVideoStatus('正在播放摄像头 '+camera+(resolution===null?' 自动':' 档位'+resolution)+' ...');
    while(id===runId){const item=await reader.read();if(item.done)throw new Error('视频连接已结束');await appendChunk(video,sourceBuffer,item.value);if(video.paused)video.play().catch(()=>{});}
  }catch(error){
    if(id!==runId||error.name==='AbortError')return;
    if(error.status===404||error.status===503||String(error.message||'').indexOf('不支持视频编码')>=0){showMjpegFallback(id,password,camera,resolution);return;}
    setVideoStatus('视频连接失败，2秒后重试：'+error.message);videoRetry=setTimeout(()=>connectVideo(id,password,camera,resolution),2000);
  }
}
async function connectAudio(id,password){
  if(id!==runId)return;
  const prefix=password?encodeURIComponent(password)+'/':'',path='/'+prefix+'aac';
  if(!window.MediaSource){audio.src=path;audio.load();audio.play().catch(()=>{});setAudioStatus('正在连接麦克风 ...');return;}
  clearAudio();const mediaSource=new MediaSource();audioUrl=URL.createObjectURL(mediaSource);audio.src=audioUrl;audio.play().catch(()=>{});
  try{
    await waitEvent(mediaSource,'sourceopen');if(id!==runId)return;
    audioAbort=new AbortController();const response=await fetch(path,{cache:'no-store',signal:audioAbort.signal});
    if(response.status===204){clearAudio();setAudioStatus('麦克风未授权，当前无声音');return;}
    if(!response.ok){const error=new Error('HTTP '+response.status);error.status=response.status;throw error;}
    const codec=response.headers.get('X-Audio-Codec')||'mp4a.40.2',mime='audio/mp4; codecs="'+codec+'"';
    if(!MediaSource.isTypeSupported(mime))throw new Error('浏览器不支持 '+mime);
    const sourceBuffer=mediaSource.addSourceBuffer(mime),reader=response.body.getReader();setAudioStatus('正在播放麦克风声音 ...');
    while(id===runId){const item=await reader.read();if(item.done)throw new Error('音频连接已结束');await appendChunk(audio,sourceBuffer,item.value);if(audio.paused)audio.play().catch(()=>{});}
  }catch(error){
    if(id!==runId||error.name==='AbortError')return;
    if(error.status===404){setAudioStatus('密码错误');return;}
    if(error.status===503){setAudioStatus('麦克风暂时不可用');return;}
    setAudioStatus('音频连接失败，2秒后重试：'+error.message);audioRetry=setTimeout(()=>connectAudio(id,password),2000);
  }
}
form.addEventListener('submit',event=>{
  event.preventDefault();if(playbackActive){stopPlayback(true);return;}
  const password=document.getElementById('password').value.trim().replace(/^\/+|\/+$/g,'');
  const camera=parseInt(document.getElementById('camera').value,10),resolutionText=document.getElementById('resolution').value.trim();
  const resolution=resolutionText===''?null:Number(resolutionText),withVideo=playVideo.checked,withAudio=playAudio.checked;
  if(!withVideo&&!withAudio){setVideoStatus('请至少选择视频或声音');setAudioStatus('');return;}
  if(withVideo&&(!Number.isInteger(camera)||camera<1)){setVideoStatus('摄像头编号无效');return;}
  if(withVideo&&resolution!==null&&(!Number.isInteger(resolution)||resolution<1)){setVideoStatus('分辨率档位无效');return;}
  try{
    localStorage.setItem('cameraPassword',password);localStorage.setItem('cameraNumber',String(camera));
    localStorage.setItem('cameraResolution',resolution===null?'':String(resolution));
    localStorage.setItem('cameraPlayVideo',String(withVideo));localStorage.setItem('cameraPlayAudio',String(withAudio));
  }catch(error){}
  videoFrame.hidden=!withVideo;videoStatus.hidden=!withVideo;rotateButton.disabled=!withVideo;
  audio.hidden=!withAudio;audioStatus.hidden=!withAudio;setVideoStatus('');setAudioStatus('');setPlaybackState(true);
  const id=runId;if(withVideo)connectVideo(id,password,camera,resolution);if(withAudio)connectAudio(id,password);
});
rotateButton.addEventListener('click',()=>{rotation=(rotation+90)%360;updateVideoShape();});video.addEventListener('loadedmetadata',updateVideoShape);
try{
  const savedPassword=localStorage.getItem('cameraPassword'),savedCamera=localStorage.getItem('cameraNumber'),savedResolution=localStorage.getItem('cameraResolution');
  const savedVideo=localStorage.getItem('cameraPlayVideo'),savedAudio=localStorage.getItem('cameraPlayAudio');
  if(savedPassword!==null)document.getElementById('password').value=savedPassword;
  if(savedCamera!==null&&/^[1-9][0-9]*$/.test(savedCamera))document.getElementById('camera').value=savedCamera;
  if(savedResolution!==null&&/^[1-9][0-9]*$/.test(savedResolution))document.getElementById('resolution').value=savedResolution;
  if(savedVideo!==null)playVideo.checked=savedVideo==='true';if(savedAudio!==null)playAudio.checked=savedAudio==='true';
}catch(error){}
updateVideoShape();
</script>
</body>
</html>
"""

    private val RECORDINGS_PAGE = """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>咩监控录像</title>
<style>
*{box-sizing:border-box}body{font-family:sans-serif;margin:0 auto;max-width:760px;padding:12px;background:#0d1117;color:#eee}
h1{font-size:18px;margin:4px 0 12px}
.bar{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
.bar input{flex:1 1 140px;min-width:0;min-height:42px;font-size:16px;padding:8px 10px;border:1px solid #555;border-radius:6px;background:#161b22;color:#eee}
button{min-height:40px;padding:8px 14px;font-size:15px;border:1px solid #278bd2;border-radius:6px;background:#1769aa;color:#fff;cursor:pointer}
button.ghost{background:#21262d;border-color:#444}
#status{margin:10px 0;color:#9aa4b0;min-height:1.2em;font-size:14px}
#player{width:100%;background:#000;border-radius:8px;margin-bottom:12px;max-height:60vh}
ul{list-style:none;margin:0;padding:0}
li{display:flex;align-items:center;gap:10px;padding:11px 8px;border-bottom:1px solid #21262d}
.meta{flex:1;min-width:0}.meta .t{font-size:15px;word-break:break-all}.meta .s{font-size:12px;color:#8b949e;margin-top:3px}
.badge{font-size:11px;padding:1px 6px;border-radius:10px;margin-left:6px}
.badge.local{background:#1769aa}.badge.nas{background:#5a3e00;color:#ffc76b}
.act{display:flex;gap:8px;flex-shrink:0}.act a,.act button{min-height:36px;padding:6px 12px;font-size:14px;text-decoration:none;border-radius:6px}
.act a{border:1px solid #444;color:#eee;background:#21262d}
[hidden]{display:none!important}
</style>
</head>
<body>
<h1>监控录像</h1>
<div class="bar">
<input id="password" type="text" autocomplete="off" placeholder="访问密码（无密码可留空）">
<button id="load">加载录像</button>
<button id="refresh" class="ghost">刷新</button>
<button id="live" class="ghost">看实时</button>
</div>
<div id="status"></div>
<video id="player" controls playsinline hidden></video>
<ul id="list"></ul>
<script>
var passwordInput=document.getElementById('password'),statusNode=document.getElementById('status');
var listNode=document.getElementById('list'),player=document.getElementById('player');
function apiPrefix(){var p=passwordInput.value.trim().replace(/^\/+|\/+$/g,'');return p?encodeURIComponent(p)+'/':'';}
function setStatus(text){statusNode.textContent=text||'';}
function formatSize(bytes){if(!bytes)return '0 B';var u=['B','KB','MB','GB'],i=Math.min(u.length-1,Math.floor(Math.log(bytes)/Math.log(1024)));return (bytes/Math.pow(1024,i)).toFixed(i?1:0)+' '+u[i];}
function formatTime(ms){try{return new Date(ms).toLocaleString();}catch(e){return String(ms);}}
function stopPlay(){player.pause();player.removeAttribute('src');player.load();player.hidden=true;}
function playItem(name){
  var url=apiPrefix()+'rec/file/'+encodeURIComponent(name);
  player.hidden=false;player.src=url;player.load();
  try{player.play();}catch(e){}
  player.scrollIntoView({behavior:'smooth',block:'center'});
}
function render(items){
  listNode.innerHTML='';
  if(!items.length){setStatus('暂无录像文件');return;}
  setStatus('共 '+items.length+' 段录像（按时间倒序）');
  items.forEach(function(item){
    var li=document.createElement('li');
    var meta=document.createElement('div');meta.className='meta';
    var t=document.createElement('div');t.className='t';t.textContent=formatTime(item.time);
    var badge=document.createElement('span');badge.className='badge '+(item.local?'local':'nas');
    badge.textContent=item.local?'本机':'NAS';
    t.appendChild(badge);
    var s=document.createElement('div');s.className='s';s.textContent=formatSize(item.size)+' · '+item.name;
    meta.appendChild(t);meta.appendChild(s);
    var act=document.createElement('div');act.className='act';
    var playBtn=document.createElement('button');playBtn.textContent='播放';
    playBtn.addEventListener('click',function(){playItem(item.name);});
    var dl=document.createElement('a');dl.textContent='下载';dl.href=apiPrefix()+'rec/file/'+encodeURIComponent(item.name)+'?download=1';
    dl.setAttribute('download',item.name);
    act.appendChild(playBtn);act.appendChild(dl);
    li.appendChild(meta);li.appendChild(act);
    listNode.appendChild(li);
  });
}
function loadList(){
  stopPlay();setStatus('加载中…');
  fetch(apiPrefix()+'rec/list',{cache:'no-store'}).then(function(resp){
    if(resp.status===404){setStatus('密码错误或录像服务不可用');render([]);return null;}
    if(!resp.ok){setStatus('加载失败：'+resp.status);return null;}
    return resp.json();
  }).then(function(items){if(items)render(items);}).catch(function(err){setStatus('加载失败：'+err.message);});
}
document.getElementById('load').addEventListener('click',function(){
  try{localStorage.setItem('recPassword',passwordInput.value);}catch(e){}
  loadList();
});
document.getElementById('refresh').addEventListener('click',loadList);
document.getElementById('live').addEventListener('click',function(){window.location.href='/';});
passwordInput.addEventListener('keydown',function(e){if(e.key==='Enter'){document.getElementById('load').click();}});
try{var saved=localStorage.getItem('recPassword');if(saved!==null)passwordInput.value=saved;}catch(e){}
loadList();
</script>
</body>
</html>
"""
}
