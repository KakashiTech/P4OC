package dev.blazelight.p4oc.core.performance

object NativeScrollOptimizer {

    @Volatile private var loadAttempted = false
    @Volatile private var nativeAvailable = false

    @Synchronized fun ensureLoaded(): Boolean {
        if (loadAttempted) return nativeAvailable
        loadAttempted = true
        return try {
            System.loadLibrary("p4oc_scroll")
            nativeAvailable = true
            true
        } catch (_: Throwable) {
            nativeAvailable = false
            false
        }
    }

    // ── Handle lifecycle ─────────────────────────────────────────────────────

    private const val INVALID_HANDLE = 0L

    fun create(): Long {
        if (ensureLoaded()) return nativeCreate()
        return INVALID_HANDLE
    }

    fun destroy(handle: Long) {
        if (nativeAvailable && handle != INVALID_HANDLE) nativeDestroy(handle)
    }

    // ── Touch velocity tracking ───────────────────────────────────────────────

    fun addSample(handle: Long, timeNs: Long, positionPx: Float) {
        if (nativeAvailable && handle != INVALID_HANDLE) { nativeAddSample(handle, timeNs, positionPx); return }
        // no-op fallback — velocity tracking not available
    }

    fun computeVelocity(handle: Long): Float {
        if (nativeAvailable && handle != INVALID_HANDLE) return nativeComputeVelocity(handle)
        return 0f
    }

    fun resetTracker(handle: Long) {
        if (nativeAvailable && handle != INVALID_HANDLE) { nativeResetTracker(handle); return }
    }

    // ── Spline fling ─────────────────────────────────────────────────────────

    fun startFling(handle: Long, velocityPxS: Float, friction: Float = 5.8f) {
        if (nativeAvailable && handle != INVALID_HANDLE) { nativeStartFling(handle, velocityPxS, friction); return }
    }

    fun flingFrame(handle: Long, elapsedS: Float): FloatArray {
        if (nativeAvailable && handle != INVALID_HANDLE) return nativeFlingFrame(handle, elapsedS)
        return floatArrayOf(0f, 0f, 0f)
    }

    fun flingDuration(handle: Long): Float {
        if (nativeAvailable && handle != INVALID_HANDLE) return nativeFlingDuration(handle)
        return 0f
    }

    fun flingDistance(handle: Long): Float {
        if (nativeAvailable && handle != INVALID_HANDLE) return nativeFlingDistance(handle)
        return 0f
    }

    // ── JNI ──────────────────────────────────────────────────────────────────

    @JvmStatic private external fun nativeCreate(): Long
    @JvmStatic private external fun nativeDestroy(handle: Long)
    @JvmStatic private external fun nativeAddSample(handle: Long, timeNs: Long, positionPx: Float)
    @JvmStatic private external fun nativeComputeVelocity(handle: Long): Float
    @JvmStatic private external fun nativeResetTracker(handle: Long)
    @JvmStatic private external fun nativeStartFling(handle: Long, velocityPxS: Float, friction: Float)
    @JvmStatic private external fun nativeFlingFrame(handle: Long, elapsedS: Float): FloatArray
    @JvmStatic private external fun nativeFlingDuration(handle: Long): Float
    @JvmStatic private external fun nativeFlingDistance(handle: Long): Float
}
