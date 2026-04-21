package dev.blazelight.p4oc.core.performance

import dev.blazelight.p4oc.core.network.PtyWebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

object ConnectionPool {
    private val connectionMutex = Mutex()
    // Native registry tracks metadata (timestamps, health, stats) — no GC pressure
    private val registry: Long = NativeConnectionPool.create()
    // Kotlin map for client objects keyed by native slotId
    private val clientMap = ConcurrentHashMap<Int, PooledConnection>()
    // URL → slotId for quick lookup on acquire
    private val urlToSlot = ConcurrentHashMap<String, Int>()
    private val maxPoolSize = 5
    private val connectionTimeoutMs = 30000L
    private var poolScope: CoroutineScope? = null
    
    data class PooledConnection(
        val client: PtyWebSocketClient,
        val url: String,
        val createdAt: Long,
        var lastUsed: Long,
        var isInUse: Boolean = false,
        var useCount: Int = 0
    )
    
    suspend fun initialize() {
        if (poolScope != null) return
        
        poolScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        poolScope?.launch {
            while (isActive) {
                cleanupIdleConnections()
                delay(60000) // Cleanup every minute
            }
        }
    }
    
    suspend fun getConnection(url: String, factory: (String) -> PtyWebSocketClient): PtyWebSocketClient {
        connectionMutex.withLock {
            // Ask native registry for a healthy idle slot
            val slotId = NativeConnectionPool.acquire(registry, url)
            if (slotId >= 0) {
                val conn = clientMap[slotId]
                if (conn != null) {
                    conn.isInUse = true
                    conn.useCount++
                    conn.lastUsed = System.currentTimeMillis()
                    return conn.client
                }
            }

            // Create new connection and register in native registry
            val client = factory(url)
            val newConn = PooledConnection(
                client = client,
                url = url,
                createdAt = System.currentTimeMillis(),
                lastUsed = System.currentTimeMillis(),
                isInUse = true,
                useCount = 1
            )
            val nativeSlot = NativeConnectionPool.register(
                registry, url, System.identityHashCode(client))
            clientMap[nativeSlot] = newConn
            urlToSlot[url] = nativeSlot
            return newConn.client
        }
    }
    
    private fun isConnectionHealthy(slotId: Int): Boolean =
        NativeConnectionPool.isHealthy(registry, slotId)
    
    suspend fun releaseConnection(url: String) {
        connectionMutex.withLock {
            val slotId = urlToSlot[url] ?: return@withLock
            val conn = clientMap[slotId] ?: return@withLock
            conn.isInUse = false
            conn.lastUsed = System.currentTimeMillis()

            // Native registry decides: retain or evict (pool size enforced natively)
            val retained = NativeConnectionPool.release(registry, slotId)
            if (!retained) {
                clientMap.remove(slotId)
                urlToSlot.remove(url)
                try { conn.client.close() } catch (_: Exception) { }
            }
        }
    }
    
    private suspend fun cleanupIdleConnections() {
        connectionMutex.withLock {
            // Native registry returns clientIds of stale slots
            val staleIds = NativeConnectionPool.evictStale(registry)
            for (clientId in staleIds) {
                // Find slot by clientId (identity hash)
                val iter = clientMap.iterator()
                while (iter.hasNext()) {
                    val (slotId, conn) = iter.next()
                    if (System.identityHashCode(conn.client) == clientId) {
                        iter.remove()
                        urlToSlot.remove(conn.url)
                        try { conn.client.close() } catch (_: Exception) { }
                        break
                    }
                }
            }
        }
    }
    
    fun getPoolStats(): Map<String, Any> {
        val s = NativeConnectionPool.getStats(registry)
        return mapOf(
            "active_connections"  to s[0].toInt(),
            "pooled_connections"  to s[1].toInt(),
            "total_created"       to s[2].toInt(),
            "total_evicted"       to s[3].toInt(),
            "reuse_rate_pct"      to s[4]
        )
    }
    
    suspend fun clearPool() {
        connectionMutex.withLock {
            val allIds = NativeConnectionPool.clear(registry)
            for (clientId in allIds) {
                val iter = clientMap.iterator()
                while (iter.hasNext()) {
                    val (_, conn) = iter.next()
                    if (System.identityHashCode(conn.client) == clientId) {
                        iter.remove()
                        try { conn.client.close() } catch (_: Exception) { }
                        break
                    }
                }
            }
            clientMap.clear()
            urlToSlot.clear()
        }
        poolScope?.cancel()
        poolScope = null
    }

    fun cleanup() {
        poolScope?.cancel()
        poolScope = null
        NativeConnectionPool.destroy(registry)
    }
}
