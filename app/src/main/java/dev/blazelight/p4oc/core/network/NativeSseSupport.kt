package dev.blazelight.p4oc.core.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

object NativeSseSupport {

    @Volatile private var loadAttempted = false
    @Volatile private var nativeAvailable = false
    private val nextHandle = AtomicLong(1)
    private val buffers = ConcurrentHashMap<Long, SseEventBuffer>()
    private val backoffs = ConcurrentHashMap<Long, SseBackoffState>()

    @Synchronized fun ensureLoaded(): Boolean {
        if (loadAttempted) return nativeAvailable
        loadAttempted = true
        return try {
            System.loadLibrary("p4oc_sse")
            nativeAvailable = true
            true
        } catch (_: Throwable) {
            nativeAvailable = false
            false
        }
    }

    // ── EventBuffer ───────────────────────────────────────────────────────────

    fun createBuffer(capacity: Int = 256): Long {
        if (ensureLoaded()) return nativeBufferCreate(capacity)
        val h = nextHandle.getAndIncrement()
        buffers[h] = SseEventBuffer(capacity)
        return h
    }

    fun destroyBuffer(handle: Long) {
        if (ensureLoaded()) { nativeBufferDestroy(handle); return }
        buffers.remove(handle)
        backoffs.remove(handle)
    }

    fun pushEvent(handle: Long, data: String): Boolean {
        if (ensureLoaded()) return nativeBufferPush(handle, data)
        return buffers[handle]?.push(data) ?: false
    }

    fun drainEvents(handle: Long, max: Int = 32): Array<String> {
        if (ensureLoaded()) return nativeBufferDrain(handle, max)
        return buffers[handle]?.drain(max) ?: emptyArray()
    }

    fun bufferStats(handle: Long): LongArray {
        if (ensureLoaded()) return nativeBufferStats(handle)
        return buffers[handle]?.stats() ?: longArrayOf(0, 0, 0)
    }

    fun clearBuffer(handle: Long) {
        if (ensureLoaded()) { nativeBufferClear(handle); return }
        buffers[handle]?.clear()
    }

    // ── SseBackoff ────────────────────────────────────────────────────────────

    fun createBackoff(): Long {
        if (ensureLoaded()) return nativeBackoffCreate()
        val h = nextHandle.getAndIncrement()
        backoffs[h] = SseBackoffState()
        return h
    }

    fun destroyBackoff(handle: Long) {
        if (ensureLoaded()) { nativeBackoffDestroy(handle); return }
        backoffs.remove(handle)
    }

    fun computeDelayMs(handle: Long, consecutiveErrors: Int): Long {
        if (ensureLoaded()) return nativeBackoffComputeDelay(handle, consecutiveErrors)
        return backoffs[handle]?.computeDelayMs(consecutiveErrors) ?: 0L
    }

    // ── Fallback types ────────────────────────────────────────────────────────

    private class SseEventBuffer(val capacity: Int) {
        private val events = ArrayDeque<String>(capacity)
        var totalPushed = 0L
        var totalDropped = 0L

        fun push(data: String): Boolean = synchronized(events) {
            if (events.size >= capacity) { totalDropped++; false }
            else { events.addLast(data); totalPushed++; true }
        }

        fun drain(max: Int): Array<String> = synchronized(events) {
            val count = minOf(max, events.size)
            Array(count) { events.removeFirst() }
        }

        fun clear() = synchronized(events) { events.clear() }

        fun stats(): LongArray = synchronized(events) {
            longArrayOf(events.size.toLong(), totalPushed, totalDropped)
        }
    }

    private class SseBackoffState {
        fun computeDelayMs(consecutiveErrors: Int): Long {
            val tier = minOf(consecutiveErrors / 2, 5)
            val base = minOf(2000L shl tier, 60000L)
            val jitterRange = (base * 0.2f).toLong()
            val jitter = Random.nextLong(-jitterRange, jitterRange + 1)
            return (base + jitter).coerceAtLeast(0L)
        }
    }

    // ── JNI ───────────────────────────────────────────────────────────────────

    @JvmStatic private external fun nativeBufferCreate(capacity: Int): Long
    @JvmStatic private external fun nativeBufferDestroy(handle: Long)
    @JvmStatic private external fun nativeBufferPush(handle: Long, data: String): Boolean
    @JvmStatic private external fun nativeBufferDrain(handle: Long, max: Int): Array<String>
    @JvmStatic private external fun nativeBufferStats(handle: Long): LongArray
    @JvmStatic private external fun nativeBufferClear(handle: Long)

    @JvmStatic private external fun nativeBackoffCreate(): Long
    @JvmStatic private external fun nativeBackoffDestroy(handle: Long)
    @JvmStatic private external fun nativeBackoffComputeDelay(handle: Long, consecutiveErrors: Int): Long
}
