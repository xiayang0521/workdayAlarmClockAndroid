package com.zyyme.workdayalarmclock.camera

import android.Manifest
import android.R
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
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.zyyme.workdayalarmclock.ClockActivity
import com.zyyme.workdayalarmclock.DeskActivity
import com.zyyme.workdayalarmclock.MeService
import com.zyyme.workdayalarmclock.BuildConfig
import com.zyyme.workdayalarmclock.MeSettings
import com.zyyme.workdayalarmclock.record.RecordingAccess
import com.zyyme.workdayalarmclock.nas.WebDavClient
import com.zyyme.workdayalarmclock.nas.LanDomainResolver
import com.zyyme.workdayalarmclock.record.FsUsbRecordStore
import com.zyyme.workdayalarmclock.record.UsbRecordTargets
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
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

        /** 等待 AVC 管线吐出参数集的上限；MJPEG 管线会立即返回 null。 */
        private const val AVC_CONFIG_WAIT_MS = 3_000L
        private val ANNEX_B_START = byteArrayOf(0, 0, 0, 1)
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
    private val framebufferSessionLock = Any()
    @Volatile private var password = ""
    @Volatile private var lanDomain: String = ""
    @Volatile private var lanIp: String = ""
    @Volatile private var recordRotation: Int = 0
    @Volatile private var recordingAccess: RecordingAccess? = null
    /** 存储设置（USB 开关/配额）被客户端修改后回调，用于让 USB 投递器立即按新配置工作。 */
    @Volatile private var onStorageSettingsChanged: (() -> Unit)? = null
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

    /** 注册存储设置变更回调（由 MeService 设置，内部触发 USB 投递器重新评估配置）。 */
    fun setOnStorageSettingsChangedListener(listener: (() -> Unit)?) {
        onStorageSettingsChanged = listener
    }

    /** 更新页面顶部展示的局域网访问信息（mDNS 域名与本机 IP）。 */
    fun updateLanInfo(domain: String, ip: String) {
        lanDomain = domain.trim()
        lanIp = ip.trim()
    }

    /** 同步录像旋转角度到直播页（所见即所得：页面初始旋转与录像旋转一致）。 */
    fun updateRecordRotation(degrees: Int) {
        recordRotation = if (degrees == 90 || degrees == 180 || degrees == 270) degrees else 0
    }

    private fun lanInfoBanner(): String {
        val parts = mutableListOf<String>()
        if (lanDomain.isNotEmpty()) {
            parts.add("<a href=\"http://$lanDomain.local:$PORT\">http://$lanDomain.local:$PORT</a>")
        }
        if (lanIp.isNotEmpty() && lanIp != lanDomain) {
            parts.add("<a href=\"http://$lanIp:$PORT\">http://$lanIp:$PORT</a>")
        }
        if (parts.isEmpty()) return ""
        val pwdText = if (password.isEmpty()) "无密码" else "密码：$password"
        return "<div id=\"laninfo\" style=\"margin:0 0 10px;font-size:13px;color:#8b949e\">" +
            "局域网访问（$pwdText）：" + parts.joinToString("　或　") + "</div>"
    }

    private fun injectBanner(page: String): String {
        return if (page.contains("__LANINFO__")) {
            page.replace("__LANINFO__", lanInfoBanner())
        } else {
            page
        }
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
        synchronized(framebufferSessionLock) {
            val socket = activeFramebufferSocket
            activeFramebufferSocket = null
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            if (socket != null) framebufferStreamState(false)
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
            if (handleCameraSettingsRoute(socket, request, path)) {
                return
            }
            if (handleCameraStorageRoute(socket, request, path)) {
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
            if (e is SocketException || e is SocketTimeoutException) {
                // 客户端主动断开（如拖动进度条取消未完成的 Range 下载），属正常现象，静默处理
            } else {
                print2LogView("HTTP客户端处理失败：${e.javaClass.simpleName}: ${e.message ?: "无错误信息"}")
            }
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
        val rangeHeader: String?,
        val contentLength: Int
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
        var contentLength = -1
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
            if (headerName.equals("Content-Length", ignoreCase = true)) {
                contentLength = headerValue.toIntOrNull() ?: -1
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
            rangeHeader,
            contentLength
        )
    }

    private fun writeFramebufferInfo(socket: Socket) {
        val metrics = DisplayMetrics()
        var width = MeService.me?.fbinfoX ?: 0
        var height = MeService.me?.fbinfoY ?: 0
        if (MeService.me == null || MeService.me!!.fbinfoX == 0 || MeService.me!!.fbinfoY == 0) {
            // 在正常情况下永远不可能走到这里
            val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.defaultDisplay.getMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
        }
        val stride = width.toLong() * 4L
        val frameSize = stride * height
        val body = JSONObject()
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
        var frameCount = 0L
        var jpegBytes = 0L
        try {
            synchronized(framebufferSessionLock) {
                if (!running.get()) return
                val previous = activeFramebufferSocket
                activeFramebufferSocket = socket
                if (previous != null) {
                    try {
                        previous.close()
                    } catch (_: Exception) {
                    }
                    framebufferStreamState(false)
                }
                framebufferStreamState(true)
            }
            if (request.expectContinue) {
                socket.getOutputStream().write(
                    "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(HTTP_CHARSET)
                )
                socket.getOutputStream().flush()
            }
            socket.soTimeout = 0
            val boundaryLine = "--$boundary"
            while (running.get() && !socket.isClosed && activeFramebufferSocket === socket) {
                val line = readHttpLine(request.body)
                    ?: throw EOFException("missing MJPEG boundary")
                if (line == "$boundaryLine--") break
                if (line != boundaryLine) {
                    throw IllegalArgumentException("invalid MJPEG boundary")
                }

                var contentLength: Int? = null
                var partContentType: String? = null
                var headerCount = 0
                while (true) {
                    val header = readHttpLine(request.body)
                        ?: throw EOFException("truncated MJPEG part headers")
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
                        if (count < 0) throw EOFException("truncated JPEG frame")
                        if (count == 0) continue
                        offset += count
                    }
                    if (readHttpLine(request.body) != "") {
                        throw IllegalArgumentException("missing MJPEG part terminator")
                    }
                    synchronized(framebufferSessionLock) {
                        // A replaced reader may still have a complete frame buffered locally.
                        if (activeFramebufferSocket !== socket) return
                        framebufferFrame(jpeg, frameLength)
                        frame = null
                    }
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
            synchronized(framebufferSessionLock) {
                // The old reader must not clear the replacement session's renderer state.
                if (activeFramebufferSocket === socket) {
                    activeFramebufferSocket = null
                    framebufferStreamState(false)
                }
            }
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
            if (value < 0) throw EOFException("truncated chunk")
            remaining--
            if (remaining == 0) consumeCrlf()
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (!ensureChunk()) return -1
            val count = input.read(buffer, offset, minOf(length, remaining))
            if (count < 0) throw EOFException("truncated chunk")
            if (count == 0) return 0
            remaining -= count
            if (remaining == 0) consumeCrlf()
            return count
        }

        // 让上层能估算内核里还压着多少没读的音频(跳读要用)
        override fun available(): Int = remaining + input.available()

        private fun ensureChunk(): Boolean {
            if (finished) return false
            if (remaining > 0) return true
            val line = readLine() ?: throw EOFException("missing chunk header")
            val sizeText = line.substringBefore(';').trim()
            remaining = sizeText.toIntOrNull(16)
                ?: throw IllegalArgumentException("invalid chunk size: $line")
            if (remaining == 0) {
                while (true) {
                    val trailer = readLine() ?: throw EOFException("missing chunk trailer")
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
            var frameBytes = rate * channels * 2 / 1000  // 每 ms 的字节数
            val minBufferFallback = AudioTrack.getMinBufferSize(
                rate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // 与 aplay 保持一致：40ms 缓冲，低延迟，靠饿读追赶而不是靠大缓冲兜底
            val minBuffer = (frameBytes * 40).coerceAtLeast(minBufferFallback)
            if (minBuffer <= 0) {
                print2LogView("电脑音箱无法获取缓冲区：rate=$rate channels=$channels result=$minBuffer")
                writeEmptyResponse(socket, 415, "Unsupported Media Type")
                return
            } else {
                print2LogView("电脑音箱连接 缓冲：${minBuffer}B (约${minBuffer * 1000 / frameBytes}ms)")
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
            frameBytes = channels * 2
            // 15ms 一块：进程内排队小，跳读的控制粒度也够
            val buffer = ByteArray(
                (rate.toLong() * frameBytes * 15 / 1000).toInt().coerceAtLeast(1024) + frameBytes
            )
            // 目标是 AudioTrack 自己的缓冲 + 25ms 余量。下位机 CPU 一忙，旧音频
            // 会攒在 socket 和 AudioTrack 缓冲里(上位机估算不了)，一旦攒下就会
            // 一直背着；超过目标就从流里直接跳读丢掉，延迟立刻回落。
            val sinkFrames = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                track.bufferSizeInFrames
            } else {
                minBuffer / frameBytes
            }).coerceAtLeast(1).toLong()
            val bytesPerMs = rate.toLong() * frameBytes / 1000
            // 用 AudioTrack 实际给的缓冲当排队基准：请求 40ms 时设备常常给回
            // 70-80ms，写死会让目标低于真实地板，跳读永远在触发。
            val sinkLimitBytes: Long = sinkFrames * frameBytes
            val targetBytes = sinkLimitBytes + bytesPerMs * 25
            val deadbandBytes = bytesPerMs * 12
            val maxSkipBytes = bytesPerMs * 250
            val skipBuf = ByteArray(8 * 1024)
            var pending = 0
            var readBytes = 0L
            var skippedBytes = 0L
            var playedFrames = 0L
            var lastHead = 0
            var lastLog = 0L
            // 饿读追赶：一次读卡住多久，就说明上游攒了多久的旧音频，
            // 接下来涌来的数据开头那一段就是那么旧，直接丢掉。
            // 但卡了多久 != 声卡空了多久：缓冲薄时正常等下一批包也要卡 40-60ms，
            // 这期间声卡一直在放，所以要先减掉声卡当时还剩的积压。
            var catchUp = 0L
            // 一次追赶事件里累计丢了多少，只用来打日志：
            // catchUp 会跨多次读延续，打单轮的量会严重低估。
            var eventBytes = 0L
            var armed = true
            val rearmBytes = bytesPerMs * 30
            while (true) {
                // 先量后排、再读：只有还没进 AudioTrack 的旧音频才丢得掉
                val head = track.playbackHeadPosition
                val delta = (head - lastHead).toLong() and 0xFFFFFFFFL
                // 每轮最多前进 1 秒；异常的跳变(track 被复位)直接忽略，免得计数崩掉
                if (delta <= rate) {
                    playedFrames += delta
                }
                lastHead = head
                val available = try {
                    request.body.available().toLong()
                } catch (_: Exception) {
                    0L
                }
                // 播放头在欠载时会停住，这里推算出来的积压会大于缓冲本身，必须夹住
                val sinkNow = (readBytes - playedFrames * frameBytes - skippedBytes)
                    .coerceIn(0L, sinkLimitBytes)
                var excess = sinkNow + available - targetBytes
                if (excess >= deadbandBytes) {
                    if (excess > maxSkipBytes) excess = maxSkipBytes
                    excess -= excess % frameBytes
                    var dropped = 0L
                    while (dropped < excess) {
                        val want = minOf(excess - dropped, skipBuf.size.toLong()).toInt()
                        val n = request.body.read(skipBuf, 0, want)
                        if (n <= 0) break
                        readBytes += n
                        skippedBytes += n
                        dropped += n
                    }
                    val now = System.currentTimeMillis()
                    if (dropped > 0 && now - lastLog >= 10000) {
                        lastLog = now
                        print2LogView(
                            "电脑音箱: 排队 ${sinkNow / bytesPerMs}ms " +
                                "目标 ${targetBytes / bytesPerMs}ms，" +
                                "跳读 ${dropped / bytesPerMs}ms(累计 ${skippedBytes / bytesPerMs}ms)"
                        )
                    }
                }
                val readStart = System.nanoTime()
                val count = request.body.read(buffer, pending, buffer.size - pending)
                if (count < 0) break
                if (count == 0) continue
                readBytes += count
                var total = pending + count
                // 读之前声卡还剩多少没播(字节)，用来区分"正常等包"和"真的断粮"。
                // 播放头欠载时会停住导致推算偏大，夹到缓冲上限。
                val sinkBefore = (readBytes - count - playedFrames * frameBytes - skippedBytes)
                    .coerceIn(0L, sinkLimitBytes)
                val gapMs = (System.nanoTime() - readStart) / 1_000_000
                if (!armed) {
                    // 上一轮刚丢过，等声卡积压回血再重新武装，避免丢完就饿、饿了又丢
                    if (sinkBefore >= rearmBytes) armed = true
                } else if (gapMs > 40) {
                    // 卡住的时间减去声卡当时还能放的时间 = 真正断粮的时长
                    val starvedMs = gapMs - sinkBefore / bytesPerMs - 20
                    val owed = minOf(bytesPerMs * starvedMs, maxSkipBytes)
                        .coerceAtLeast(0L)
                    if (owed > catchUp) {
                        catchUp = owed
                        eventBytes = 0L // 新一轮事件，重新计总量
                    }
                }
                if (catchUp > 0) {
                    var drop = minOf(catchUp, total.toLong())
                    drop -= drop % frameBytes // 帧对齐，别把采样劈开
                    if (drop > 0) {
                        // pending 里那点尾巴还在前面，一起往后挪
                        System.arraycopy(
                            buffer, drop.toInt(), buffer, 0, (total - drop).toInt()
                        )
                        total -= drop.toInt()
                        catchUp -= drop
                        skippedBytes += drop
                        eventBytes += drop
                        if (catchUp == 0L) armed = false // 丢完了，等声卡回血
                        val now = System.currentTimeMillis()
                        if (now - lastLog >= 10000) {
                            lastLog = now
                            print2LogView(
                                "电脑音箱: 饿读追赶 卡了 ${gapMs}ms 声卡剩 ${sinkBefore / bytesPerMs}ms，" +
                                    "本次共丢 ${eventBytes / bytesPerMs}ms(累计 ${skippedBytes / bytesPerMs}ms)"
                            )
                        }
                    }
                }
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

        // 录像会话占用视频管线时，本通道下发的其实是 H.264 而不是 JPEG（客户端按帧头自动判别）。
        // 硬编码器只在 codec-config 缓冲里给出 SPS/PPS，关键帧通常不重复携带，客户端因此永远
        // 拿不到参数集、解码器无法创建（表现为"已连接但黑屏"）。这里先补发一帧参数集。
        val avcConfig = pipeline.awaitAvcConfig(AVC_CONFIG_WAIT_MS)
        if (avcConfig != null) {
            pipeline.requestKeyFrame()
            buildAnnexBConfigFrame(avcConfig)?.let { writeMjpegFrame(output, it) }
        }

        var sequence = 0L
        while (running.get() && !socket.isClosed) {
            val packet = pipeline.awaitPacket(sequence) ?: break
            sequence = packet.sequence
            writeMjpegFrame(output, packet.data)
        }
    }

    /** 写一帧 multipart 分片，载荷既可是 JPEG 也可是 H.264 码流。 */
    private fun writeMjpegFrame(output: java.io.OutputStream, payload: ByteArray) {
        output.write("--frame\r\n".toByteArray(HTTP_CHARSET))
        output.write("Content-Type: image/jpeg\r\n".toByteArray(HTTP_CHARSET))
        output.write("Content-Length: ${payload.size}\r\n\r\n".toByteArray(HTTP_CHARSET))
        output.write(payload)
        output.write("\r\n".toByteArray(HTTP_CHARSET))
        output.flush()
    }

    /**
     * 把 SPS/PPS 拼成一帧 AnnexB 码流（各自前置 00 00 00 01 起始码）。
     * 录像管线里的参数集是剥离了起始码的裸 NAL，所以这里要自己补上。
     */
    private fun buildAnnexBConfigFrame(config: AvcStreamConfig): ByteArray? {
        if (config.sps.isEmpty() || config.pps.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream(
            config.sps.size + config.pps.size + ANNEX_B_START.size * 2
        )
        out.write(ANNEX_B_START)
        out.write(config.sps)
        out.write(ANNEX_B_START)
        out.write(config.pps)
        return out.toByteArray()
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

    // ---------- 摄像头设置读写（供 viewer 等客户端使用） ----------

    private fun isCameraSettingsRoute(path: String): Boolean {
        val prefix = if (password.isEmpty()) "" else "/$password"
        return path == "$prefix/cam/settings"
    }

    private fun writeJsonResponse(socket: Socket, status: Int, reason: String, json: String) {
        val body = json.toByteArray(HTML_CHARSET)
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

    /**
     * 读取并解析 POST/PUT 的 JSON 请求体（上限 64KB，防止异常客户端拖垮内存）。
     * 解析失败时已向客户端写好 400 响应并返回 null，调用方直接 return 即可。
     */
    private fun readJsonBody(socket: Socket, request: HttpRequest): org.json.JSONObject? {
        if (request.contentLength <= 0 || request.contentLength > 64 * 1024) {
            writeJsonResponse(socket, 400, "Bad Request",
                org.json.JSONObject().put("ok", false).put("error", "empty body").toString())
            return null
        }
        val bytes = ByteArray(request.contentLength)
        var off = 0
        while (off < bytes.size) {
            val n = request.body.read(bytes, off, bytes.size - off)
            if (n < 0) break
            off += n
        }
        return try {
            org.json.JSONObject(String(bytes, 0, off, HTML_CHARSET))
        } catch (e: Exception) {
            writeJsonResponse(socket, 400, "Bad Request",
                org.json.JSONObject().put("ok", false)
                    .put("error", e.message ?: "bad json").toString())
            null
        }
    }

    private fun handleCameraSettingsRoute(
        socket: Socket,
        request: HttpRequest,
        path: String?
    ): Boolean {
        if (path == null || !isCameraSettingsRoute(path)) return false
        when (request.method) {
            "GET" -> writeCameraSettings(socket)
            "POST", "PUT" -> applyCameraSettings(socket, request)
            else -> writeEmptyResponse(socket, 405, "Method Not Allowed")
        }
        return true
    }

    private fun writeCameraSettings(socket: Socket) {
        val ctx = appContext
        val json = org.json.JSONObject()
            .put("rotation", MeSettings.getRecordRotation(ctx))
            .put("codec", MeSettings.getRecordCodec(ctx))
            .put("fps", MeSettings.getRecordFps(ctx))
            .put("height", MeSettings.getRecordHeight(ctx))
            .put("audio", MeSettings.isRecordAudioEnabled(ctx))
            .put("policy", MeSettings.getRecordStaticPolicy(ctx))
            .put("sens", MeSettings.getRecordMotionSensitivity(ctx))
            .toString()
        writeJsonResponse(socket, 200, "OK", json)
    }

    private fun applyCameraSettings(socket: Socket, request: HttpRequest) {
        try {
            if (request.contentLength <= 0 || request.contentLength > 64 * 1024) {
                writeJsonResponse(socket, 400, "Bad Request",
                    org.json.JSONObject().put("ok", false).put("error", "empty body").toString())
                return
            }
            val bytes = ByteArray(request.contentLength)
            var off = 0
            while (off < bytes.size) {
                val n = request.body.read(bytes, off, bytes.size - off)
                if (n < 0) break
                off += n
            }
            val root = org.json.JSONObject(String(bytes, 0, off, HTML_CHARSET))
            val ctx = appContext

            if (root.has("rotation")) {
                val v = root.getInt("rotation")
                MeSettings.setRecordRotation(ctx, if (v == 90 || v == 180 || v == 270) v else 0)
            }
            if (root.has("codec")) {
                val v = root.getString("codec")
                MeSettings.setRecordCodec(ctx,
                    if (v == MeSettings.CODEC_H265) MeSettings.CODEC_H265 else MeSettings.CODEC_H264)
            }
            if (root.has("fps")) {
                val v = root.getInt("fps")
                MeSettings.setRecordFps(ctx, v)
            }
            if (root.has("height")) {
                val v = root.getInt("height")
                MeSettings.setRecordHeight(ctx,
                    if (v == MeSettings.RECORD_HEIGHT_720) {
                        MeSettings.RECORD_HEIGHT_720
                    } else {
                        MeSettings.RECORD_HEIGHT_1080
                    })
            }
            if (root.has("audio")) {
                MeSettings.setRecordAudioEnabled(ctx, root.getBoolean("audio"))
            }
            if (root.has("policy")) {
                MeSettings.setRecordStaticPolicy(ctx, root.getInt("policy"))
            }
            if (root.has("sens")) {
                MeSettings.setRecordMotionSensitivity(ctx, root.getInt("sens"))
            }

            writeJsonResponse(socket, 200, "OK",
                org.json.JSONObject().put("ok", true).toString())
            // 旋转/编码/帧率/清晰度影响编码器配置，回复发出后延迟重启摄像头服务使其生效。
            Thread {
                try {
                    Thread.sleep(400)
                } catch (_: Exception) {
                }
                MeService.me?.restartCameraServer()
            }.start()
        } catch (e: Exception) {
            writeJsonResponse(socket, 400, "Bad Request",
                org.json.JSONObject().put("ok", false)
                    .put("error", e.message ?: "bad json").toString())
        }
    }

    // ---------- 录像存储配置读写（本地暂存 / USB / NAS / 配额） ----------

    private fun storagePrefix(): String = if (password.isEmpty()) "" else "/$password"

    private fun isCameraStorageRoute(path: String): Boolean {
        val base = "${storagePrefix()}/cam/storage"
        return path == base || path == "$base/nas/test" || path == "$base/volumes"
    }

    private fun handleCameraStorageRoute(
        socket: Socket,
        request: HttpRequest,
        path: String?
    ): Boolean {
        if (path == null || !isCameraStorageRoute(path)) return false
        val base = "${storagePrefix()}/cam/storage"
        when {
            path == "$base/nas/test" -> {
                // NAS 测试连接：用提交的参数实测，保存与否都可测（无屏设备排查配置用）。
                if (request.method != "POST" && request.method != "PUT") {
                    writeEmptyResponse(socket, 405, "Method Not Allowed")
                } else {
                    testNasConnection(socket, request)
                }
            }
            path == "$base/volumes" -> {
                // 枚举可移动存储卷，供 viewer 远程选择 USB 录像目录。
                if (request.method != "GET") {
                    writeEmptyResponse(socket, 405, "Method Not Allowed")
                } else {
                    writeStorageVolumes(socket)
                }
            }
            else -> when (request.method) {
                "GET" -> writeCameraStorage(socket)
                "POST", "PUT" -> applyCameraStorage(socket, request)
                else -> writeEmptyResponse(socket, 405, "Method Not Allowed")
            }
        }
        return true
    }

    /**
     * 返回当前存储配置与各目标空间占用：
     * local（手机暂存已用）、nas（已用/是否配置）、usb（开关、已用、剩余）、quota（配额方式与数值）。
     * NAS 统计需要一次 PROPFIND 列表请求，失败时省略 usedBytes 字段（不影响其余信息）。
     */
    private fun writeCameraStorage(socket: Socket) {
        val ctx = appContext
        val json = org.json.JSONObject()
        try {
            json.put("recordEnabled", MeSettings.isRecordEnabled(ctx))

            val localDir = File(ctx.filesDir, "recordings")
            val localUsed = localDir.listFiles()
                ?.filter { it.isFile }
                ?.sumOf { it.length() } ?: 0L
            json.put("local", org.json.JSONObject()
                .put("usedBytes", localUsed)
                .put("cacheMb", MeSettings.getLocalCacheMb(ctx)))

            // NAS（WebDAV）配置：供无屏设备在 viewer 上远程填写。
            // 安全考虑：密码只回传「是否已设置」，从不回传明文。
            val nasConfigured = MeSettings.isNasConfigured(ctx)
            val nasObj = org.json.JSONObject()
                .put("configured", nasConfigured)
                .put("url", MeSettings.getWebDavUrl(ctx))
                .put("username", MeSettings.getWebDavUsername(ctx))
                .put("dir", MeSettings.getWebDavDir(ctx))
                .put("hasPassword", MeSettings.getWebDavPassword(ctx).isNotEmpty())
            if (nasConfigured) {
                nasUsedBytesAsync(ctx)?.let { nasObj.put("usedBytes", it) }
            }
            json.put("nas", nasObj)

            val usbEnabled = MeSettings.isUsbRecordEnabled(ctx)
            val usbFsPath = MeSettings.getUsbRecordFsPath(ctx)
            val usbTreeUri = MeSettings.getUsbRecordTreeUri(ctx)
            // configured 已兼容 SAF 与 fs 直写两种后端；回传当前位置，供 viewer 回显。
            val usbObj = org.json.JSONObject()
                .put("enabled", usbEnabled)
                .put("configured", MeSettings.isUsbRecordConfigured(ctx))
                .put("fsPath", usbFsPath)
            if (usbEnabled) {
                try {
                    UsbRecordTargets.create(ctx)?.let { store ->
                        val ready = store.isAvailable()
                        usbObj.put("ready", ready)
                        if (ready) {
                            val files = store.listRecordings()
                            usbObj.put("count", files.size)
                            usbObj.put("usedBytes", files.sumOf { it.size })
                            store.availableBytes()?.let { usbObj.put("availableBytes", it) }
                        }
                    }
                } catch (e: Exception) {
                    // U 盘拔出或授权失效时其余信息仍可返回
                }
            }
            // treeUri 仅供诊断/兼容，viewer 新流程使用 fsPath；无屏设备一般为空。
            usbObj.put("hasSafUri", usbTreeUri.isNotEmpty())
            json.put("usb", usbObj)

            json.put("quota", org.json.JSONObject()
                .put("mode", MeSettings.getQuotaMode(ctx))
                .put("reserveMb", MeSettings.getQuotaReserveMb(ctx))
                .put("limitMb", MeSettings.getQuotaLimitMb(ctx)))
        } catch (e: Exception) {
            writeJsonResponse(socket, 500, "Internal Server Error",
                org.json.JSONObject().put("ok", false)
                    .put("error", e.message ?: "storage stats failed").toString())
            return
        }
        writeJsonResponse(socket, 200, "OK", json.toString())
    }

    /**
     * 用提交的 WebDAV 参数实测连接（PROPFIND 根目录）。
     * 参数缺省时回退到已保存配置，这样「不改配置只测当前」也能用。
     * 同步执行：每条 HTTP 连接本就运行在独立工作线程（camera-http-client），
     * 直接在当前线程回写响应，避免路由返回后 finally 提前关闭 socket。
     */
    private fun testNasConnection(socket: Socket, request: HttpRequest) {
        val root = readJsonBody(socket, request) ?: return
        // 取参：提交值优先，缺省回退已保存配置；密码留空回退原密码。
        val ctx = appContext
        val url = if (root.has("nasUrl")) root.getString("nasUrl").trim().trimEnd('/')
                  else MeSettings.getWebDavUrl(ctx)
        val username = if (root.has("nasUsername")) root.getString("nasUsername").trim()
                       else MeSettings.getWebDavUsername(ctx)
        val password = if (root.has("nasPassword") && root.getString("nasPassword").isNotEmpty())
                           root.getString("nasPassword")
                       else MeSettings.getWebDavPassword(ctx)

        if (url.isEmpty() || !url.startsWith("http://") && !url.startsWith("https://")) {
            writeJsonResponse(socket, 400, "Bad Request",
                org.json.JSONObject().put("ok", false)
                    .put("error", "请填写以 http:// 或 https:// 开头的 NAS 地址").toString())
            return
        }
        val result = org.json.JSONObject()
        try {
            // 域名（workdayXXXXXX.local）先经 mDNS 解析成 IP 再连接，
            // 并告知实际使用的 IP，方便排查；解析结果缓存 5 分钟。
            val (resolvedUrl, viaIp) = LanDomainResolver.resolveUrl(ctx, url)
            WebDavClient(resolvedUrl, username, password).testConnection()
            result.put("ok", true)
            if (viaIp != null) result.put("via", viaIp)
        } catch (e: Exception) {
            // 连接失败：清掉该域名的解析缓存，下次自动重新解析（IP 变了能自愈）。
            LanDomainResolver.evictUrl(url)
            // 业务失败也回 200，用 ok:false + error 表达，viewer 直接展示错误原因。
            result.put("ok", false).put("error", e.message ?: "连接失败")
        }
        writeJsonResponse(socket, 200, "OK", result.toString())
    }

    /**
     * 枚举外部/可移动存储卷，供 viewer 远程选择录像目录。
     *
     * 实现要点：
     * - 用系统 StorageManager.storageVolumes（API24+）取卷，不依赖对 /storage 根目录的读权限
     *  （该 FUSE 根在部分 ROM 上对普通 App 不可 list），挂载路径经隐藏接口反射取得；
     * - 可写性一律以「真实创建探针文件」为准，canWrite() 在可移动 FUSE 上会误报；
     * - 并入 getExternalFilesDirs() 返回的各卷 App 私有目录（系统保证可写、免 SAF/免授权）；
     * - Android 11 起可移动卷根即使持有 MANAGE_EXTERNAL_STORAGE 通常仍不可裸写（平台限制），
     *   此时该卷 writable=false，viewer 据此提示改用 SAF 一次性授权。
     */
    private fun writeStorageVolumes(socket: Socket) {
        val array = org.json.JSONArray()
        try {
            val seen = LinkedHashSet<String>()

            fun addVolume(dir: File?, removable: Boolean) {
                if (dir == null) return
                val path = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
                if (!FsUsbRecordStore.isAllowedBasePath(path)) return
                if (!seen.add(path)) return
                val exists = dir.exists() && dir.isDirectory
                val obj = org.json.JSONObject()
                    .put("path", path)
                    .put("name", dir.name)
                    .put("removable", removable)
                if (exists) {
                    obj.put("writable", probeWritable(dir))
                    runCatching {
                        val stat = android.os.StatFs(path)
                        obj.put("totalBytes", stat.totalBytes)
                        obj.put("availableBytes", stat.availableBytes)
                    }
                } else {
                    obj.put("writable", false)
                }
                array.put(obj)
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                runCatching {
                    val sm = appContext.getSystemService(android.content.Context.STORAGE_SERVICE)
                        as android.os.storage.StorageManager
                    sm.storageVolumes.forEach { vol ->
                        // getPathFile 为隐藏接口，反射取得该卷在当前用户下的挂载 File。
                        val root = runCatching {
                            val m = vol.javaClass.getMethod("getPathFile")
                            m.isAccessible = true
                            m.invoke(vol) as? File
                        }.getOrNull()
                        val removable = runCatching {
                            vol.javaClass.getMethod("isRemovable").invoke(vol) as? Boolean
                        }.getOrNull() ?: false
                        addVolume(root, removable)
                    }
                }
            } else {
                // API<24 回退：直接扫描 /storage。
                File("/storage").listFiles()?.forEach {
                    if (it.isDirectory) addVolume(it, it.name != "emulated" && it.name != "self")
                }
            }
        } catch (e: Exception) {
            writeJsonResponse(socket, 500, "Internal Server Error",
                org.json.JSONObject().put("ok", false)
                    .put("error", e.message ?: "list volumes failed").toString())
            return
        }
        writeJsonResponse(socket, 200, "OK",
            org.json.JSONObject().put("ok", true).put("volumes", array).toString())
    }

    /**
     * 在目标目录实际创建并删除一个探针文件，验证裸路径在当前 ROM 上确实可写
     *（仅靠 canWrite() 在部分可移动存储上会误报）。探针残留会被兜底删除，不污染录像目录。
     */
    private fun probeWritable(dir: java.io.File): Boolean {
        return try {
            if (!dir.exists() || !dir.isDirectory) return false
            val probe = java.io.File(dir, ".workday_write_test_${System.currentTimeMillis()}")
            val created = probe.createNewFile()
            if (created) {
                probe.outputStream().use { it.write(1) }
            }
            val ok = created && probe.exists() && probe.length() >= 0L
            probe.delete()
            ok
        } catch (_: Exception) {
            false
        }
    }

    /** NAS 录像目录已占用字节数；列目录失败返回 null。 */
    private fun nasUsedBytes(ctx: android.content.Context): Long? {
        return try {
            val client = WebDavClient(
                LanDomainResolver.resolveUrl(ctx, MeSettings.getWebDavUrl(ctx)).first,
                MeSettings.getWebDavUsername(ctx),
                MeSettings.getWebDavPassword(ctx)
            )
            client.list(MeSettings.getWebDavDir(ctx))
                .filter { !it.isDirectory }
                .sumOf { it.size }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * NAS 统计（PROPFIND 列表）在后台线程执行，最多等 [waitMs] 毫秒：
     * NAS 不可达时 WebDAV 连接超时很长，绝不能拖住 /cam/storage 整个请求，
     * 否则 viewer 读超时会认为「后台不支持存储设置」，把存储分区整个藏掉。
     */
    private fun nasUsedBytesAsync(ctx: android.content.Context, waitMs: Long = 4000): Long? {
        val holder = arrayOfNulls<Long>(1)
        val t = Thread {
            holder[0] = nasUsedBytes(ctx)
        }
        t.isDaemon = true
        t.start()
        return try {
            t.join(waitMs)
            holder[0]
        } catch (_: InterruptedException) {
            null
        }
    }

    /**
     * 保存存储配置：USB 开关、配额方式（保留可用空间/占用上限）与各阈值，
     * 以及 NAS（WebDAV）地址/账号/密码/目录（支持无屏设备在 viewer 上远程配置）。
     * 密码字段留空表示「不修改原密码」；传入特殊值可按需清空（viewer 不暴露清空入口）。
     */
    private fun applyCameraStorage(socket: Socket, request: HttpRequest) {
        try {
            val root = readJsonBody(socket, request) ?: return
            val ctx = appContext

            // ---- NAS（WebDAV）远程配置 ----
            // 仅当字段存在时才更新，避免旧客户端缺字段时误清空。
            if (root.has("nasUrl")) {
                val url = root.getString("nasUrl").trim().trimEnd('/')
                // 允许清空（传空串）以停用 NAS；非空时做最基本的协议校验。
                if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                    writeJsonResponse(socket, 400, "Bad Request",
                        org.json.JSONObject().put("ok", false)
                            .put("error", "NAS 地址需以 http:// 或 https:// 开头").toString())
                    return
                }
                MeSettings.setWebDavUrl(ctx, url)
            }
            if (root.has("nasUsername")) {
                MeSettings.setWebDavUsername(ctx, root.getString("nasUsername"))
            }
            if (root.has("nasPassword")) {
                // 留空 = 保持原密码不变，避免每次保存表单都把密码覆盖成空。
                val password = root.getString("nasPassword")
                if (password.isNotEmpty()) {
                    MeSettings.setWebDavPassword(ctx, password)
                }
            }
            if (root.has("nasDir")) {
                MeSettings.setWebDavDir(ctx, root.getString("nasDir"))
            }

            if (root.has("usbEnabled")) {
                MeSettings.setUsbRecordEnabled(ctx, root.getBoolean("usbEnabled"))
            }
            // 远程指定 USB 真实目录（免 SAF 授权）。传空串表示清除、回退到未配置。
            if (root.has("usbFsPath")) {
                val fsPath = root.getString("usbFsPath").trim().trimEnd('/')
                if (fsPath.isNotEmpty()) {
                    if (!FsUsbRecordStore.isAllowedBasePath(fsPath)) {
                        writeJsonResponse(socket, 400, "Bad Request",
                            org.json.JSONObject().put("ok", false)
                                .put("error", "目录必须位于 /storage 下的外部存储").toString())
                        return
                    }
                    val store = FsUsbRecordStore(fsPath)
                    // 保存即实测：建目录并写一个探针文件，确保无屏设备保存后真的能录像。
                    if (!store.ensureDirectory() || !probeWritable(java.io.File(fsPath))) {
                        writeJsonResponse(socket, 200, "OK",
                            org.json.JSONObject().put("ok", false)
                                .put("error", "目录不可写：请确认 U 盘已插入且路径正确（$fsPath）")
                                .toString())
                        return
                    }
                }
                MeSettings.setUsbRecordFsPath(ctx, fsPath)
            }
            if (root.has("quotaMode")) {
                MeSettings.setQuotaMode(ctx, if (root.getInt("quotaMode") == 1) 1 else 0)
            }
            if (root.has("reserveMb")) {
                // 下限 100MB：留得太小起不到保护作用；上限 100GB 防止误输入
                MeSettings.setQuotaReserveMb(ctx, root.getInt("reserveMb").coerceIn(100, 102400))
            }
            if (root.has("limitMb")) {
                MeSettings.setQuotaLimitMb(ctx, root.getInt("limitMb").coerceIn(100, 1024000))
            }
            if (root.has("localCacheMb")) {
                MeSettings.setLocalCacheMb(ctx, root.getInt("localCacheMb").coerceIn(100, 102400))
            }

            writeJsonResponse(socket, 200, "OK",
                org.json.JSONObject().put("ok", true).toString())
            // USB 开关变化后立即让投递器按新配置补传/停传，无需重启摄像头服务
            onStorageSettingsChanged?.invoke()
        } catch (e: Exception) {
            writeJsonResponse(socket, 400, "Bad Request",
                org.json.JSONObject().put("ok", false)
                    .put("error", e.message ?: "bad json").toString())
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
            val item = org.json.JSONObject()
                .put("name", info.name)
                .put("size", info.size)
                .put("time", info.timestampMs)
                .put("local", info.local)
            if (info.motion.isNotEmpty()) {
                val motion = org.json.JSONArray()
                info.motion.forEach { range ->
                    motion.put(
                        org.json.JSONArray()
                            .put(info.timestampMs + range[0])
                            .put(info.timestampMs + range[1])
                    )
                }
                item.put("motion", motion)
            }
            array.put(item)
        }
        val body = array.toString().toByteArray(HTML_CHARSET)
        val header = "HTTP/1.0 200 OK\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-cache, no-store\r\n" +
            "X-App-Version: ${BuildConfig.VERSION_NAME}\r\n" +
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
                    "Content-Range: bytes $start-$end/$length\r\n" +
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

    /** 合并页：实时画面与录像回放在同一窗口用页签切换。defaultTab 为 "live" 或 "rec"。 */
    private fun serveCombined(socket: Socket, defaultTab: String) {
        var page = COMBINED_PAGE
        if (meBgCamCtrl.enabled && defaultTab == "live") {
            page = page.replace("</body>", "${meBgCamCtrl.playerUi()}\n</body>")
        }
        page = injectBanner(page)
            .replace("__DEFAULT_TAB__", defaultTab)
            .replace("__RECORD_ROTATION__", recordRotation.toString())
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

    private fun writeRecordingsPage(socket: Socket) {
        serveCombined(socket, "rec")
    }

    private fun writePlayerPage(socket: Socket) {
        serveCombined(socket, "live")
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

    private val COMBINED_PAGE = """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>咩IP摄像头 · 监控与回放</title>
<style>
*{box-sizing:border-box}body{font-family:sans-serif;max-width:1100px;margin:0 auto;padding:16px;background:#0d1117;color:#eee}
.bar{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:12px}
.bar input{flex:1 1 140px;min-width:0;min-height:42px;font-size:16px;padding:8px 10px;border:1px solid #555;border-radius:6px;background:#161b22;color:#eee}
.bar button{flex:0 0 auto;min-height:42px;padding:8px 14px;font-size:16px;border:1px solid #278bd2;border-radius:6px;background:#1769aa;color:#fff;cursor:pointer}
.bar button.ghost{background:#21262d;border-color:#444;color:#eee}
.bar button.on{background:#1769aa;border-color:#278bd2;color:#fff;font-weight:bold}
#controls{display:flex;flex-wrap:wrap;gap:8px;align-items:end}
.field{display:flex;flex:1 1 180px;min-width:0;flex-direction:column;gap:4px;font-size:14px;color:#bbb}
.field input{width:100%;min-height:42px;font:inherit;font-size:16px;padding:8px 10px;border:1px solid #555;border-radius:4px;background:#222;color:#eee}
#controls button{width:100%;min-height:42px;font:inherit;font-size:16px;padding:8px 10px;border:1px solid #555;border-radius:4px;flex:0 1 110px;cursor:pointer;background:#333;color:#eee}
#controls button[type=submit]{background:#1769aa;border-color:#278bd2}
#modes{display:flex;gap:14px;align-items:center;min-height:42px;padding:0 8px}.mode{display:flex;gap:7px;align-items:center;color:#eee;font-size:16px}.mode input{width:20px;min-height:20px;margin:0;padding:0}
#videoFrame{width:100%;max-width:100vw;max-height:100vh;aspect-ratio:16/9;margin:16px auto 0;display:flex;align-items:center;justify-content:center;overflow:hidden;background:#000}
video,#mjpeg{display:block;width:100%;height:100%;object-fit:contain;transform-origin:center center}audio{display:block;width:100%;margin-top:12px}
.status{color:#aaa;margin-top:8px;min-height:1.4em}
button:disabled{opacity:.45;cursor:default}
h1{font-size:18px;margin:4px 0 12px}
button{min-height:40px;padding:8px 14px;font-size:15px;border:1px solid #278bd2;border-radius:6px;background:#1769aa;color:#fff;cursor:pointer}
button.ghost{background:#21262d;border-color:#444}
#status{margin:10px 0;color:#9aa4b0;min-height:1.2em;font-size:14px}
#playerWrap{width:100%;background:#000;border-radius:8px;margin-bottom:8px;max-height:60vh;position:relative}
#player{width:100%;max-height:60vh;display:block;background:#000;border-radius:8px}
#caption{font-size:13px;color:#8b949e;margin:0 0 8px;word-break:break-all}
#pcontrols{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:12px}
#pcontrols button,#pcontrols select,#pcontrols label.pcb{min-height:36px;padding:6px 12px;font-size:14px;border-radius:6px}
#pcontrols select{background:#21262d;color:#eee;border:1px solid #444;font-size:14px}
#pcontrols label.pcb{display:flex;gap:6px;align-items:center;border:1px solid #444;background:#21262d;color:#eee;cursor:pointer;user-select:none}
#pcontrols button{width:auto;flex:0 0 auto}
#errBox{margin:0 0 12px;padding:10px 12px;border:1px solid #8a2b2b;background:#2d1414;border-radius:6px;font-size:14px;color:#ffb4b4}
#errBox a{color:#79b8ff;margin-left:8px}
ul{list-style:none;margin:0;padding:0}
li{display:flex;align-items:center;gap:10px;padding:11px 8px;border-bottom:1px solid #21262d}
li.playing{background:#10233a;outline:1px solid #1f6feb;border-radius:6px}
.dateHead{font-size:14px;color:#79b8ff;padding:10px 4px 6px;border-bottom:1px solid #30363d;font-weight:bold;cursor:pointer}
.monthBox,.dayBox{margin:0}
.monthHead{font-size:16px;color:#e6b450;padding:12px 4px 6px;font-weight:bold;cursor:pointer;border-bottom:1px solid #30363d}
.dayBox summary::-webkit-details-marker,.monthBox summary::-webkit-details-marker{color:#555}
details.hideEmpty{display:none}
.meta{flex:1;min-width:0}.meta .t{font-size:15px;word-break:break-all}.meta .s{font-size:12px;color:#8b949e;margin-top:3px}
.badge{font-size:11px;padding:1px 6px;border-radius:10px;margin-left:6px}
.badge.local{background:#1769aa}.badge.nas{background:#5a3e00;color:#ffc76b}.badge.cam{background:#234}
.act{display:flex;gap:8px;flex-shrink:0}.act a,.act button{min-height:36px;padding:6px 12px;font-size:14px;text-decoration:none;border-radius:6px}
.act a{border:1px solid #444;color:#eee;background:#21262d}
.act button{width:auto;flex:0 0 auto}
[hidden]{display:none!important}
@media (max-width:520px){body{padding:10px}#controls{display:grid;grid-template-columns:1fr 1fr;gap:8px}.field,#modes{grid-column:span 2}#controls button{flex:auto;width:auto}#videoFrame{margin-top:10px}}
</style>
</head>
<body>
__LANINFO__
<div class="bar">
<input id="password" type="text" autocomplete="off" placeholder="访问密码（无密码可留空）">
<button id="tabLive" type="button" class="ghost">实时画面</button>
<button id="tabRec" type="button" class="ghost">录像回放</button>
<input id="filter" type="text" autocomplete="off" placeholder="筛选录像：如 10-15 或 c1" hidden>
<button id="refresh" type="button" class="ghost" hidden>刷新</button>
</div>
<div id="livePane">
<form id="controls">
<label class="field">摄像头 <input id="camera" type="number" min="1" value="1"></label>
<label class="field">分辨率档位 <input id="resolution" type="number" min="1" placeholder="自动"></label>
<div id="modes">
<label class="mode"><input id="playVideo" type="checkbox" checked>视频</label>
<label class="mode"><input id="playAudio" type="checkbox" checked>声音</label>
</div>
<button id="playStop" type="submit">播放</button>
<button id="rotate" type="button" aria-label="旋转画面">旋转 0°</button>
</form>
<div id="videoFrame"><video id="video" controls autoplay muted playsinline></video></div>
<div id="videoStatus" class="status"></div>
<audio id="audio" controls autoplay></audio>
<div id="audioStatus" class="status"></div>
</div>
<div id="recPane" hidden>
<h1>监控录像</h1>
<div id="status"></div>
<div id="playerWrap" hidden><video id="player" controls playsinline></video></div>
<div id="caption" hidden></div>
<div id="pcontrols" hidden>
<button id="prevBtn" class="ghost" title="上一段">⏮ 上一个</button>
<button id="nextBtn" class="ghost" title="下一段">下一个 ⏭</button>
<select id="rateSel" title="播放速度">
<option value="0.5">0.5×</option>
<option value="1" selected>1×</option>
<option value="1.25">1.25×</option>
<option value="1.5">1.5×</option>
<option value="2">2×</option>
<option value="4">4×</option>
</select>
<button id="shotBtn" class="ghost" title="截取当前画面">📷 截图</button>
<button id="fsBtn" class="ghost" title="全屏播放">⛶ 全屏</button>
<label class="pcb" title="播完自动跳下一段"><input id="autoNext" type="checkbox" checked>自动连播</label>
<button id="closeBtn" class="ghost" title="关闭播放器">✕ 关闭</button>
</div>
<div id="errBox" hidden></div>
<div id="list"></div>
</div>
<script>
var RECORD_ROTATION=__RECORD_ROTATION__;
const video=document.getElementById('video'),audio=document.getElementById('audio');
const videoFrame=document.getElementById('videoFrame'),form=document.getElementById('controls');
const playStopButton=document.getElementById('playStop'),rotateButton=document.getElementById('rotate');
const videoStatus=document.getElementById('videoStatus'),audioStatus=document.getElementById('audioStatus');
const playVideo=document.getElementById('playVideo'),playAudio=document.getElementById('playAudio');
let runId=0,rotation=RECORD_ROTATION,playbackActive=false,mjpegImage=null;
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
var passwordInput=document.getElementById('password'),statusNode=document.getElementById('status');
var listNode=document.getElementById('list'),player=document.getElementById('player');
var wrapNode=document.getElementById('playerWrap'),capNode=document.getElementById('caption');
var pcNode=document.getElementById('pcontrols'),errNode=document.getElementById('errBox');
var rateSel=document.getElementById('rateSel'),autoNextBox=document.getElementById('autoNext');
var prevBtn=document.getElementById('prevBtn'),nextBtn=document.getElementById('nextBtn');
var allItems=[],curIndex=-1,recLoaded=false;
function apiPrefix(){var p=passwordInput.value.trim().replace(/^\/+|\/+$/g,'');return p?encodeURIComponent(p)+'/':'';}
function setStatus(text){statusNode.textContent=text||'';}
function formatSize(bytes){if(!bytes)return '0 B';var u=['B','KB','MB','GB'],i=Math.min(u.length-1,Math.floor(Math.log(bytes)/Math.log(1024)));return (bytes/Math.pow(1024,i)).toFixed(i?1:0)+' '+u[i];}
function formatTime(ms){try{return new Date(ms).toLocaleString();}catch(e){return String(ms);}}
function pad2(n){return (n<10?'0':'')+n;}
function camOf(name){var m=/^rec_[0-9]{8}-[0-9]{6}_c([0-9]+)\.mp4/i.exec(name||'');return m?('摄像头 '+m[1]):'';}
function errText(){try{var c=player.error&&player.error.code;return c===1?'加载被中止':c===2?'网络错误，拉取录像失败':c===3?'解码错误，浏览器无法解码这段视频':c===4?'格式不支持或文件损坏':'未知错误('+(player.error?player.error.code:'?')+')';}catch(e){return '播放失败';}}
function clearErr(){errNode.hidden=true;errNode.innerHTML='';}
function showErr(){var name=curIndex>=0?allItems[curIndex].name:'';errNode.hidden=false;
 errNode.textContent='回放失败：'+errText()+'。';
 var a=document.createElement('a');a.textContent='点击下载到本地播放';a.href=apiPrefix()+'rec/file/'+encodeURIComponent(name)+'?download=1';a.setAttribute('download',name);
 errNode.appendChild(a);}
function showPlayer(){wrapNode.hidden=false;capNode.hidden=false;pcNode.hidden=false;}
function stopPlay(){player.pause();player.removeAttribute('src');player.load();wrapNode.hidden=true;capNode.hidden=true;pcNode.hidden=true;curIndex=-1;clearErr();highlight();}
function highlight(){var lis=listNode.querySelectorAll('li.rec');for(var i=0;i<lis.length;i++){lis[i].className=lis[i].className.replace(/\bplaying\b/g,'').replace(/^\s+|\s+$/g,'');}
 if(curIndex>=0){var n=listNode.querySelector('li[data-idx="'+curIndex+'"]');if(n){n.className=(n.className?n.className+' ':'')+'playing';}}}
function updateNav(){prevBtn.disabled=curIndex<=0;nextBtn.disabled=curIndex<0||curIndex>=allItems.length-1;}
function playIndex(idx){
 if(idx<0||idx>=allItems.length)return;
 curIndex=idx;clearErr();
 var item=allItems[idx],url=apiPrefix()+'rec/file/'+encodeURIComponent(item.name);
 capNode.textContent='正在播放：'+item.name+'（'+formatTime(item.time)+'）';
 showPlayer();highlight();updateNav();
 player.src=url;player.load();player.playbackRate=Number(rateSel.value)||1;
 var p=player.play();if(p&&p.catch)p.catch(function(){});
 try{wrapNode.scrollIntoView({behavior:'smooth',block:'nearest'});}catch(e){}
}
function playItem(name){for(var i=0;i<allItems.length;i++){if(allItems[i].name===name){playIndex(i);return;}}}
player.addEventListener('ended',function(){if(autoNextBox.checked){playIndex(curIndex+1);}});
player.addEventListener('error',function(){if(player.getAttribute('src'))showErr();});
rateSel.addEventListener('change',function(){player.playbackRate=Number(rateSel.value)||1;});
prevBtn.addEventListener('click',function(){playIndex(curIndex-1);});
nextBtn.addEventListener('click',function(){playIndex(curIndex+1);});
document.getElementById('closeBtn').addEventListener('click',function(){stopPlay();});
document.getElementById('fsBtn').addEventListener('click',function(){
 if(document.fullscreenElement){document.exitFullscreen();return;}
 var el=wrapNode;if(el.requestFullscreen)el.requestFullscreen();else if(el.webkitRequestFullscreen)el.webkitRequestFullscreen();});
document.getElementById('shotBtn').addEventListener('click',function(){
 if(!player.videoWidth){setStatus('当前没有画面可截取');return;}
 var c=document.createElement('canvas');c.width=player.videoWidth;c.height=player.videoHeight;
 c.getContext('2d').drawImage(player,0,0,c.width,c.height);
 var base=curIndex>=0?allItems[curIndex].name.replace(/\.mp4/i,''):'snapshot';
 c.toBlob(function(b){if(!b)return;var a=document.createElement('a');
  a.href=URL.createObjectURL(b);
  a.download=base+'_'+pad2(new Date().getHours())+pad2(new Date().getMinutes())+pad2(new Date().getSeconds())+'.png';
  document.body.appendChild(a);a.click();
  setTimeout(function(){URL.revokeObjectURL(a.href);a.remove();},1500);},'image/png');});
document.addEventListener('keydown',function(e){
 if(wrapNode.hidden||e.target===passwordInput)return;
 if(e.key==='ArrowLeft'){player.currentTime=Math.max(0,player.currentTime-5);e.preventDefault();}
 else if(e.key==='ArrowRight'){player.currentTime=Math.min(player.duration||player.currentTime+5,player.currentTime+5);e.preventDefault();}});
function buildItem(item,idx,d){
  var li=document.createElement('li');li.className='rec';li.setAttribute('data-idx',idx);
  var meta=document.createElement('div');meta.className='meta';
  var t=document.createElement('div');t.className='t';
  t.textContent=pad2(d.getHours())+':'+pad2(d.getMinutes())+':'+pad2(d.getSeconds());
  var badge=document.createElement('span');badge.className='badge '+(item.local?'local':'nas');
  badge.textContent=item.local?'本机':'NAS';t.appendChild(badge);
  var cam=camOf(item.name);
  if(cam){var cb=document.createElement('span');cb.className='badge cam';cb.textContent=cam;t.appendChild(cb);}
  var s=document.createElement('div');s.className='s';s.textContent=formatSize(item.size)+' · '+item.name;
  meta.appendChild(t);meta.appendChild(s);
  var act=document.createElement('div');act.className='act';
  var playBtn=document.createElement('button');playBtn.textContent='播放';
  playBtn.addEventListener('click',function(){playIndex(idx);});
  var dl=document.createElement('a');dl.textContent='下载';dl.href=apiPrefix()+'rec/file/'+encodeURIComponent(item.name)+'?download=1';
  dl.setAttribute('download',item.name);
  act.appendChild(playBtn);act.appendChild(dl);
  li.appendChild(meta);li.appendChild(act);
  return li;
}
function render(items){
 listNode.innerHTML='';clearErr();
 if(!items.length){setStatus('暂无录像文件');return;}
 items.sort(function(a,b){return (b.time||0)-(a.time||0);});
 allItems=items;
 setStatus('共 '+items.length+' 段录像（按月、日分组，点标题展开/收起）');
 var monthMap={},order=[];
 items.forEach(function(item,idx){
  var d=new Date(item.time||0);
  var mk=d.getFullYear()+'-'+d.getMonth();
  var dk=mk+'-'+d.getDate();
  if(!monthMap[mk]){monthMap[mk]={count:0,days:{},dorder:[]};order.push(mk);}
  var m=monthMap[mk];m.count++;
  if(!m.days[dk]){m.days[dk]={count:0,items:[]};m.dorder.push(dk);}
  m.days[dk].count++;m.days[dk].items.push({item:item,idx:idx,d:d});
 });
 order.forEach(function(mk,mii){
  var m=monthMap[mk],parts=mk.split('-'),y=Number(parts[0]),mo=Number(parts[1]);
  var mdet=document.createElement('details');mdet.className='monthBox';
  if(mii===0)mdet.open=true;
  var msum=document.createElement('summary');msum.className='monthHead';
  msum.textContent=y+' 年 '+(mo+1)+' 月（'+m.count+' 段）';
  mdet.appendChild(msum);
  m.dorder.forEach(function(dk,dii){
   var day=m.days[dk],dom=Number(dk.split('-')[2]);
   var ddet=document.createElement('details');ddet.className='dayBox';
   if(mii===0&&dii===0)ddet.open=true;
   var dsum=document.createElement('summary');dsum.className='dateHead';
   dsum.textContent=(mo+1)+' 月 '+dom+' 日（'+day.count+' 段）';
   ddet.appendChild(dsum);
   day.items.forEach(function(entry){ddet.appendChild(buildItem(entry.item,entry.idx,entry.d));});
   mdet.appendChild(ddet);
  });
  listNode.appendChild(mdet);
 });
 applyFilter();
}
function applyFilter(){
 var kw=document.getElementById('filter').value.trim().toLowerCase();
 var anyVisible=false;
 allItems.forEach(function(item,idx){
  var n=listNode.querySelector('li[data-idx="'+idx+'"]');if(!n)return;
  var d=new Date(item.time||0);
  var hay=(item.name+' '+pad2(d.getMonth()+1)+'-'+pad2(d.getDate())+' '+pad2(d.getHours())+' '+d.getFullYear()).toLowerCase();
  var show=!kw||hay.indexOf(kw)>=0;
  n.style.display=show?'':'none';
  if(show)anyVisible=true;
 });
 var boxes=listNode.querySelectorAll('details.dayBox');
 for(var i=0;i<boxes.length;i++){
  var vis=false,lis=boxes[i].querySelectorAll('li');
  for(var j=0;j<lis.length;j++){if(lis[j].style.display!=='none'){vis=true;break;}}
  boxes[i].className=vis||!kw?'dayBox':'dayBox hideEmpty';
  if(kw&&vis)boxes[i].open=true;
 }
 var months=listNode.querySelectorAll('details.monthBox');
 for(var k=0;k<months.length;k++){
  var mv=false,ds=months[k].querySelectorAll('details.dayBox');
  for(var t=0;t<ds.length;t++){if(ds[t].className.indexOf('hideEmpty')<0){mv=true;break;}}
  months[k].className=mv||!kw?'monthBox':'monthBox hideEmpty';
 }
 if(kw&&!anyVisible)setStatus('没有匹配「'+kw+'」的录像');
 else setStatus('共 '+allItems.length+' 段录像（按月、日分组）');
}
document.getElementById('filter').addEventListener('input',applyFilter);
function loadList(){
 stopPlay();setStatus('加载中…');
 fetch(apiPrefix()+'rec/list',{cache:'no-store'}).then(function(resp){
  if(resp.status===404){setStatus('密码错误或录像服务不可用');render([]);return null;}
  if(!resp.ok){setStatus('加载失败：'+resp.status);return null;}
  return resp.json();
 }).then(function(items){if(items)render(items);}).catch(function(err){setStatus('加载失败：'+err.message);});
}
document.getElementById('refresh').addEventListener('click',function(){
 try{localStorage.setItem('cameraPassword',passwordInput.value);}catch(e){}
 loadList();
});
passwordInput.addEventListener('keydown',function(e){
 if(e.key!=='Enter')return;
 if(document.getElementById('recPane').hidden){document.getElementById('playStop').click();}
 else{document.getElementById('refresh').click();}
});
function showTab(name){
 stopPlay();stopPlayback(false);
 document.getElementById('livePane').hidden=name!=='live';
 document.getElementById('recPane').hidden=name!=='rec';
 document.getElementById('filter').hidden=name!=='rec';
 document.getElementById('refresh').hidden=name!=='rec';
 document.getElementById('tabLive').className=name==='live'?'on':'ghost';
 document.getElementById('tabRec').className=name==='rec'?'on':'ghost';
 if(name==='rec'&&!recLoaded){recLoaded=true;loadList();}
}
document.getElementById('tabLive').addEventListener('click',function(){showTab('live');});
document.getElementById('tabRec').addEventListener('click',function(){showTab('rec');});
var DEFAULT_TAB='__DEFAULT_TAB__';
showTab(DEFAULT_TAB==='rec'?'rec':'live');
</script>
</body>
</html>
"""
}
