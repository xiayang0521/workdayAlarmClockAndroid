package com.zyyme.workdayalarmclock.camera

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/**
 * 局域网 mDNS 域名注册：把摄像头 HTTP 服务以 _http._tcp 广播到局域网，
 * 其他设备可通过 http://<域名>.local:8880 访问（Windows/macOS/Android 均支持 mDNS）。
 * 服务名即域名主体（如 workday483920），首次使用随机生成后保持不变。
 */
object LanDomain {
    private const val SERVICE_TYPE = "_http._tcp."

    @Volatile private var registeredName: String? = null
    private var registeredListener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun register(context: Context, name: String, port: Int) {
        unregister(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return
        try {
            val info = NsdServiceInfo().apply {
                setServiceName(name)
                setServiceType(SERVICE_TYPE)
                setPort(port)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(registered: NsdServiceInfo) {
                    // 系统可能微调服务名（重名冲突时），以回调名为准。
                    registeredName = registered.serviceName
                    Log.i("LanDomain", "mDNS 域名已注册：${registered.serviceName}.local:$port")
                }

                override fun onRegistrationFailed(service: NsdServiceInfo, error: Int) {
                    Log.w("LanDomain", "mDNS 域名注册失败：error=$error")
                }

                override fun onServiceUnregistered(service: NsdServiceInfo) {
                }

                override fun onUnregistrationFailed(service: NsdServiceInfo, error: Int) {
                }
            }
            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsdManager.registerService(
                info,
                NsdManager.PROTOCOL_DNS_SD,
                listener
            )
            registeredListener = listener
        } catch (e: Exception) {
            Log.w("LanDomain", "mDNS 域名注册异常：${e.message}")
        }
    }

    @Synchronized
    fun unregister(context: Context) {
        val listener = registeredListener ?: return
        registeredListener = null
        registeredName = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return
        try {
            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsdManager.unregisterService(listener)
        } catch (e: Exception) {
            Log.w("LanDomain", "mDNS 域名注销异常：${e.message}")
        }
    }

    fun currentName(): String? = registeredName
}
