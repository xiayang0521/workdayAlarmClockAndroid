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
        val mainHandler = Handler(Looper.getMainLooper())
        // NsdManager 必须在主线程创建、所有方法在主线程调用，否则回调可能丢失
        val nsdInit = CountDownLatch(1)
        val nsdBox = arrayOfNulls<NsdManager>(1)
        mainHandler.post {
            try {
                nsdBox[0] = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            } catch (_: Exception) {
            }
            nsdInit.countDown()
        }
        try {
            nsdInit.await(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return emptyList()
        }
        val nsd = nsdBox[0] ?: return emptyList()

        val found = LinkedHashMap<String, NsdServiceInfo>()
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
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
        pinnedListeners.add(discovery)
        val issued = CountDownLatch(1)
        mainHandler.post {
            try {
                nsd.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery)
            } catch (e: Exception) {
            }
            issued.countDown()
        }
        try {
            issued.await(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            pinnedListeners.remove(discovery)
            return emptyList()
        }
        Thread.sleep(seconds * 1000L)
        mainHandler.post {
            try {
                nsd.stopServiceDiscovery(discovery)
            } catch (e: Exception) {
            }
            pinnedListeners.remove(discovery)
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

    /** 钉住进行中的 Listener，防止被 GC（NsdManager 只持弱引用）。 */
    private val pinnedListeners =
        java.util.Collections.synchronizedSet(HashSet<Any>())

    /** 解析单个服务，最多等 3 秒；失败返回 null。resolveService 在主线程执行。 */
    private fun resolveOne(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<NsdServiceInfo>(1)
        // NsdManager 只弱引用 Listener，必须强引用钉住，否则回调可能被 GC 吃掉
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                latch.countDown()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                holder[0] = serviceInfo
                latch.countDown()
            }
        }
        pinnedListeners.add(listener)
        val mainHandler = Handler(Looper.getMainLooper())
        val issued = CountDownLatch(1)
        mainHandler.post {
            try {
                nsd.resolveService(info, listener)
            } catch (e: Exception) {
            }
            issued.countDown()
        }
        try {
            issued.await(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            pinnedListeners.remove(listener)
            return null
        }
        val r = try {
            latch.await(3, TimeUnit.SECONDS)
            holder[0]
        } catch (e: InterruptedException) {
            null
        }
        pinnedListeners.remove(listener)
        return r
    }
}
