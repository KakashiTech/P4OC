package dev.blazelight.p4oc.core.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.blazelight.p4oc.core.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet6Address
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = "MdnsDiscovery"
private const val SERVICE_TYPE = "_http._tcp."
private const val SERVICE_NAME_PREFIX = "opencode-"

/**
 * A discovered OpenCode server on the local network.
 */
data class DiscoveredServer(
    val serviceName: String,
    val host: String,
    val port: Int,
    val url: String
)

/**
 * State of mDNS discovery scanning.
 */
enum class DiscoveryState {
    IDLE,
    SCANNING,
    ERROR
}

/**
 * Manages mDNS/NSD discovery of OpenCode servers on the local network.
 *
 * Uses Android's [NsdManager] to browse for `_http._tcp.` services whose name
 * starts with `opencode-`. Resolves each discovered service sequentially
 * (NSD limitation: only one resolve at a time) and exposes results via
 * [discoveredServers] and [discoveryState] StateFlows.
 */
class MdnsDiscoveryManager(private val context: Context) {

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()

    private val _discoveryState = MutableStateFlow(DiscoveryState.IDLE)
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()

    /** Queue for sequential resolution (NSD can only resolve one at a time). */
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    @Volatile
    private var isResolving = false

    private var activeListener: NsdManager.DiscoveryListener? = null

    /**
     * Start browsing for OpenCode servers on the local network.
     * If already scanning, stops the previous scan first.
     */
    fun startDiscovery() {
        // Stop any existing discovery first (handles rapid stop/start)
        if (activeListener != null) {
            AppLog.d(TAG, "Stopping previous discovery before restarting")
            stopDiscovery()
        }

        // Clear previous results
        _discoveredServers.value = emptyList()
        _discoveryState.value = DiscoveryState.SCANNING

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                AppLog.d(TAG, "Discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                AppLog.d(TAG, "Service found: $name")
                if (name.startsWith(SERVICE_NAME_PREFIX, ignoreCase = true)) {
                    AppLog.d(TAG, "OpenCode service matched: $name, queuing resolve")
                    enqueueResolve(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                AppLog.d(TAG, "Service lost: $name")
                _discoveredServers.update { servers ->
                    servers.filter { it.serviceName != name }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                AppLog.d(TAG, "Discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.e(TAG, "Start discovery failed: errorCode=$errorCode")
                activeListener = null
                _discoveryState.value = DiscoveryState.ERROR
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.w(TAG, "Stop discovery failed: errorCode=$errorCode")
            }
        }

        activeListener = listener

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to start discovery", e)
            activeListener = null
            _discoveryState.value = DiscoveryState.ERROR
        }
    }

    /**
     * Stop browsing for servers.
     */
    fun stopDiscovery() {
        val listener = activeListener ?: return
        activeListener = null

        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            // IllegalArgumentException if listener not registered — safe to ignore
            AppLog.w(TAG, "Error stopping discovery: ${e.message}")
        }

        resolveQueue.clear()
        isResolving = false
        _discoveryState.value = DiscoveryState.IDLE
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        resolveQueue.add(serviceInfo)
        processResolveQueue()
    }

    @Synchronized
    private fun processResolveQueue() {
        if (isResolving) return
        val next = resolveQueue.poll() ?: return
        isResolving = true
        resolveService(next)
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        try {
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    AppLog.w(TAG, "Resolve failed for ${info.serviceName}: errorCode=$errorCode")
                    isResolving = false
                    processResolveQueue()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host
                    val port = info.port
                    val hostAddress = host?.hostAddress
                    if (hostAddress == null) {
                        AppLog.w(TAG, "Resolved ${info.serviceName} but hostAddress is null, skipping")
                        isResolving = false
                        processResolveQueue()
                        return
                    }

                    // Format IPv6 addresses with brackets for URL construction
                    val formattedHost = if (host is Inet6Address) {
                        "[${hostAddress.split("%").first()}]"
                    } else {
                        hostAddress
                    }

                    val url = "http://$formattedHost:$port"
                    val server = DiscoveredServer(
                        serviceName = info.serviceName,
                        host = formattedHost,
                        port = port,
                        url = url
                    )

                    AppLog.d(TAG, "Resolved: ${server.serviceName} → $url")

                    _discoveredServers.update { servers ->
                        // Replace if same service name already present, otherwise add
                        val existing = servers.indexOfFirst { it.serviceName == server.serviceName }
                        if (existing >= 0) {
                            servers.toMutableList().apply { this[existing] = server }
                        } else {
                            servers + server
                        }
                    }

                    isResolving = false
                    processResolveQueue()
                }
            })
        } catch (e: Exception) {
            AppLog.e(TAG, "Error resolving service: ${e.message}", e)
            isResolving = false
            processResolveQueue()
        }
    }
}
