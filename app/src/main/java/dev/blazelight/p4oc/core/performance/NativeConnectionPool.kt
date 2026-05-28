package dev.blazelight.p4oc.core.performance

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object NativeConnectionPool {

    @Volatile private var loadAttempted = false
    @Volatile private var nativeAvailable = false
    private val nextHandle = AtomicLong(1)
    private val pools = ConcurrentHashMap<Long, KotlinConnectionPool>()

    @Synchronized fun ensureLoaded(): Boolean {
        if (loadAttempted) return nativeAvailable
        loadAttempted = true
        return try {
            System.loadLibrary("p4oc_pool")
            nativeAvailable = true
            true
        } catch (_: Throwable) {
            nativeAvailable = false
            false
        }
    }

    fun create(): Long {
        if (ensureLoaded()) return nativeCreate()
        val h = nextHandle.getAndIncrement()
        pools[h] = KotlinConnectionPool()
        return h
    }

    fun destroy(handle: Long) {
        if (ensureLoaded()) { nativeDestroy(handle); return }
        pools.remove(handle)
    }

    fun acquire(handle: Long, url: String): Int {
        if (ensureLoaded()) return nativeAcquire(handle, url)
        return pools[handle]?.acquire(url) ?: -1
    }

    fun register(handle: Long, url: String, clientId: Int): Int {
        if (ensureLoaded()) return nativeRegister(handle, url, clientId)
        return pools[handle]?.register(url, clientId) ?: -1
    }

    fun release(handle: Long, slotId: Int): Boolean {
        if (ensureLoaded()) return nativeRelease(handle, slotId)
        return pools[handle]?.release(slotId) ?: false
    }

    fun evictStale(handle: Long): IntArray {
        if (ensureLoaded()) return nativeEvictStale(handle)
        return pools[handle]?.evictStale() ?: IntArray(0)
    }

    fun isHealthy(handle: Long, slotId: Int): Boolean {
        if (ensureLoaded()) return nativeIsHealthy(handle, slotId)
        return pools[handle]?.isHealthy(slotId) ?: false
    }

    fun getStats(handle: Long): FloatArray {
        if (ensureLoaded()) return nativeGetStats(handle)
        return pools[handle]?.getStats() ?: floatArrayOf(0f, 0f, 0f, 0f, 0f)
    }

    fun clear(handle: Long): IntArray {
        if (ensureLoaded()) return nativeClear(handle)
        return pools[handle]?.clear() ?: IntArray(0)
    }

    // ── Fallback type ─────────────────────────────────────────────────────────

    private class KotlinConnectionPool {
        data class Slot(
            val url: String,
            val clientId: Int,
            var inUse: Boolean = false,
            var createdAt: Long = System.currentTimeMillis(),
            var lastUsed: Long = System.currentTimeMillis(),
            var healthy: Boolean = true
        )

        private val slots = mutableListOf<Slot>()
        private var slotCounter = 0
        private var totalCreated = 0L
        private var totalEvicted = 0L

        @Synchronized fun acquire(url: String): Int {
            val now = System.currentTimeMillis()
            for (i in slots.indices) {
                val s = slots[i]
                if (!s.inUse && s.healthy && s.url == url && (now - s.lastUsed) < 60_000) {
                    s.inUse = true
                    s.lastUsed = now
                    return i
                }
            }
            return -1
        }

        @Synchronized fun register(url: String, clientId: Int): Int {
            val id = slotCounter++
            slots.add(Slot(url = url, clientId = clientId, inUse = true))
            totalCreated++
            return id
        }

        @Synchronized fun release(slotId: Int): Boolean {
            if (slotId < 0 || slotId >= slots.size) return false
            val s = slots[slotId]
            s.inUse = false
            s.lastUsed = System.currentTimeMillis()
            if (slots.size > 10) {
                slots.removeAt(slotId)
                return false
            }
            return true
        }

        @Synchronized fun evictStale(): IntArray {
            val now = System.currentTimeMillis()
            val stale = mutableListOf<Int>()
            val it = slots.iterator()
            while (it.hasNext()) {
                val s = it.next()
                if (!s.inUse && (now - s.lastUsed) > 120_000) {
                    stale.add(s.clientId)
                    it.remove()
                    totalEvicted++
                }
            }
            return stale.toIntArray()
        }

        @Synchronized fun isHealthy(slotId: Int): Boolean {
            if (slotId < 0 || slotId >= slots.size) return false
            return slots[slotId].healthy
        }

        @Synchronized fun getStats(): FloatArray {
            val active = slots.count { it.inUse }
            val pooled = slots.size - active
            val reuseRate = if (totalCreated > 0) {
                ((totalCreated - totalEvicted).toFloat() / totalCreated) * 100f
            } else 100f
            return floatArrayOf(
                active.toFloat(),
                pooled.toFloat(),
                totalCreated.toFloat(),
                totalEvicted.toFloat(),
                reuseRate
            )
        }

        @Synchronized fun clear(): IntArray {
            val ids = slots.map { it.clientId }.toIntArray()
            slots.clear()
            return ids
        }
    }

    // ── JNI ───────────────────────────────────────────────────────────────────

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
