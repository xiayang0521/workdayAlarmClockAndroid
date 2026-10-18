package com.zyyme.storageserver;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 局域网 mDNS 域名注册：把 FTP / WebDAV 服务以
 * _http._tcp（WebDAV）和 _ftp._tcp（FTP）广播到局域网，
 * 其他设备可通过 workdayXXXXXX.local 访问，IP 变了也不用改配置。
 * 服务名首次随机生成后保持不变（与「工作咩闹钟」同一套机制）。
 */
public class LanDomain {
    public interface LogListener {
        void onLog(String message);
    }

    private static final List<NsdManager.RegistrationListener> listeners = new ArrayList<>();
    private static String registeredName;

    public static synchronized void register(Context context, String name,
                                             int httpPort, int ftpPort,
                                             final LogListener log) {
        unregister(context);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return;
        try {
            NsdManager nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) return;
            if (httpPort > 0) {
                registerOne(nsdManager, name, "_http._tcp.", httpPort, log);
            }
            if (ftpPort > 0) {
                registerOne(nsdManager, name, "_ftp._tcp.", ftpPort, log);
            }
        } catch (Exception e) {
            if (log != null) log.onLog("mDNS 注册异常：" + e.getMessage());
        }
    }

    private static void registerOne(NsdManager nsdManager, String name, String type,
                                    int port, final LogListener log) {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType(type);
        info.setPort(port);
        NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                // 系统可能因重名微调服务名，以回调为准
                registeredName = serviceInfo.getServiceName();
                if (log != null) {
                    log.onLog("局域网域名已注册：" + serviceInfo.getServiceName() + ".local");
                }
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                if (log != null) log.onLog("mDNS 域名注册失败：error=" + errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            }
        };
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
        listeners.add(listener);
    }

    public static synchronized void unregister(Context context) {
        if (listeners.isEmpty()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            try {
                NsdManager nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
                if (nsdManager != null) {
                    for (NsdManager.RegistrationListener l : listeners) {
                        try {
                            nsdManager.unregisterService(l);
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        listeners.clear();
        registeredName = null;
    }

    public static String currentName() {
        return registeredName;
    }

    /** 生成 workday + 6 位随机数字的域名主体。 */
    public static String generateName() {
        Random random = new Random();
        StringBuilder sb = new StringBuilder("workday");
        for (int i = 0; i < 6; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }
}
