package com.zyyme.storageserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 极简 FTP 服务器：供摄像头/录像设备通过 FTP 上传保存录像。
 * 支持 USER/PASS、PASV（被动模式）、PORT（主动模式）、LIST、MLSD、
 * RETR、STOR、APPE、DELE、MKD、RMD、CWD、CDUP、PWD、SIZE、RNFR/RNTO、REST。
 *  passive 模式优先，绝大多数摄像头只用 PASV + STOR。
 */
public class FtpServer {
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
    private Thread acceptThread;

    public FtpServer(int port, String username, String password,
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
            acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (running.get()) {
                        try {
                            Socket socket = serverSocket.accept();
                            new Thread(new Session(socket),
                                    "ftp-client").start();
                        } catch (IOException e) {
                            if (running.get()) log("FTP 接受连接失败：" + e.getMessage());
                        }
                    }
                }
            }, "ftp-accept");
            acceptThread.start();
            log("FTP 服务器已启动，端口 " + port);
            return true;
        } catch (IOException e) {
            running.set(false);
            log("FTP 启动失败：" + e.getMessage());
            return false;
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        log("FTP 服务器已停止");
    }

    private void log(String message) {
        if (log != null) log.onLog(message);
    }

    private class Session implements Runnable {
        private final Socket control;
        private String cwd = "";      // 当前目录（规范化相对路径，"" = 根）
        private boolean authenticated;
        private String renameFrom;
        private ServerSocket passiveSocket;
        private Socket activeSocket;
        private String activeHost;
        private int activePort;
        private long restOffset = 0;

        Session(Socket socket) {
            this.control = socket;
        }

        @Override
        public void run() {
            try {
                control.setSoTimeout(5 * 60 * 1000);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(control.getInputStream(), "UTF-8"));
                OutputStream out = control.getOutputStream();
                reply(out, 220, "workday FTP ready");
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    int sp = line.indexOf(' ');
                    String cmd = (sp < 0 ? line : line.substring(0, sp)).toUpperCase(Locale.US);
                    String arg = sp < 0 ? "" : line.substring(sp + 1).trim();
                    if (!handle(out, cmd, arg)) break;
                }
            } catch (Exception ignored) {
            } finally {
                closePassive();
                try {
                    control.close();
                } catch (IOException ignored) {
                }
            }
        }

        private void reply(OutputStream out, int code, String text) throws IOException {
            String msg = code + " " + text + "\r\n";
            out.write(msg.getBytes("UTF-8"));
            out.flush();
        }

        private boolean handle(OutputStream out, String cmd, String arg) throws IOException {
            switch (cmd) {
                case "USER":
                    if (username.isEmpty()) {
                        authenticated = true;
                        reply(out, 230, "Login successful");
                    } else {
                        reply(out, 331, "Password required");
                    }
                    return true;
                case "PASS":
                    if (username.isEmpty() || arg.equals(password)) {
                        authenticated = true;
                        reply(out, 230, "Login successful");
                    } else {
                        reply(out, 530, "Login incorrect");
                    }
                    return true;
                case "SYST":
                    reply(out, 215, "UNIX Type: L8");
                    return true;
                case "FEAT":
                    String feats = "211-Features:\r\n SIZE\r\n REST STREAM\r\n MLSD\r\n UTF8\r\n211 End\r\n";
                    out.write(feats.getBytes("UTF-8"));
                    out.flush();
                    return true;
                case "OPTS":
                    reply(out, 200, "OK");
                    return true;
                case "TYPE":
                    reply(out, 200, "Type set to " + (arg.isEmpty() ? "I" : arg));
                    return true;
                case "MODE":
                case "STRU":
                case "NOOP":
                case "ALLO":
                    reply(out, 200, "OK");
                    return true;
                case "PWD":
                case "XPWD":
                    reply(out, 257, "\"" + (cwd.isEmpty() ? "/" : "/" + cwd) + "\" is current directory");
                    return true;
                case "CWD":
                case "XCWD": {
                    String target = arg.isEmpty() || arg.equals("/") || arg.equals(".")
                            ? "" : Storage.normalize(cwd + "/" + arg);
                    if (target.isEmpty() || storage.isDirectory(target)) {
                        cwd = target;
                        reply(out, 250, "Directory changed");
                    } else {
                        reply(out, 550, "Directory not found");
                    }
                    return true;
                }
                case "CDUP":
                case "XCUP":
                    cwd = parentOf(cwd);
                    reply(out, 250, "Directory changed");
                    return true;
                case "MKD":
                case "XMKD": {
                    String target = Storage.normalize(cwd + "/" + arg);
                    if (target.isEmpty() || storage.mkdirs(target)) {
                        reply(out, 257, "\"" + arg + "\" directory created");
                    } else {
                        reply(out, 550, "Create directory failed");
                    }
                    return true;
                }
                case "RMD":
                case "XRMD": {
                    String target = Storage.normalize(cwd + "/" + arg);
                    if (!target.isEmpty() && storage.delete(target)) {
                        reply(out, 250, "Directory removed");
                    } else {
                        reply(out, 550, "Remove directory failed (not empty?)");
                    }
                    return true;
                }
                case "DELE": {
                    String target = Storage.normalize(cwd + "/" + arg);
                    if (!target.isEmpty() && storage.delete(target)) {
                        reply(out, 250, "File deleted");
                    } else {
                        reply(out, 550, "Delete failed");
                    }
                    return true;
                }
                case "SIZE": {
                    String target = Storage.normalize(cwd + "/" + arg);
                    if (!target.isEmpty() && storage.exists(target) && !storage.isDirectory(target)) {
                        reply(out, 213, String.valueOf(storage.size(target)));
                    } else {
                        reply(out, 550, "File not found");
                    }
                    return true;
                }
                case "RNFR":
                    renameFrom = Storage.normalize(cwd + "/" + arg);
                    reply(out, 350, "Ready for destination name");
                    return true;
                case "RNTO":
                    if (renameFrom == null) {
                        reply(out, 503, "RNFR first");
                    } else if (storage.rename(renameFrom, Storage.normalize(cwd + "/" + arg))) {
                        reply(out, 250, "Rename successful");
                    } else {
                        reply(out, 550, "Rename failed");
                    }
                    renameFrom = null;
                    return true;
                case "REST":
                    restOffset = parseLong(arg, 0);
                    reply(out, 350, "Restarting at " + restOffset);
                    return true;
                case "PASV": {
                    closePassive();
                    passiveSocket = new ServerSocket(0);
                    passiveSocket.setSoTimeout(60 * 1000);
                    InetAddress addr = control.getLocalAddress();
                    byte[] ip = addr.getAddress();
                    int p = passiveSocket.getLocalPort();
                    String r = "Entering Passive Mode (" + (ip[0] & 0xff) + ","
                            + (ip[1] & 0xff) + "," + (ip[2] & 0xff) + "," + (ip[3] & 0xff)
                            + "," + (p >> 8) + "," + (p & 0xff) + ")";
                    reply(out, 227, r);
                    return true;
                }
                case "EPSV":
                    closePassive();
                    passiveSocket = new ServerSocket(0);
                    passiveSocket.setSoTimeout(60 * 1000);
                    reply(out, 229, "Entering Extended Passive Mode (|||"
                            + passiveSocket.getLocalPort() + "|)");
                    return true;
                case "PORT": {
                    // h1,h2,h3,h4,p1,p2
                    String[] parts = arg.split(",");
                    if (parts.length == 6) {
                        activeHost = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
                        activePort = (int) ((parseLong(parts[4], 0) << 8) + parseLong(parts[5], 0));
                        reply(out, 200, "PORT command successful");
                    } else {
                        reply(out, 501, "Bad PORT syntax");
                    }
                    return true;
                }
                case "LIST":
                case "NLST":
                case "MLSD": {
                    boolean mlsd = cmd.equals("MLSD");
                    String target = arg.isEmpty() ? cwd
                            : Storage.normalize(cwd + "/" + stripFlags(arg));
                    if (!target.isEmpty() && !storage.isDirectory(target)) {
                        reply(out, 550, "Not a directory");
                        return true;
                    }
                    Socket data = openData(out);
                    if (data == null) return true;
                    reply(out, 150, "Opening data connection");
                    try {
                        List<Storage.Entry> entries = storage.list(target);
                        StringBuilder sb = new StringBuilder();
                        SimpleDateFormat df = new SimpleDateFormat("MMM dd HH:mm", Locale.US);
                        SimpleDateFormat mdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
                        for (Storage.Entry e : entries) {
                            if (mlsd) {
                                sb.append("Type=").append(e.dir ? "dir" : "file")
                                        .append(";Size=").append(e.size)
                                        .append(";Modify=").append(mdf.format(new Date(e.modified)))
                                        .append("; ").append(e.name).append("\r\n");
                            } else {
                                sb.append(e.dir ? "drwxr-xr-x" : "-rw-r--r--")
                                        .append(" 1 owner group ")
                                        .append(String.format(Locale.US, "%12d ", e.size))
                                        .append(df.format(new Date(e.modified)))
                                        .append(" ").append(e.name).append("\r\n");
                            }
                        }
                        data.getOutputStream().write(sb.toString().getBytes("UTF-8"));
                        data.getOutputStream().flush();
                        reply(out, 226, "Transfer complete");
                    } catch (Exception e) {
                        reply(out, 550, "Transfer failed: " + e.getMessage());
                    } finally {
                        try {
                            data.close();
                        } catch (IOException ignored) {
                        }
                    }
                    return true;
                }
                case "RETR": {
                    String target = Storage.normalize(cwd + "/" + arg);
                    Socket data = openData(out);
                    if (data == null) return true;
                    InputStream in = null;
                    try {
                        in = storage.openRead(target);
                        reply(out, 150, "Opening data connection");
                        if (restOffset > 0) {
                            long skipped = 0;
                            while (skipped < restOffset) {
                                long n = in.skip(restOffset - skipped);
                                if (n <= 0) break;
                                skipped += n;
                            }
                            restOffset = 0;
                        }
                        copy(in, data.getOutputStream());
                        data.getOutputStream().flush();
                        reply(out, 226, "Transfer complete");
                    } catch (Exception e) {
                        reply(out, 550, "Transfer failed: " + e.getMessage());
                    } finally {
                        if (in != null) try {
                            in.close();
                        } catch (IOException ignored) {
                        }
                        try {
                            data.close();
                        } catch (IOException ignored) {
                        }
                    }
                    return true;
                }
                case "STOR":
                case "APPE": {
                    String target = Storage.normalize(cwd + "/" + arg);
                    Socket data = openData(out);
                    if (data == null) return true;
                    OutputStream fos = null;
                    try {
                        if (cmd.equals("APPE")) {
                            // SAF 不便追加，按覆盖处理（录像场景不会 APPE 同一文件）
                            fos = storage.openWrite(target);
                        } else {
                            fos = storage.openWrite(target);
                        }
                        reply(out, 150, "Opening data connection");
                        copy(data.getInputStream(), fos);
                        fos.flush();
                        reply(out, 226, "Transfer complete");
                        log("FTP 已保存：" + (target.isEmpty() ? arg : target));
                    } catch (Exception e) {
                        reply(out, 550, "Transfer failed: " + e.getMessage());
                    } finally {
                        if (fos != null) try {
                            fos.close();
                        } catch (IOException ignored) {
                        }
                        try {
                            data.close();
                        } catch (IOException ignored) {
                        }
                    }
                    return true;
                }
                case "QUIT":
                    reply(out, 221, "Goodbye");
                    return false;
                default:
                    reply(out, 502, "Command not implemented: " + cmd);
                    return true;
            }
        }

        private String stripFlags(String arg) {
            // LIST 可能带 -la 等参数，取最后一个以 / 分隔或裸路径
            String[] parts = arg.split(" ");
            for (String p : parts) {
                if (!p.startsWith("-")) return p;
            }
            return "";
        }

        private String parentOf(String path) {
            int i = path.lastIndexOf('/');
            return i < 0 ? "" : path.substring(0, i);
        }

        private long parseLong(String s, long fallback) {
            try {
                return Long.parseLong(s.trim());
            } catch (Exception e) {
                return fallback;
            }
        }

        /** 打开数据连接：被动优先，其次主动。失败时回复 425 并返回 null。 */
        private Socket openData(OutputStream out) throws IOException {
            if (passiveSocket != null) {
                try {
                    Socket data = passiveSocket.accept();
                    passiveSocket.close();
                    passiveSocket = null;
                    return data;
                } catch (IOException e) {
                    reply(out, 425, "Passive data connection failed");
                    return null;
                }
            }
            if (activeHost != null && activePort > 0) {
                try {
                    Socket data = new Socket(activeHost, activePort);
                    data.setSoTimeout(60 * 1000);
                    activeHost = null;
                    activePort = 0;
                    return data;
                } catch (IOException e) {
                    reply(out, 425, "Active data connection failed");
                    return null;
                }
            }
            reply(out, 425, "Use PASV or PORT first");
            return null;
        }

        private void closePassive() {
            if (passiveSocket != null) {
                try {
                    passiveSocket.close();
                } catch (IOException ignored) {
                }
                passiveSocket = null;
            }
        }

        private void copy(InputStream in, OutputStream out) throws IOException {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }
}
