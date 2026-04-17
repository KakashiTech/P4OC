package dev.blazelight.p4oc.core.network

/**
 * Native C++ support for OpenCodeEventSource (SSE).
 *
 * Provides:
 *  1. EventBuffer  — lock-free SPSC ring buffer for raw SSE event JSON strings
 *  2. SseBackoff   — exponential backoff + jitter calculator matching Kotlin logic exactly
 *
 * Benefits:
 *  - EventBuffer push: ~50ns vs coroutine emit overhead
 *  - SseBackoff: xorshift64 jitter, zero allocation, no Random overhead
 */
object NativeSseSupport {

    init {
        System.loadLibrary("p4oc_sse")
    }

    // ── EventBuffer ───────────────────────────────────────────────────────────

    fun createBuffer(capacity: Int = 256): Long = nativeBufferCreate(capacity)
    fun destroyBuffer(handle: Long) = nativeBufferDestroy(handle)

    /** Push raw SSE event JSON. Returns false if buffer full. */
    fun pushEvent(handle: Long, data: String): Boolean = nativeBufferPush(handle, data)

    /** Drain up to [max] events. Call from consumer/emit coroutine. */
    fun drainEvents(handle: Long, max: Int = 32): Array<String> = nativeBufferDrain(handle, max)

    /** [size, totalPushed, totalDropped] */
    fun bufferStats(handle: Long): LongArray = nativeBufferStats(handle)

    fun clearBuffer(handle: Long) = nativeBufferClear(handle)

    // ── SseBackoff ────────────────────────────────────────────────────────────

    fun createBackoff(): Long = nativeBackoffCreate()
    fun destroyBackoff(handle: Long) = nativeBackoffDestroy(handle)

    /**
     * Compute retry delay in ms for [consecutiveErrors].
     * Mirrors OpenCodeEventSource.computeRetryDelayMs() exactly:
     *   tier = min(errors/2, 5), base = min(2000 << tier, 60000), ±20% jitter
     */
    fun computeDelayMs(handle: Long, consecutiveErrors: Int): Long =
        nativeBackoffComputeDelay(handle, consecutiveErrors)

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
