package com.zyyme.workdayalarmclock.nas

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

internal data class WebDavEntry(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)

internal class WebDavException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 零第三方依赖的 WebDAV 客户端，基于 HttpURLConnection 实现。
 * 兼容 minSdk 14：MKCOL/PROPFIND 通过 X-HTTP-Method-Override 或反射 requestMethod。
 */
internal class WebDavClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String
) {
    companion object {
        private const val CONNECT_TIMEOUT = 12_000
        private const val READ_TIMEOUT = 30_000
        private const val UPLOAD_READ_TIMEOUT = 120_000
        private val IPV4 = Regex(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"
        )
    }

    private val methodsWorkaround = arrayOf("MKCOL", "PROPFIND")

    private fun encodePath(path: String): String {
        if (path.isEmpty()) {
            return ""
        }
        return path.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
    }

    private fun resolveUrl(remotePath: String): String {
        val cleanPath = remotePath.trim().trimStart('/')
        val base = resolveHost(baseUrl)
        return if (cleanPath.isEmpty()) {
            "$base/"
        } else {
            "$base/${encodePath(cleanPath)}"
        }
    }

    /**
     * 局域网域名（workdayXXXXXX / xxx.local）在连接前解析成 IP：
     * 系统解析器支持 mDNS 的机型直接成功；否则补试 ".local" 后缀。
     * 解析失败时原样返回，让连接层报错。
     */
    private fun resolveHost(url: String): String {
        return try {
            val parsed = URL(url)
            val host = parsed.host
            if (host.isEmpty() || IPV4.matches(host)) return url
            val resolved = try {
                java.net.InetAddress.getByName(host)
            } catch (_: Exception) {
                if (host.endsWith(".local")) {
                    null
                } else {
                    try {
                        java.net.InetAddress.getByName("$host.local")
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            if (resolved == null) {
                url
            } else {
                URL(parsed.protocol, resolved.hostAddress, parsed.port, parsed.file).toString()
            }
        } catch (_: Exception) {
            url
        }
    }

    private fun applyAuth(connection: HttpURLConnection) {
        if (username.isNotEmpty()) {
            val raw = "$username:$password"
            val encoded = android.util.Base64.encodeToString(
                raw.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            connection.setRequestProperty("Authorization", "Basic $encoded")
        }
    }

    private fun openConnection(
        remotePath: String,
        method: String,
        readTimeout: Int = READ_TIMEOUT
    ): HttpURLConnection {
        val connection = URL(resolveUrl(remotePath)).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = readTimeout
        connection.instanceFollowRedirects = false
        applyAuth(connection)
        if (method in methodsWorkaround) {
            try {
                connection.requestMethod = method
            } catch (e: java.net.ProtocolException) {
                connection.requestMethod = "POST"
                connection.setRequestProperty("X-HTTP-Method-Override", method)
            }
        } else {
            connection.requestMethod = method
        }
        return connection
    }

    private fun ensureSuccess(connection: HttpURLConnection, allowed: IntArray): Int {
        val code = try {
            connection.responseCode
        } catch (e: IOException) {
            connection.disconnect()
            throw WebDavException("无法连接到 NAS：${e.message}", e)
        }
        if (code !in allowed) {
            val message = try {
                connection.errorStream?.bufferedReader()?.readText()?.take(300).orEmpty()
            } catch (e: Exception) {
                ""
            }
            connection.disconnect()
            throw WebDavException("NAS 返回错误 $code $message".trim())
        }
        return code
    }

    /** 连接测试：对基础目录做一次 PROPFIND（Depth: 0）。 */
    fun testConnection() {
        val connection = openConnection("", "PROPFIND")
        try {
            connection.setRequestProperty("Depth", "0")
            connection.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
            connection.doOutput = true
            connection.outputStream.use { output ->
                output.write(PROPFIND_REQUEST_BYTES)
            }
            ensureSuccess(connection, intArrayOf(
                HttpURLConnection.HTTP_OK, 207 /* WebDAV Multi-Status */))
        } finally {
            connection.disconnect()
        }
    }

    /** 递归创建目录，已存在视为成功。 */
    fun mkdirs(remotePath: String) {
        val clean = remotePath.trim('/')
        if (clean.isEmpty()) {
            return
        }
        val segments = clean.split('/').filter { it.isNotEmpty() }
        var current = ""
        for (segment in segments) {
            current = if (current.isEmpty()) segment else "$current/$segment"
            val connection = openConnection(current, "MKCOL")
            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_CREATED &&
                    code != HttpURLConnection.HTTP_OK &&
                    code != 405
                ) {
                    throw WebDavException("创建目录失败：$code")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * 上传本地文件。uploaded 在写入过程中回调已上传字节数。
     */
    fun upload(
        localFile: java.io.File,
        remotePath: String,
        progress: ((Long) -> Unit)? = null
    ) {
        val connection = openConnection(remotePath, "PUT", UPLOAD_READ_TIMEOUT)
        try {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(localFile.length())
            connection.setRequestProperty("Content-Type", "video/mp4")
            java.io.FileInputStream(localFile).use { input ->
                connection.outputStream.use { rawOutput ->
                    val output = if (progress != null) {
                        ProgressOutputStream(rawOutput, progress)
                    } else {
                        rawOutput
                    }
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            ensureSuccess(
                connection,
                intArrayOf(
                    HttpURLConnection.HTTP_OK,
                    HttpURLConnection.HTTP_CREATED,
                    HttpURLConnection.HTTP_NO_CONTENT
                )
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 以 GET 打开远程文件，支持 Range 请求（用于在线播放/下载代理）。
     * 调用方负责读取完成后 disconnect。
     */
    fun openRead(remotePath: String, rangeHeader: String? = null): HttpURLConnection {
        val connection = openConnection(remotePath, "GET")
        if (!rangeHeader.isNullOrEmpty()) {
            connection.setRequestProperty("Range", rangeHeader)
        }
        connection.connect()
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK &&
            code != HttpURLConnection.HTTP_PARTIAL
        ) {
            connection.disconnect()
            throw WebDavException("读取录像失败：$code")
        }
        return connection
    }

    fun delete(remotePath: String) {
        val connection = openConnection(remotePath, "DELETE")
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK &&
                code != HttpURLConnection.HTTP_NO_CONTENT &&
                code != HttpURLConnection.HTTP_NOT_FOUND
            ) {
                throw WebDavException("删除失败：$code")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 列举目录内容（Depth: 1，仅直接子项）。
     */
    fun list(remotePath: String): List<WebDavEntry> {
        val connection = openConnection(remotePath, "PROPFIND")
        try {
            connection.setRequestProperty("Depth", "1")
            connection.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
            connection.doOutput = true
            connection.outputStream.use { it.write(PROPFIND_REQUEST_BYTES) }
            ensureSuccess(connection, intArrayOf(
                HttpURLConnection.HTTP_OK, 207 /* WebDAV Multi-Status */))
            val input: InputStream = connection.inputStream
            return input.use { parsePropFind(it) }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 查询目录配额：返回 Pair(可用字节, 已用字节)。不支持时返回 null。
     */
    fun queryQuota(remotePath: String): Pair<Long, Long>? {
        val connection = openConnection(remotePath, "PROPFIND")
        try {
            connection.setRequestProperty("Depth", "0")
            connection.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
            connection.doOutput = true
            connection.outputStream.use { it.write(QUOTA_REQUEST_BYTES) }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_MULT_CHOICE) {
                return null
            }
            return connection.inputStream.use { parseQuota(it) }
        } catch (e: Exception) {
            return null
        } finally {
            connection.disconnect()
        }
    }

    private class ProgressOutputStream(
        out: OutputStream,
        private val progress: (Long) -> Unit
    ) : FilterOutputStream(out) {
        private var written = 0L

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            written += length
            progress(written)
        }

        override fun write(oneByte: Int) {
            out.write(oneByte)
            written += 1
            if (written % 16384L == 1L) {
                progress(written)
            }
        }
    }

    private fun parsePropFind(input: InputStream): List<WebDavEntry> {
        val entries = mutableListOf<WebDavEntry>()
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        var event = parser.eventType
        var href = ""
        var isCollection = false
        var size = 0L
        var lastModified = 0L
        var inResponse = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "response" -> {
                            inResponse = true
                            href = ""
                            isCollection = false
                            size = 0L
                            lastModified = 0L
                        }
                        "href" -> if (inResponse) href = parser.nextText()
                        "collection" -> isCollection = true
                        "getcontentlength" -> {
                            val text = parser.nextText()
                            size = text.trim().toLongOrNull() ?: 0L
                        }
                        "getlastmodified" -> {
                            val text = parser.nextText()
                            lastModified = parseHttpDate(text)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "response" && inResponse) {
                        val decodedHref = decodeHref(href)
                        if (decodedHref.isNotEmpty()) {
                            entries.add(WebDavEntry(decodedHref, isCollection, size, lastModified))
                        }
                        inResponse = false
                    }
                }
            }
            event = parser.next()
        }
        return entries
    }

    private fun parseQuota(input: InputStream): Pair<Long, Long>? {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        var event = parser.eventType
        var available = -1L
        var used = -1L
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "quota-available-bytes" -> {
                        val v = parser.nextText().trim().toLongOrNull()
                        if (v != null) available = v
                    }
                    "quota-used-bytes" -> {
                        val v = parser.nextText().trim().toLongOrNull()
                        if (v != null) used = v
                    }
                }
            }
            event = parser.next()
        }
        if (available < 0 && used < 0) {
            return null
        }
        return Pair(if (available < 0) Long.MAX_VALUE else available, if (used < 0) 0L else used)
    }

    private fun decodeHref(href: String): String {
        val trimmed = href.trim()
        if (trimmed.isEmpty()) {
            return ""
        }
        return try {
            java.net.URLDecoder.decode(trimmed, "UTF-8")
        } catch (e: Exception) {
            trimmed
        }
    }

    private fun parseHttpDate(value: String): Long {
        val formats = arrayOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy"
        )
        for (pattern in formats) {
            try {
                val format = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                format.isLenient = true
                format.parse(value.trim())?.let { return it.time }
            } catch (e: Exception) {
            }
        }
        return 0L
    }
}

private val PROPFIND_REQUEST_BYTES = (
    "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<D:propfind xmlns:D=\"DAV:\"><D:prop>" +
        "<D:resourcetype/><D:getcontentlength/><D:getlastmodified/>" +
        "</D:prop></D:propfind>"
    ).toByteArray(Charsets.UTF_8)

private val QUOTA_REQUEST_BYTES = (
    "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<D:propfind xmlns:D=\"DAV:\"><D:prop>" +
        "<D:quota-available-bytes/><D:quota-used-bytes/>" +
        "</D:prop></D:propfind>"
    ).toByteArray(Charsets.UTF_8)
