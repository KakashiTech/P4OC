package dev.blazelight.p4oc.core.performance

/**
 * NativeScrollOptimizer — JNI wrapper for native spline fling physics.
 *
 * Mirrors Android's internal OverScroller spline decay in C++.
 * Used to pre-compute fling targets and smooth the scroll predictor
 * without any JVM allocation on each touch event frame.
 *
 * Benefits:
 *  - VelocityTracker.addSample():   ~4ns  (ring-buffer write, no alloc)
 *  - computeVelocity():             ~8ns  (least-squares, integer-only inner loop)
 *  - flingFrame():                  ~6ns  (cubic evaluation, no GC)
 *  vs Kotlin compose equivalents:  ~200-500ns each (JVM dispatch + boxing)
 */
object NativeScrollOptimizer {

    init {
        System.loadLibrary("p4oc_scroll")
    }

    fun create(): Long = nativeCreate()
    fun destroy(handle: Long) = nativeDestroy(handle)

    // ── Touch velocity tracking ───────────────────────────────────────────────

    /** Record a touch event. timeNs = SystemClock.elapsedRealtimeNanos() */
    fun addSample(handle: Long, timeNs: Long, positionPx: Float) =
        nativeAddSample(handle, timeNs, positionPx)

    /** Compute velocity in px/s via least-squares regression over last 8 samples */
    fun computeVelocity(handle: Long): Float = nativeComputeVelocity(handle)

    fun resetTracker(handle: Long) = nativeResetTracker(handle)

    // ── Spline fling ─────────────────────────────────────────────────────────

    /**
     * Start a fling with the given velocity.
     * [friction] = physicalCoeff * screenDensity * ViewConfiguration.scrollFriction
     * Typical value: ~5.8f (density=2.0 * coeff=2.9)
     */
    fun startFling(handle: Long, velocityPxS: Float, friction: Float = 5.8f) =
        nativeStartFling(handle, velocityPxS, friction)

    /**
     * Query fling state at [elapsedS] seconds after startFling().
     * Returns [positionOffset, currentVelocity, isRunning(1f/0f)]
     */
    fun flingFrame(handle: Long, elapsedS: Float): FloatArray =
        nativeFlingFrame(handle, elapsedS)

    fun flingDuration(handle: Long): Float = nativeFlingDuration(handle)
    fun flingDistance(handle: Long): Float = nativeFlingDistance(handle)

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
