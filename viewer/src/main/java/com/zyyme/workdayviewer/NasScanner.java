package com.zyyme.workdayviewer;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 局域网 NAS 扫描：通过 NSD 发现 _http._tcp. 服务（咩存储注册的 WebDAV 服务），
 * 解析出 IP 与端口后回调候选列表。摄像头本身（8880 端口）会被排除。
 * WebDAV 验证（PROPFIND）由调用方完成，通过后建议以局域网域名形式保存。
 */
public class NasScanner {
    public static class Candidate {
        public final String name;
        public final String host;
        public final int port;

        Candidate(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }

        /** 建议保存的地址：局域网域名形式，存储手机 IP 变了也能连；本机服务用回环地址。 */
        public String suggestedUrl() {
            if ("127.0.0.1".equals(host)) return "http://127.0.0.1:" + port;
            return "http://" + name + ".local:" + port;
        }

        @Override
        public String toString() {
            return name + "（" + suggestedUrl() + "）";
        }
    }

    /** 在后台线程扫描 seconds 秒后，回主线程回调候选列表（可能为空）。 */
    public static void scan(Context context, int seconds, final Callback callback) {
        final Context appContext = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Candidate> candidates = doScan(appContext, 5);
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onResult(candidates);
                    }
                });
            }
        }, "nas-scan").start();
    }

    public interface Callback {
        void onResult(List<Candidate> candidates);
    }

    private static List<Candidate> doScan(Context context, int seconds) {
        List<Candidate> empty = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return empty;
        NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return empty;

        final Map<String, NsdServiceInfo> found = new LinkedHashMap<>();
        NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                String name = serviceInfo.getServiceName();
                if (name == null) return;
                synchronized (found) {
                    if (!found.containsKey(name)) found.put(name, serviceInfo);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
            }
        };
        try {
            nsd.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            return empty;
        }
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException ignored) {
        }
        try {
            nsd.stopServiceDiscovery(discovery);
        } catch (Exception ignored) {
        }

        Map<String, Candidate> result = new LinkedHashMap<>(); // key: host:port 去重
        synchronized (found) {
            for (Map.Entry<String, NsdServiceInfo> entry : found.entrySet()) {
                NsdServiceInfo info = entry.getValue();
                if (info.getPort() == 8880) continue; // 摄像头本身
                NsdServiceInfo resolved = resolveOne(nsd, info);
                if (resolved == null || resolved.getHost() == null) continue;
                String host = resolved.getHost().getHostAddress();
                if (host == null || host.isEmpty()) continue;
                String key = host + ":" + resolved.getPort();
                if (!result.containsKey(key)) {
                    result.put(key, new Candidate(entry.getKey(), host, resolved.getPort()));
                }
            }
        }
        // 本机回环探测：NSD 通常发现不了本机自己注册的服务（摄像头与咩存储同机时
        // 扫不到自己的 NAS），把 127.0.0.1:5005 作为候选交给调用方做 WebDAV 验证。
        result.put("127.0.0.1:5005", new Candidate("本机咩存储", "127.0.0.1", 5005));
        return new ArrayList<>(result.values());
    }

    /** 解析单个服务，最多等 3 秒；失败返回 null。 */
    private static NsdServiceInfo resolveOne(NsdManager nsd, NsdServiceInfo info) {
        final CountDownLatch latch = new CountDownLatch(1);
        final NsdServiceInfo[] holder = new NsdServiceInfo[1];
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    latch.countDown();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    holder[0] = serviceInfo;
                    latch.countDown();
                }
            });
        } catch (Exception e) {
            return null;
        }
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return holder[0];
    }
}
