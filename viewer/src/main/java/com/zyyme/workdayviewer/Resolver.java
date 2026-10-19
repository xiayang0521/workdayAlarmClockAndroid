package com.zyyme.workdayviewer;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把配置里的主机名解析成可连接的 "ip:port"。
 * IP 地址原样返回；局域网域名（workdayXXXXXX）优先走 mDNS（NSD）发现，
 * 失败时退回系统 DNS 解析。
 */
public class Resolver {
    public static class Endpoint {
        public final String host;
        public final int port;

        Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /** 解析结果缓存：域名解析要 5 秒 mDNS 扫描，缓存 5 分钟避免每次等待。 */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final Map<String, CachedEndpoint> cache = new java.util.HashMap<>();

    private static class CachedEndpoint {
        final Endpoint endpoint;
        final long at;

        CachedEndpoint(Endpoint endpoint, long at) {
            this.endpoint = endpoint;
            this.at = at;
        }
    }

    /** 串行化解析：系统同一时间只允许一个 resolveService 在跑，并发解析会互相挤失败。 */
    private static final Object RESOLVE_LOCK = new Object();

    /** 清除某域名的解析缓存（连接失败/IP 变化时调用，下次解析自动拿新 IP）。 */
    public static void evict(String host) {
        if (host == null) return;
        String name = host.endsWith(".local") ? host.substring(0, host.length() - 6) : host;
        String key = name + ":";
        synchronized (cache) {
            java.util.Iterator<Map.Entry<String, CachedEndpoint>> it = cache.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getKey().startsWith(key)) it.remove();
            }
        }
    }

    /** 阻塞解析，最多 5 秒；必须在后台线程调用。 */
    public static Endpoint resolve(Context ctx, String host, int defaultPort) {
        String key = host + ":" + defaultPort;
        synchronized (cache) {
            CachedEndpoint hit = cache.get(key);
            if (hit != null && System.currentTimeMillis() - hit.at < CACHE_TTL_MS) {
                return hit.endpoint;
            }
        }
        Endpoint result;
        synchronized (RESOLVE_LOCK) {
            // 拿到锁后再查一次缓存，避免重复 mDNS 扫描
            synchronized (cache) {
                CachedEndpoint hit = cache.get(key);
                if (hit != null && System.currentTimeMillis() - hit.at < CACHE_TTL_MS) {
                    return hit.endpoint;
                }
            }
            result = resolveSlow(ctx, host, defaultPort);
        }
        // 只缓存成功解析出的 IP；解析失败（原样返回域名）不缓存，
        // 避免一次失败把 5 分钟内的连接全部拖死。
        if (Config.isIp(result.host)) {
            synchronized (cache) {
                cache.put(key, new CachedEndpoint(result, System.currentTimeMillis()));
            }
        }
        return result;
    }

    private static Endpoint resolveSlow(Context ctx, String host, int defaultPort) {
        if (Config.isIp(host)) {
            return new Endpoint(host, defaultPort);
        }
        String name = host.endsWith(".local") ? host.substring(0, host.length() - 6) : host;
        try {
            Endpoint viaNsd = resolveViaNsd(ctx, name, defaultPort);
            if (viaNsd != null) return viaNsd;
        } catch (Exception ignored) {
        }
        // 摄像头重装/换机后域名会变（旧域名解析不到）。家庭局域网通常只有一台
        // 摄像头：此时自动改用当前唯一在线的 workday* 服务，无需用户重配设备。
        try {
            Endpoint viaPrefix = resolveViaNsdPrefix(ctx, name, defaultPort);
            if (viaPrefix != null) return viaPrefix;
        } catch (Exception ignored) {
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return new Endpoint(address.getHostAddress(), defaultPort);
        } catch (Exception e) {
            return new Endpoint(host, defaultPort);
        }
    }

    /**
     * 按前缀兜底的 mDNS 发现：收集局域网内所有 workday* 服务，
     * 命中唯一候选（或先命中与请求名一致的）时解析并返回其地址。
     */
    private static Endpoint resolveViaNsdPrefix(Context ctx, final String wantedName, int defaultPort) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return null;
        final Object lock = new Object();
        final java.util.List<NsdServiceInfo> workdayServices = new java.util.ArrayList<NsdServiceInfo>();
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final NsdManager nsdManager = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) return null;

        final NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                synchronized (lock) { lock.notifyAll(); }
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
                String found = serviceInfo.getServiceName();
                if (found == null) return;
                if (found.equalsIgnoreCase(wantedName) || found.toLowerCase().startsWith("workday")) {
                    synchronized (lock) {
                        workdayServices.add(serviceInfo);
                        lock.notifyAll();
                    }
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
            }
        };

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    nsdManager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery);
                } catch (Exception e) {
                    synchronized (lock) { lock.notifyAll(); }
                }
            }
        });

        long deadline = System.currentTimeMillis() + 3500;
        synchronized (lock) {
            while (System.currentTimeMillis() < deadline) {
                // 已找到与请求名一致的服务，或已收集到 2 个不同候选（有歧义）就提前结束
                NsdServiceInfo exact = findByName(workdayServices, wantedName);
                if (exact != null || countDistinctHosts(workdayServices) >= 2) break;
                try {
                    lock.wait(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    nsdManager.stopServiceDiscovery(discovery);
                } catch (Exception ignored) {
                }
            }
        });

        NsdServiceInfo target = findByName(workdayServices, wantedName);
        if (target == null && countDistinctHosts(workdayServices) == 1) {
            target = workdayServices.get(0);
        }
        if (target == null) return null;
        return resolveServiceInfo(nsdManager, target, defaultPort, 3000);
    }

    private static NsdServiceInfo findByName(java.util.List<NsdServiceInfo> list, String name) {
        for (NsdServiceInfo info : list) {
            if (info.getServiceName() != null && info.getServiceName().equalsIgnoreCase(name)) {
                return info;
            }
        }
        return null;
    }

    /** 按服务名去重后的数量（同名服务只算一个）。 */
    private static int countDistinctHosts(java.util.List<NsdServiceInfo> list) {
        java.util.Set<String> names = new java.util.HashSet<String>();
        for (NsdServiceInfo info : list) {
            if (info.getServiceName() != null) names.add(info.getServiceName().toLowerCase());
        }
        return names.size();
    }

    /** 解析指定服务信息，最多等待 timeoutMs。 */
    private static Endpoint resolveServiceInfo(NsdManager nsdManager, NsdServiceInfo info,
                                               final int defaultPort, long timeoutMs) {
        final AtomicReference<Endpoint> result = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            nsdManager.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    latch.countDown();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    InetAddress hostAddress = serviceInfo.getHost();
                    if (hostAddress != null) {
                        int port = serviceInfo.getPort() > 0 ? serviceInfo.getPort() : defaultPort;
                        result.set(new Endpoint(hostAddress.getHostAddress(), port));
                    }
                    latch.countDown();
                }
            });
        } catch (Exception e) {
            return null;
        }
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    private static Endpoint resolveViaNsd(Context ctx, String name, int defaultPort) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return null;
        final AtomicReference<Endpoint> result = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final NsdManager nsdManager = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) return null;

        final NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                latch.countDown();
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
                String found = serviceInfo.getServiceName();
                if (found != null && found.equalsIgnoreCase(name) && latch.getCount() > 0) {
                    try {
                        nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                            @Override
                            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                                latch.countDown();
                            }

                            @Override
                            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                                InetAddress hostAddress = serviceInfo.getHost();
                                if (hostAddress != null) {
                                    int port = serviceInfo.getPort() > 0 ? serviceInfo.getPort() : defaultPort;
                                    result.set(new Endpoint(hostAddress.getHostAddress(), port));
                                }
                                latch.countDown();
                            }
                        });
                    } catch (Exception e) {
                        latch.countDown();
                    }
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
            }
        };

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    nsdManager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery);
                } catch (Exception e) {
                    latch.countDown();
                }
            }
        });

        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    nsdManager.stopServiceDiscovery(discovery);
                } catch (Exception ignored) {
                }
            }
        });
        return result.get();
    }
}
