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
    private val activeConnections = ConcurrentHashMap<String, PooledConnection>()
    private val connectionPool = ConcurrentHashMap<String, MutableList<PooledConnection>>()
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
            // Try to reuse existing connection
            val existingConnection = findAvailableConnection(url)
            if (existingConnection != null) {
                existingConnection.lastUsed = System.currentTimeMillis()
                existingConnection.isInUse = true
                existingConnection.useCount++
                return existingConnection.client
            }
            
            // Create new connection
            val newConnection = PooledConnection(
                client = factory(url),
                url = url,
                createdAt = System.currentTimeMillis(),
                lastUsed = System.currentTimeMillis()
            )
            
            activeConnections[url] = newConnection
            newConnection.isInUse = true
            newConnection.useCount++
            
            return newConnection.client
        }
    }
    
    private fun findAvailableConnection(url: String): PooledConnection? {
        // Check active connections first
        val active = activeConnections[url]
        if (active != null && !active.isInUse && isConnectionHealthy(active)) {
            return active
        }
        
        // Check pool
        val pool = connectionPool[url]
        if (pool != null) {
            val available = pool.find { !it.isInUse && isConnectionHealthy(it) }
            if (available != null) {
                // Move to active
                activeConnections[url] = available
                pool.remove(available)
                return available
            }
        }
        
        return null
    }
    
    private fun isConnectionHealthy(connection: PooledConnection): Boolean {
        val age = System.currentTimeMillis() - connection.createdAt
        val idleTime = System.currentTimeMillis() - connection.lastUsed
        
        return age < connectionTimeoutMs && idleTime < connectionTimeoutMs / 2
    }
    
    suspend fun releaseConnection(url: String) {
        connectionMutex.withLock {
            val connection = activeConnections[url]
            if (connection != null) {
                connection.isInUse = false
                connection.lastUsed = System.currentTimeMillis()
                
                // Move to pool if under limit
                val pool = connectionPool.getOrPut(url) { mutableListOf() }
                if (pool.size < maxPoolSize) {
                    pool.add(connection)
                    activeConnections.remove(url)
                } else {
                    // Close excess connection
                    try {
                        connection.client.close()
                    } catch (e: Exception) {
                        // Ignore cleanup errors
                    }
                    activeConnections.remove(url)
                }
            }
        }
    }
    
    private suspend fun cleanupIdleConnections() {
        connectionMutex.withLock {
            val now = System.currentTimeMillis()
            
            // Clean up pool
            connectionPool.values.forEach { pool ->
                val iterator = pool.iterator()
                while (iterator.hasNext()) {
                    val connection = iterator.next()
                    if (now - connection.lastUsed > connectionTimeoutMs) {
                        try {
                            connection.client.close()
                        } catch (e: Exception) {
                            // Ignore
                        }
                        iterator.remove()
                    }
                }
            }
            
            // Clean up active connections that are too old
            activeConnections.values.removeIf { connection ->
                val shouldRemove = now - connection.createdAt > connectionTimeoutMs * 2
                if (shouldRemove) {
                    try {
                        connection.client.close()
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
                shouldRemove
            }
        }
    }
    
    fun getPoolStats(): Map<String, Any> {
        return mapOf(
            "active_connections" to activeConnections.size,
            "pooled_connections" to connectionPool.values.sumOf { it.size },
            "total_connections" to (activeConnections.size + connectionPool.values.sumOf { it.size }),
            "pool_utilization" to connectionPool.mapValues { (_, pool) -> 
                mapOf(
                    "size" to pool.size,
                    "max_size" to maxPoolSize
                )
            }
        )
    }
    
    suspend fun clearPool() {
        connectionMutex.withLock {
            // Close all active connections
            activeConnections.values.forEach { connection ->
                try {
                    connection.client.close()
                } catch (e: Exception) {
                    // Ignore
                }
            }
            activeConnections.clear()
            
            // Close all pooled connections
            connectionPool.values.forEach { pool ->
                pool.forEach { connection ->
                    try {
                        connection.client.close()
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
            connectionPool.clear()
        }
        
        poolScope?.cancel()
        poolScope = null
    }
    
    fun cleanup() {
        poolScope?.cancel()
        poolScope = null
    }
}
