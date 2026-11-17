package org.ledfxmobile.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class WledInfo(val host: String, val name: String, val ledCount: Int)

object WledApi {
    /** Reads name and LED count from WLED's JSON API. Returns null if it isn't a WLED device. */
    suspend fun fetchInfo(host: String): WledInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL("http://$host/json/info").openConnection() as HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            try {
                if (conn.responseCode != 200) return@withContext null
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                WledInfo(
                    host = host,
                    name = json.optString("name", "WLED"),
                    ledCount = json.optJSONObject("leds")?.optInt("count", 60) ?: 60,
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.d("WledApi", "No WLED at $host: $e")
            null
        }
    }
}

/** Finds WLED controllers on the local network via mDNS (`_wled._tcp`). */
class WledDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val _found = MutableStateFlow<List<WledInfo>>(emptyList())
    val found: StateFlow<List<WledInfo>> = _found

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()

    fun start() {
        if (listener != null) return
        _found.value = emptyList()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { _scanning.value = true }
            override fun onDiscoveryStopped(serviceType: String) { _scanning.value = false }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _scanning.value = false; listener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        listener = l
        nsd.discoverServices("_wled._tcp.", NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        _scanning.value = false
        synchronized(resolveQueue) { resolveQueue.clear() }
    }

    private fun enqueue(info: NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.addLast(info)
            if (!resolving) resolveNext()
        }
    }

    // NsdManager resolves one service at a time on older Android versions.
    private fun resolveNext() {
        val next = synchronized(resolveQueue) {
            val n = resolveQueue.removeFirstOrNull()
            resolving = n != null
            n
        } ?: return
        @Suppress("DEPRECATION")
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = resolveNext()
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = if (Build.VERSION.SDK_INT >= 34) {
                    info.hostAddresses.firstOrNull { it is java.net.Inet4Address }?.hostAddress
                } else {
                    @Suppress("DEPRECATION") info.host?.hostAddress
                }
                if (host != null) ioExecutor.execute { addHost(host, info.serviceName) }
                resolveNext()
            }
        })
    }

    private fun addHost(host: String, fallbackName: String) {
        val info = kotlinx.coroutines.runBlocking { WledApi.fetchInfo(host) }
            ?: WledInfo(host, fallbackName, 60)
        _found.value = (_found.value.filter { it.host != host } + info).sortedBy { it.name }
    }
}
