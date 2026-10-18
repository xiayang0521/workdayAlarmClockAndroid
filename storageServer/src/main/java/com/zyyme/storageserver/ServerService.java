package com.zyyme.storageserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 前台服务：按设置启动/停止 FTP 与 WebDAV 服务器，保活并显示通知。
 */
public class ServerService extends Service {
    private static final String CHANNEL_ID = "storage_server";
    private static final int NOTIFICATION_ID = 1;
    public static final int MAX_LOG_LINES = 200;

    private static ServerService instance;

    private FtpServer ftpServer;
    private WebDavServer webDavServer;
    private final List<String> logs =
            Collections.synchronizedList(new ArrayList<String>());

    public static ServerService getInstance() {
        return instance;
    }

    public List<String> getLogs() {
        synchronized (logs) {
            return new ArrayList<>(logs);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("正在启动…"));
        applySettings();
        return START_STICKY;
    }

    /** 设置变化后调用：重启服务器使新配置生效。 */
    public void applySettings() {
        stopServers();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        Storage storage = createStorage();

        boolean ftpOn = prefs.getBoolean("ftp_on", true);
        boolean davOn = prefs.getBoolean("dav_on", true);
        String status;

        if (!ftpOn && !davOn) {
            status = "未开启任何服务器";
        } else {
            StringBuilder sb = new StringBuilder();
            int ftpPort = 0;
            int davPort = 0;
            if (ftpOn) {
                ftpPort = prefs.getInt("ftp_port", 2121);
                ftpServer = new FtpServer(ftpPort,
                        prefs.getString("ftp_user", ""),
                        prefs.getString("ftp_pass", ""),
                        storage, this::addLog);
                if (ftpServer.start()) {
                    sb.append("FTP:").append(ftpPort);
                }
            }
            if (davOn) {
                davPort = prefs.getInt("dav_port", 5005);
                webDavServer = new WebDavServer(davPort,
                        prefs.getString("dav_user", ""),
                        prefs.getString("dav_pass", ""),
                        storage, this::addLog);
                if (webDavServer.start()) {
                    if (sb.length() > 0) sb.append("　");
                    sb.append("WebDAV:").append(davPort);
                }
            }
            status = sb.length() > 0 ? sb.toString() : "启动失败（端口被占用？）";
            registerLanDomain(prefs, davPort, ftpPort);
        }
        addLog("服务状态：" + status);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(status));
    }

    /** 注册局域网域名（workdayXXXXXX.local），IP 变化不影响访问。 */
    private void registerLanDomain(SharedPreferences prefs, int davPort, int ftpPort) {
        String name = prefs.getString("lan_name", "");
        if (name.isEmpty()) {
            name = LanDomain.generateName();
            prefs.edit().putString("lan_name", name).apply();
        }
        LanDomain.register(this, name, davPort, ftpPort, this::addLog);
    }

    public String lanDomainText() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String name = prefs.getString("lan_name", "");
        if (name.isEmpty()) return "";
        String registered = LanDomain.currentName();
        return registered != null ? registered : name;
    }

    private void stopServers() {
        if (ftpServer != null) {
            ftpServer.stop();
            ftpServer = null;
        }
        if (webDavServer != null) {
            webDavServer.stop();
            webDavServer = null;
        }
        LanDomain.unregister(this);
    }

    /** 存储：用户选过 SAF 目录则用 SAF，否则用 App 专属目录。 */
    public Storage createStorage() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String tree = prefs.getString("root_uri", "");
        if (!tree.isEmpty()) {
            try {
                getContentResolver().takePersistableUriPermission(Uri.parse(tree),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                return new SafStorage(this, Uri.parse(tree));
            } catch (Exception e) {
                addLog("存储目录授权失效，已回退默认目录：" + e.getMessage());
            }
        }
        File dir = getExternalFilesDir("recordings");
        if (dir == null) dir = new File(getFilesDir(), "recordings");
        return new FileStorage(dir);
    }

    /** 当前存储目录的展示路径。 */
    public String storageDisplayPath() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String tree = prefs.getString("root_uri", "");
        if (!tree.isEmpty()) return "已选目录（SAF）：" + tree;
        File dir = getExternalFilesDir("recordings");
        if (dir == null) dir = new File(getFilesDir(), "recordings");
        return dir.getAbsolutePath();
    }

    public void addLog(String message) {
        logs.add(android.text.format.DateFormat.format("HH:mm:ss ", new java.util.Date())
                + message);
        while (logs.size() > MAX_LOG_LINES) logs.remove(0);
        Intent intent = new Intent("com.zyyme.storageserver.LOG");
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "存储服务器", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setContentTitle("咩存储 服务器运行中")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setOngoing(true);
        return builder.build();
    }

    @Override
    public void onDestroy() {
        stopServers();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
