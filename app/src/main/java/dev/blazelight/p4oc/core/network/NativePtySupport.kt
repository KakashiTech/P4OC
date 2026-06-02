package dev.blazelight.p4oc.core.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

object NativePtySupport {

    @Volatile private var loadAttempted = false
    @Volatile private var nativeAvailable = false
    private val nextHandle = AtomicLong(1)
    private val buffers = ConcurrentHashMap<Long, PtyOutputBuffer>()
    private val reconnects = ConcurrentHashMap<Long, PtyReconnectState>()

    @Synchronized fun ensureLoaded(): Boolean {
        if (loadAttempted) return nativeAvailable
        loadAttempted = true
        return try {
            System.loadLibrary("p4oc_pty")
            nativeAvailable = true
            true
        } catch (_: Throwable) {
            nativeAvailable = false
            false
        }
    }

    // ── OutputBuffer ──────────────────────────────────────────────────────────

    fun createBuffer(capacity: Int = 1024): Long {
        if (ensureLoaded()) return nativeBufferCreate(capacity)
        val h = nextHandle.getAndIncrement()
        buffers[h] = PtyOutputBuffer(capacity)
        return h
    }

    fun destroyBuffer(handle: Long) {
        if (ensureLoaded()) { nativeBufferDestroy(handle); return }
        buffers.remove(handle)
    }

    fun pushFrame(handle: Long, text: String): Boolean {
        if (ensureLoaded()) return nativeBufferPush(handle, text)
        return buffers[handle]?.push(text) ?: false
    }

    fun drainFrames(handle: Long, max: Int = 64): Array<String> {
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

    // ── ReconnectionManager ───────────────────────────────────────────────────

    fun createReconnect(): Long {
        if (ensureLoaded()) return nativeReconnectCreate()
        val h = nextHandle.getAndIncrement()
        reconnects[h] = PtyReconnectState()
        return h
    }

    fun destroyReconnect(handle: Long) {
        if (ensureLoaded()) { nativeReconnectDestroy(handle); return }
        reconnects.remove(handle)
    }

    fun onConnected(handle: Long) {
        if (ensureLoaded()) { nativeReconnectOnConnected(handle); return }
        reconnects[handle]?.reset()
    }

    fun nextDelayMs(handle: Long): Long {
        if (ensureLoaded()) return nativeReconnectNextDelay(handle)
        return reconnects[handle]?.nextDelayMs() ?: 0L
    }

    fun shouldRetry(handle: Long): Boolean {
        if (ensureLoaded()) return nativeReconnectShouldRetry(handle)
        return reconnects[handle]?.shouldRetry() ?: false
    }

    fun resetReconnect(handle: Long) {
        if (ensureLoaded()) { nativeReconnectReset(handle); return }
        reconnects[handle]?.reset()
    }

    fun attempts(handle: Long): Int {
        if (ensureLoaded()) return nativeReconnectAttempts(handle)
        return reconnects[handle]?.attempts ?: 0
    }

    // ── Fallback types ────────────────────────────────────────────────────────

    private class PtyOutputBuffer(val capacity: Int) {
        private val frames = ArrayDeque<String>(capacity)
        var totalReceived = 0L
        var totalDropped = 0L

        fun push(text: String): Boolean = synchronized(frames) {
            if (frames.size >= capacity) { totalDropped++; false }
            else { frames.addLast(text); totalReceived++; true }
        }

        fun drain(max: Int): Array<String> = synchronized(frames) {
            val count = minOf(max, frames.size)
            Array(count) { frames.removeFirst() }
        }

        fun clear() = synchronized(frames) { frames.clear() }

        fun stats(): LongArray = synchronized(frames) {
            longArrayOf(frames.size.toLong(), totalReceived, totalDropped)
        }
    }

    private class PtyReconnectState {
        var attempts = 0
            private set

        fun nextDelayMs(): Long {
            if (attempts >= 5) return -1L
            val base = minOf(1000L shl attempts, 30000L)
            val jitterRange = (base * 0.2f).toLong()
            val jitter = Random.nextLong(-jitterRange, jitterRange + 1)
            attempts++
            return (base + jitter).coerceAtLeast(0L)
        }

        fun shouldRetry(): Boolean = attempts < 5
        fun reset() { attempts = 0 }
    }

    // ── JNI ───────────────────────────────────────────────────────────────────

    @JvmStatic private external fun nativeBufferCreate(capacity: Int): Long
    @JvmStatic private external fun nativeBufferDestroy(handle: Long)
    @JvmStatic private external fun nativeBufferPush(handle: Long, text: String): Boolean
    @JvmStatic private external fun nativeBufferDrain(handle: Long, max: Int): Array<String>
    @JvmStatic private external fun nativeBufferStats(handle: Long): LongArray
    @JvmStatic private external fun nativeBufferClear(handle: Long)

    @JvmStatic private external fun nativeReconnectCreate(): Long
    @JvmStatic private external fun nativeReconnectDestroy(handle: Long)
    @JvmStatic private external fun nativeReconnectOnConnected(handle: Long)
    @JvmStatic private external fun nativeReconnectNextDelay(handle: Long): Long
    @JvmStatic private external fun nativeReconnectShouldRetry(handle: Long): Boolean
    @JvmStatic private external fun nativeReconnectReset(handle: Long)
    @JvmStatic private external fun nativeReconnectAttempts(handle: Long): Int
}
