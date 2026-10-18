package com.zyyme.workdayalarmclock.nas

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 局域网 NAS 扫描：通过 NSD 发现 _http._tcp. 服务（咩存储注册的 WebDAV 服务），
 * 解析出 IP 与端口后回调候选列表。摄像头本身（8880 端口）会被排除。
 * 真正的 WebDAV 验证（PROPFIND）由调用方用 [WebDavClient.testConnection] 做。
 */
object NasScanner {

    class Candidate(val name: String, val host: String, val port: Int) {
        /** 建议保存的地址：局域网域名形式，存储手机 IP 变了也能连；本机服务用回环地址。 */
        fun suggestedUrl(): String =
            if (host == "127.0.0.1") "http://127.0.0.1:$port" else "http://$name.local:$port"

        override fun toString(): String = "$name（${suggestedUrl()}）"
    }

    /** 在后台线程扫描 [seconds] 秒后，回主线程回调候选列表（可能为空）。 */
    fun scan(context: Context, seconds: Int = 5, callback: (List<Candidate>) -> Unit) {
        Thread {
            val candidates = doScan(context, seconds)
            Handler(Looper.getMainLooper()).post { callback(candidates) }
        }.start()
    }

    private fun doScan(context: Context, seconds: Int): List<Candidate> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return emptyList()
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyList()

        val found = LinkedHashMap<String, NsdServiceInfo>()
        val started = CountDownLatch(1)
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                started.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                val name = serviceInfo?.serviceName ?: return
                synchronized(found) {
                    if (!found.containsKey(name)) found[name] = serviceInfo
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
        }
        try {
            nsd.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: Exception) {
            return emptyList()
        }
        Thread.sleep(seconds * 1000L)
        try {
            nsd.stopServiceDiscovery(discovery)
        } catch (_: Exception) {
        }

        val result = LinkedHashMap<String, Candidate>() // key: host:port 去重
        synchronized(found) {
            for ((name, info) in found) {
                // 排除摄像头本身（8880）与已带端口信息的重复项
                if (info.port == 8880) continue
                val resolved = resolveOne(nsd, info) ?: continue
                val host = resolved.host?.hostAddress ?: continue
                if (host.isEmpty()) continue
                val key = "$host:${resolved.port}"
                if (!result.containsKey(key)) {
                    result[key] = Candidate(name, host, resolved.port)
                }
            }
        }
        // 本机回环探测：NSD 通常发现不了本机自己注册的服务（摄像头与咩存储同机时
        // 扫不到自己的 NAS），把 127.0.0.1:5005 作为候选交给调用方做 WebDAV 验证。
        result["127.0.0.1:5005"] = Candidate("本机咩存储", "127.0.0.1", 5005)
        return result.values.toList()
    }

    /** 解析单个服务，最多等 3 秒；失败返回 null。 */
    private fun resolveOne(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<NsdServiceInfo>(1)
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    latch.countDown()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                    holder[0] = serviceInfo
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            return null
        }
        return try {
            latch.await(3, TimeUnit.SECONDS)
            holder[0]
        } catch (e: InterruptedException) {
            null
        }
    }
}
