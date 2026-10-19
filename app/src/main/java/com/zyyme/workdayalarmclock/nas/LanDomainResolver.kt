package com.zyyme.workdayalarmclock.nas

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 局域网域名（workdayXXXXXX / workdayXXXXXX.local）→ IP 解析器。
 *
 * NAS 地址按域名形式保存（存储手机 IP 变了也不用重配），但安卓系统的
 * HTTP 栈解析不了 mDNS 域名，所以真正连接前必须先用 NSD 解析出 IP。
 * 解析结果缓存 5 分钟；连接失败时调用 [evict] 清缓存，下次会自动重新
 * 解析拿到新 IP（换网络/IP 变化自动恢复）。
 *
 * 所有方法都是阻塞式，必须在后台线程调用。
 */
object LanDomainResolver {

    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val PREFIX = "workday"

    private val lock = Any()
    private val cache = HashMap<String, Pair<String, Long>>()

    fun isIp(host: String): Boolean =
        host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))

    /** 域名→IP；本身是 IP 或解析失败时原样返回。成功结果缓存 5 分钟。 */
    fun resolveHost(context: Context, host: String): String {
        val name = host.removeSuffix(".local")
        if (isIp(name)) return name
        synchronized(lock) {
            val hit = cache[name]
            if (hit != null && System.currentTimeMillis() - hit.second < CACHE_TTL_MS) {
                return hit.first
            }
        }
        val ip = resolveViaNsd(context, name) ?: return host
        synchronized(lock) { cache[name] = ip to System.currentTimeMillis() }
        return ip
    }

    /** 清除某域名的解析缓存：连接失败/IP 变化时调用，下次解析自动更新。 */
    fun evict(host: String) {
        synchronized(lock) { cache.remove(host.removeSuffix(".local")) }
    }

    /** [evict] 的 URL 版：从 URL 中取主机名清缓存。 */
    fun evictUrl(url: String) {
        try {
            evict(URL(url).host)
        } catch (_: Exception) {
        }
    }

    /**
     * 解析 URL 中的主机名（域名→IP）。
     * @return Pair<解析后的 URL, 实际连接用的 IP（本为 IP 或未变时为 null）>
     */
    fun resolveUrl(context: Context, url: String): Pair<String, String?> {
        return try {
            val u = URL(url)
            val host = u.host ?: return url to null
            val resolved = resolveHost(context, host)
            if (resolved == host) {
                url to null
            } else {
                val port = if (u.port > 0) ":${u.port}" else ""
                "${u.protocol}://$resolved$port${u.file}" to resolved
            }
        } catch (_: Exception) {
            url to null
        }
    }

    private fun resolveViaNsd(context: Context, name: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return null
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val mainHandler = Handler(Looper.getMainLooper())

        val lockObj = Object()
        val services = ArrayList<NsdServiceInfo>()
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                synchronized(lockObj) { lockObj.notifyAll() }
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                val found = serviceInfo?.serviceName ?: return
                if (found.equals(name, ignoreCase = true) ||
                    found.startsWith(PREFIX, ignoreCase = true)) {
                    synchronized(lockObj) {
                        services.add(serviceInfo)
                        lockObj.notifyAll()
                    }
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
        }
        try {
            mainHandler.post {
                try {
                    nsd.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery)
                } catch (_: Exception) {
                    synchronized(lockObj) { lockObj.notifyAll() }
                }
            }
        } catch (_: Exception) {
            return null
        }

        val deadline = System.currentTimeMillis() + 4000
        synchronized(lockObj) {
            while (System.currentTimeMillis() < deadline) {
                val exact = services.firstOrNull { it.serviceName.equals(name, ignoreCase = true) }
                if (exact != null || distinctNames(services) >= 2) break
                try {
                    lockObj.wait(400)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        mainHandler.post {
            try {
                nsd.stopServiceDiscovery(discovery)
            } catch (_: Exception) {
            }
        }

        // 优先精确同名；否则局域网里只有一台 workday* 服务时兜底用它
        var target = services.firstOrNull { it.serviceName.equals(name, ignoreCase = true) }
        if (target == null && distinctNames(services) == 1) target = services[0]
        if (target == null) return null

        val latch = CountDownLatch(1)
        val ipHolder = arrayOfNulls<String>(1)
        try {
            nsd.resolveService(target, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    latch.countDown()
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                    ipHolder[0] = serviceInfo?.host?.hostAddress
                    latch.countDown()
                }
            })
        } catch (_: Exception) {
            return null
        }
        try {
            latch.await(3000, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return ipHolder[0]
    }

    private fun distinctNames(list: List<NsdServiceInfo>): Int =
        list.mapNotNull { it.serviceName?.lowercase() }.toHashSet().size
}
