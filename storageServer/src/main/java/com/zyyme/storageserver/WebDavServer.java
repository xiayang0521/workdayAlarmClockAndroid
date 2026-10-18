package com.zyyme.storageserver;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 极简 WebDAV 服务器：供「工作咩闹钟」等设备的录像上传客户端使用。
 * 兼容面：Basic 认证、PROPFIND（Depth 0/1，含 quota 属性）、MKCOL、
 * PUT（流式写入）、GET（支持 Range）、HEAD、DELETE、MOVE、OPTIONS。
 * 每个请求独立连接（Connection: close），与 HttpURLConnection 客户端完全兼容。
 */
public class WebDavServer {
    public interface LogListener {
        void onLog(String message);
    }

    private final int port;
    private final String username;
    private final String password;
    private final Storage storage;
    private final LogListener log;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;

    public WebDavServer(int port, String username, String password,
                        Storage storage, LogListener log) {
        this.port = port;
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
        this.storage = storage;
        this.log = log;
    }

    public boolean start() {
        if (!running.compareAndSet(false, true)) return true;
        try {
            serverSocket = new ServerSocket(port);
            Thread accept = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (running.get()) {
                        try {
                            Socket socket = serverSocket.accept();
                            new Thread(new Session(socket), "dav-client").start();
                        } catch (IOException e) {
                            if (running.get()) log("WebDAV 接受连接失败：" + e.getMessage());
                        }
                    }
                }
            }, "dav-accept");
            accept.start();
            log("WebDAV 服务器已启动，端口 " + port);
            return true;
        } catch (IOException e) {
            running.set(false);
            log("WebDAV 启动失败：" + e.getMessage());
            return false;
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        log("WebDAV 服务器已停止");
    }

    private void log(String message) {
        if (log != null) log.onLog(message);
    }

    private class Session implements Runnable {
        private final Socket socket;

        Session(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                socket.setSoTimeout(120 * 1000);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();

                String requestLine = readLine(in);
                if (requestLine == null || requestLine.isEmpty()) return;
                String[] parts = requestLine.split(" ");
                if (parts.length < 2) return;
                String method = parts[0].toUpperCase(Locale.US);
                String rawPath = parts[1];

                // 头部
                int contentLength = 0;
                String depth = "1";
                String range = null;
                String destination = null;
                String overwrite = "T";
                String auth = null;
                String methodOverride = null;
                while (true) {
                    String line = readLine(in);
                    if (line == null || line.isEmpty()) break;
                    int c = line.indexOf(':');
                    if (c < 0) continue;
                    String name = line.substring(0, c).trim();
                    String value = line.substring(c + 1).trim();
                    if (name.equalsIgnoreCase("Content-Length")) {
                        contentLength = parseInt(value, 0);
                    } else if (name.equalsIgnoreCase("Depth")) {
                        depth = value;
                    } else if (name.equalsIgnoreCase("Range")) {
                        range = value;
                    } else if (name.equalsIgnoreCase("Destination")) {
                        destination = value;
                    } else if (name.equalsIgnoreCase("Overwrite")) {
                        overwrite = value;
                    } else if (name.equalsIgnoreCase("Authorization")) {
                        auth = value;
                    } else if (name.equalsIgnoreCase("X-HTTP-Method-Override")) {
                        methodOverride = value;
                    }
                }

                // Android HttpURLConnection 不支持 PROPFIND/MKCOL 方法名，客户端会退化为
                // POST + X-HTTP-Method-Override 头，这里还原成真实方法处理。
                if ("POST".equals(method) && methodOverride != null) {
                    method = methodOverride.toUpperCase(Locale.US);
                }

                // 认证
                if (!username.isEmpty() && !checkAuth(auth)) {
                    writeResponse(out, 401, "Unauthorized", "WWW-Authenticate",
                            "Basic realm=\"webdav\"", "text/plain; charset=utf-8",
                            "authentication required".getBytes("UTF-8"));
                    return;
                }

                String path;
                try {
                    path = Storage.normalize(URLDecoder.decode(rawPath, "UTF-8"));
                } catch (Exception e) {
                    path = Storage.normalize(rawPath);
                }

                switch (method) {
                    case "OPTIONS":
                        writeResponse(out, 200, "OK",
                                new String[][]{{"DAV", "1"}, {"Allow",
                                        "OPTIONS, PROPFIND, GET, HEAD, PUT, MKCOL, DELETE, MOVE"}},
                                null, null);
                        break;
                    case "PROPFIND":
                        handlePropfind(out, path, depth, in, contentLength);
                        break;
                    case "GET":
                    case "HEAD":
                        handleGet(out, path, range, method.equals("HEAD"));
                        break;
                    case "PUT":
                        handlePut(out, path, in, contentLength);
                        break;
                    case "MKCOL":
                        handleMkcol(out, path);
                        break;
                    case "DELETE":
                        handleDelete(out, path);
                        break;
                    case "MOVE":
                        handleMove(out, path, destination, overwrite);
                        break;
                    default:
                        writeResponse(out, 405, "Method Not Allowed", null, null, null);
                        break;
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }

        private boolean checkAuth(String auth) {
            if (auth == null || !auth.startsWith("Basic ")) return false;
            try {
                String decoded = new String(
                        Base64.decode(auth.substring(6), Base64.DEFAULT), "UTF-8");
                String expect = username + ":" + password;
                return expect.equals(decoded);
            } catch (Exception e) {
                return false;
            }
        }

        // ---------- PROPFIND ----------

        private void handlePropfind(OutputStream out, String path, String depth,
                                    InputStream in, int contentLength) throws IOException {
            if (contentLength > 0) {
                int remaining = contentLength;
                byte[] buf = new byte[4096];
                while (remaining > 0) {
                    int n = in.read(buf, 0, Math.min(buf.length, remaining));
                    if (n < 0) break;
                    remaining -= n;
                }
            }
            if (!path.isEmpty() && !storage.exists(path)) {
                writeResponse(out, 404, "Not Found", null, "text/plain; charset=utf-8",
                        "not found".getBytes("UTF-8"));
                return;
            }
            boolean selfOnly = depth != null && depth.trim().equals("0");

            StringBuilder xml = new StringBuilder();
            xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
            xml.append("<D:multistatus xmlns:D=\"DAV:\">");
            appendPropfindResponse(xml, path, true);
            if (!selfOnly && (path.isEmpty() || storage.isDirectory(path))) {
                List<Storage.Entry> entries = storage.list(path);
                for (Storage.Entry e : entries) {
                    String child = path.isEmpty() ? e.name : path + "/" + e.name;
                    appendPropfindResponse(xml, child, false);
                }
            }
            xml.append("</D:multistatus>");
            writeResponse(out, 207, "Multi-Status", null,
                    "application/xml; charset=utf-8", xml.toString().getBytes("UTF-8"));
        }

        private void appendPropfindResponse(StringBuilder xml, String path, boolean isSelf) {
            boolean dir = path.isEmpty() || isDirectoryQuiet(path);
            long size = dir ? 0 : sizeQuiet(path);
            long modified = modifiedQuiet(path);
            String href = "/";
            if (!path.isEmpty()) {
                String[] segs = path.split("/");
                StringBuilder h = new StringBuilder();
                for (String s : segs) {
                    h.append('/').append(urlEncode(s));
                }
                href = h.toString();
                if (dir && !href.endsWith("/")) href = href + "/";
            }
            String when = httpDate(modified);
            xml.append("<D:response><D:href>").append(href).append("</D:href>")
                    .append("<D:propstat><D:prop>")
                    .append("<D:resourcetype>");
            if (dir) xml.append("<D:collection/>");
            xml.append("</D:resourcetype>")
                    .append("<D:getcontentlength>").append(size).append("</D:getcontentlength>")
                    .append("<D:getlastmodified>").append(when).append("</D:getlastmodified>")
                    .append("<D:quota-available-bytes>-3</D:quota-available-bytes>")
                    .append("<D:quota-used-bytes>").append(0).append("</D:quota-used-bytes>")
                    .append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>")
                    .append("</D:response>");
        }

        // ---------- GET / HEAD ----------

        private void handleGet(OutputStream out, String path, String range, boolean head)
                throws IOException {
            if (path.isEmpty() || !storage.exists(path) || storage.isDirectory(path)) {
                writeResponse(out, 404, "Not Found", null, "text/plain; charset=utf-8",
                        "not found".getBytes("UTF-8"));
                return;
            }
            long total = storage.size(path);
            long start = 0;
            long end = total - 1;
            boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                String spec = range.substring(6);
                int dash = spec.indexOf('-');
                if (dash >= 0) {
                    try {
                        if (dash > 0) start = Long.parseLong(spec.substring(0, dash).trim());
                        if (dash < spec.length() - 1) {
                            end = Long.parseLong(spec.substring(dash + 1).trim());
                        }
                        if (start > end || start >= total) {
                            writeResponse(out, 416, "Requested Range Not Satisfiable",
                                    new String[][]{{"Content-Range", "bytes */" + total}},
                                    null, null);
                            return;
                        }
                        end = Math.min(end, total - 1);
                        partial = true;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            long length = end - start + 1;
            String[][] headers = partial
                    ? new String[][]{{"Content-Range", "bytes " + start + "-" + end + "/" + total},
                    {"Accept-Ranges", "bytes"}, {"Content-Type", "video/mp4"}}
                    : new String[][]{{"Accept-Ranges", "bytes"}, {"Content-Type", "video/mp4"}};
            writeHead(out, partial ? 206 : 200,
                    partial ? "Partial Content" : "OK", headers, length);
            if (!head) {
                InputStream fileIn = storage.openRead(path);
                try {
                    long skipped = 0;
                    while (skipped < start) {
                        long n = fileIn.skip(start - skipped);
                        if (n <= 0) break;
                        skipped += n;
                    }
                    byte[] buf = new byte[64 * 1024];
                    long remaining = length;
                    while (remaining > 0) {
                        int want = (int) Math.min(buf.length, remaining);
                        int n = fileIn.read(buf, 0, want);
                        if (n < 0) break;
                        out.write(buf, 0, n);
                        remaining -= n;
                    }
                    out.flush();
                } finally {
                    fileIn.close();
                }
            }
        }

        // ---------- PUT ----------

        private void handlePut(OutputStream out, String path, InputStream in, int contentLength)
                throws IOException {
            if (path.isEmpty()) {
                writeResponse(out, 403, "Forbidden", null, null, null);
                return;
            }
            boolean existed = storage.exists(path);
            OutputStream fos = storage.openWrite(path);
            try {
                // 未声明 Content-Length 时（chunked 客户端），按流到 EOF 读
                byte[] buf = new byte[64 * 1024];
                if (contentLength > 0) {
                    int remaining = contentLength;
                    while (remaining > 0) {
                        int n = in.read(buf, 0, Math.min(buf.length, remaining));
                        if (n < 0) break;
                        fos.write(buf, 0, n);
                        remaining -= n;
                    }
                } else {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                }
                fos.flush();
            } finally {
                fos.close();
            }
            writeResponse(out, existed ? 200 : 201, existed ? "OK" : "Created",
                    null, null, null);
            log("WebDAV 已保存：" + path);
        }

        // ---------- MKCOL ----------

        private void handleMkcol(OutputStream out, String path) throws IOException {
            if (path.isEmpty()) {
                writeResponse(out, 405, "Method Not Allowed", null, null, null);
                return;
            }
            if (storage.exists(path)) {
                writeResponse(out, 405, "Method Not Allowed", null, null, null);
                return;
            }
            if (storage.mkdirs(path)) {
                writeResponse(out, 201, "Created", null, null, null);
            } else {
                writeResponse(out, 409, "Conflict", null, null, null);
            }
        }

        // ---------- DELETE ----------

        private void handleDelete(OutputStream out, String path) throws IOException {
            if (path.isEmpty() || !storage.exists(path)) {
                writeResponse(out, 404, "Not Found", null, null, null);
                return;
            }
            if (storage.delete(path)) {
                writeResponse(out, 204, "No Content", null, null, null);
            } else {
                writeResponse(out, 409, "Conflict (directory not empty)", null, null, null);
            }
        }

        // ---------- MOVE ----------

        private void handleMove(OutputStream out, String fromPath, String destination,
                                String overwrite) throws IOException {
            if (destination == null) {
                writeResponse(out, 400, "Bad Request", null, null, null);
                return;
            }
            String dest = destination;
            int scheme = dest.indexOf("://");
            if (scheme >= 0) {
                int slash = dest.indexOf('/', scheme + 3);
                dest = slash >= 0 ? dest.substring(slash) : "/";
            }
            try {
                dest = Storage.normalize(URLDecoder.decode(dest, "UTF-8"));
            } catch (Exception ignored) {
            }
            if (dest.isEmpty()) {
                writeResponse(out, 400, "Bad Request", null, null, null);
                return;
            }
            if (storage.exists(dest)) {
                if (!"F".equalsIgnoreCase(overwrite)) {
                    writeResponse(out, 412, "Precondition Failed", null, null, null);
                    return;
                }
                storage.delete(dest);
            }
            if (storage.rename(fromPath, dest)) {
                writeResponse(out, 201, "Created", null, null, null);
            } else {
                writeResponse(out, 409, "Conflict", null, null, null);
            }
        }

        // ---------- 工具 ----------

        private boolean isDirectoryQuiet(String path) {
            try {
                return storage.isDirectory(path);
            } catch (Exception e) {
                return false;
            }
        }

        private long sizeQuiet(String path) {
            try {
                return storage.size(path);
            } catch (Exception e) {
                return 0;
            }
        }

        private long modifiedQuiet(String path) {
            try {
                return storage.modified(path);
            } catch (Exception e) {
                return System.currentTimeMillis();
            }
        }

        private String urlEncode(String s) {
            try {
                return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
            } catch (Exception e) {
                return s;
            }
        }

        private String httpDate(long ms) {
            SimpleDateFormat df = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            return df.format(new java.util.Date(ms));
        }

        private int parseInt(String s, int fallback) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Exception e) {
                return fallback;
            }
        }
    }

    // ---------- HTTP 写出 ----------

    static void writeResponse(OutputStream out, int code, String reason,
                              String headerName, String headerValue,
                              String contentType, byte[] body) throws IOException {
        String[][] headers = (headerName == null) ? null
                : new String[][]{{headerName, headerValue}};
        writeResponse(out, code, reason, headers, contentType, body);
    }

    static void writeResponse(OutputStream out, int code, String reason,
                              String[][] headers, String contentType, byte[] body)
            throws IOException {
        byte[] data = body == null ? new byte[0] : body;
        writeHead(out, code, reason, headers, data.length);
        if (data.length > 0) {
            out.write(data);
        }
        out.flush();
    }

    static void writeHead(OutputStream out, int code, String reason,
                          String[][] headers, long contentLength) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("Server: workday-webdav/1.0\r\n");
        if (headers != null) {
            for (String[] h : headers) {
                sb.append(h[0]).append(": ").append(h[1]).append("\r\n");
            }
        }
        sb.append("Content-Length: ").append(contentLength).append("\r\n\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                byte[] arr = buf.toByteArray();
                int len = arr.length;
                if (len > 0 && arr[len - 1] == '\r') len--;
                return new String(arr, 0, len, "UTF-8");
            }
            if (buf.size() > 8192) return null;
            buf.write(c);
        }
        return buf.size() == 0 ? null : buf.toString("UTF-8");
    }
}
