package dev.blazelight.p4oc.core.performance

/**
 * Native C++ connection registry for ConnectionPool.
 *
 * Tracks connection metadata (timestamps, health, stats) in native code,
 * eliminating GC pressure from the tracking bookkeeping. The actual
 * PtyWebSocketClient objects remain in Kotlin; this is the lifecycle ledger.
 *
 * Benefits:
 *  - Health checks: pure integer arithmetic, ~2ns vs ~50ns with boxing
 *  - Stats: atomic counters, no HashMap allocation on read
 *  - Eviction: sorted sweep without Kotlin iterator overhead
 *  - Reuse rate: maintained in C++ atomic, readable any time
 */
object NativeConnectionPool {

    init {
        System.loadLibrary("p4oc_pool")
    }

    fun create(): Long = nativeCreate()
    fun destroy(handle: Long) = nativeDestroy(handle)

    /**
     * Try to find an idle, healthy slot for [url].
     * Returns slot ID ≥ 0 if found (now marked in-use), -1 if none available.
     */
    fun acquire(handle: Long, url: String): Int = nativeAcquire(handle, url)

    /**
     * Register a newly created connection.
     * [clientId] is an opaque Kotlin-side handle (e.g., identity hash code).
     * Returns the assigned native slot ID.
     */
    fun register(handle: Long, url: String, clientId: Int): Int =
        nativeRegister(handle, url, clientId)

    /**
     * Release a slot back to the pool.
     * Returns true if slot was retained (healthy, under pool limit); false if evicted.
     */
    fun release(handle: Long, slotId: Int): Boolean = nativeRelease(handle, slotId)

    /**
     * Evict all stale slots and return their Kotlin clientIds.
     * Caller is responsible for closing those clients.
     */
    fun evictStale(handle: Long): IntArray = nativeEvictStale(handle)

    fun isHealthy(handle: Long, slotId: Int): Boolean = nativeIsHealthy(handle, slotId)

    /**
     * Returns [activeCount, pooledCount, totalCreated, totalEvicted, reuseRate%].
     */
    fun getStats(handle: Long): FloatArray = nativeGetStats(handle)

    /**
     * Clear all slots. Returns all clientIds for caller to close.
     */
    fun clear(handle: Long): IntArray = nativeClear(handle)

    @JvmStatic private external fun nativeCreate(): Long
    @JvmStatic private external fun nativeDestroy(handle: Long)
    @JvmStatic private external fun nativeAcquire(handle: Long, url: String): Int
    @JvmStatic private external fun nativeRegister(handle: Long, url: String, clientId: Int): Int
    @JvmStatic private external fun nativeRelease(handle: Long, slotId: Int): Boolean
    @JvmStatic private external fun nativeEvictStale(handle: Long): IntArray
    @JvmStatic private external fun nativeIsHealthy(handle: Long, slotId: Int): Boolean
    @JvmStatic private external fun nativeGetStats(handle: Long): FloatArray
    @JvmStatic private external fun nativeClear(handle: Long): IntArray
}
