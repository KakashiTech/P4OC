package dev.blazelight.p4oc.core.performance

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

/**
 * NativeFlingBehavior — iOS-feel fling for LazyColumn/LazyRow.
 *
 * Delegates every fling frame to the C++ SplineFling engine which mirrors
 * AOSP OverScroller but with iOS-tuned friction (lower = longer glide).
 * No JVM allocation per frame — all physics computed in native code.
 *
 * Friction tuning:
 *   Android default : physicalCoeff(386.294) * density * friction(0.015) ≈ 5.8
 *   iOS feel target : same formula * 0.50 → half friction = 2x longer glide
 */
class NativeFlingBehavior(
    private val handle: Long,
    private val density: Float,
) : FlingBehavior {

    // iOS-tuned: 52% of Android default → noticeably longer, silkier glide
    private val friction: Float = 386.294f * density * 0.015f * 0.52f

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (abs(initialVelocity) < 1f) return 0f

        NativeScrollOptimizer.startFling(handle, initialVelocity, friction)
        val durationS = NativeScrollOptimizer.flingDuration(handle)
        if (durationS <= 0f) return 0f

        var prevPosPx   = 0f
        var lastVelocity = initialVelocity
        var startNs     = 0L

        // withFrameNanos gives us the exact vsync timestamp each frame —
        // identical to how Compose's own AnimationState drives animations.
        withFrameNanos { startNs = it }

        var running = true
        while (running) {
            withFrameNanos { frameNs ->
                val elapsedS = (frameNs - startNs) * 1e-9f
                val r        = NativeScrollOptimizer.flingFrame(handle, elapsedS)
                val newPos   = r[0]
                lastVelocity = r[1]
                val alive    = r[2] > 0f

                val delta    = newPos - prevPosPx
                if (abs(delta) > 0f) {
                    val consumed = scrollBy(delta)
                    // Hit a boundary — stop fling
                    if (abs(consumed) < abs(delta) * 0.5f) running = false
                }
                prevPosPx = newPos
                if (!alive) running = false
            }
        }

        NativeScrollOptimizer.resetTracker(handle)
        return lastVelocity
    }
}

/**
 * Remembers a [NativeFlingBehavior] bound to the given native handle.
 */
@Composable
fun rememberNativeFlingBehavior(handle: Long): FlingBehavior {
    val density = LocalDensity.current.density
    return remember(handle, density) { NativeFlingBehavior(handle, density) }
}
