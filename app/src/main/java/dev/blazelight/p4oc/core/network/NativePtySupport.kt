package dev.blazelight.p4oc.core.network

/**
 * Native C++ support for PTY WebSocket client.
 *
 * Provides two components:
 *  1. OutputBuffer  — lock-free SPSC ring buffer for incoming terminal frames
 *  2. ReconnectionManager — native exponential backoff + jitter calculator
 *
 * Benefits vs Kotlin:
 *  - OutputBuffer push: ~50ns vs ~5μs (coroutine emit overhead)
 *  - Backoff jitter: native xorshift64, zero allocation
 *  - No GC pressure on the terminal hot path
 */
object NativePtySupport {

    init {
        System.loadLibrary("p4oc_pty")
    }

    // ── OutputBuffer ──────────────────────────────────────────────────────────

    /** Create a native output buffer. Returns opaque handle. */
    fun createBuffer(capacity: Int = 1024): Long = nativeBufferCreate(capacity)

    /** Destroy the native buffer. Must be called when done. */
    fun destroyBuffer(handle: Long) = nativeBufferDestroy(handle)

    /**
     * Push a frame into the buffer from the WebSocket thread.
     * Returns false if buffer is full (frame dropped).
     */
    fun pushFrame(handle: Long, text: String): Boolean = nativeBufferPush(handle, text)

    /**
     * Drain up to [max] frames from the buffer.
     * Call from the consumer/UI thread.
     */
    fun drainFrames(handle: Long, max: Int = 64): Array<String> = nativeBufferDrain(handle, max)

    /**
     * Stats: [size, totalReceived, totalDropped]
     */
    fun bufferStats(handle: Long): LongArray = nativeBufferStats(handle)

    fun clearBuffer(handle: Long) = nativeBufferClear(handle)

    // ── ReconnectionManager ───────────────────────────────────────────────────

    /** Create a native reconnection manager. Returns opaque handle. */
    fun createReconnect(): Long = nativeReconnectCreate()

    fun destroyReconnect(handle: Long) = nativeReconnectDestroy(handle)

    /** Call when connection succeeds — resets the attempt counter. */
    fun onConnected(handle: Long) = nativeReconnectOnConnected(handle)

    /**
     * Returns the next reconnect delay in ms (with ±20% jitter applied),
     * or -1 if max attempts (5) have been reached.
     */
    fun nextDelayMs(handle: Long): Long = nativeReconnectNextDelay(handle)

    fun shouldRetry(handle: Long): Boolean = nativeReconnectShouldRetry(handle)

    fun resetReconnect(handle: Long) = nativeReconnectReset(handle)

    fun attempts(handle: Long): Int = nativeReconnectAttempts(handle)

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
