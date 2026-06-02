package dev.blazelight.p4oc.core.performance

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

class NativeFlingBehavior(
    private val handle: Long,
    private val density: Float,
) : FlingBehavior {

    private val friction: Float = 386.294f * density * 0.015f * 0.52f

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (!NativeScrollOptimizer.ensureLoaded()) {
            return fallbackPerformFling(initialVelocity)
        }
        if (abs(initialVelocity) < 1f) return 0f

        NativeScrollOptimizer.startFling(handle, initialVelocity, friction)
        val durationS = NativeScrollOptimizer.flingDuration(handle)
        if (durationS <= 0f) return 0f

        var prevPosPx   = 0f
        var lastVelocity = initialVelocity
        var startNs     = 0L

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
                    if (abs(consumed) < abs(delta) * 0.5f) running = false
                }
                prevPosPx = newPos
                if (!alive) running = false
            }
        }

        NativeScrollOptimizer.resetTracker(handle)
        return lastVelocity
    }

    private suspend fun ScrollScope.fallbackPerformFling(initialVelocity: Float): Float {
        if (abs(initialVelocity) < 1f) return 0f
        var velocity = initialVelocity
        withFrameNanos { }
        while (abs(velocity) > 0.5f) {
            withFrameNanos { frameNs ->
                val delta = velocity * 0.016f
                if (abs(delta) > 0f) {
                    val consumed = scrollBy(delta)
                    if (abs(consumed) < abs(delta) * 0.5f) {
                        velocity = 0f
                        return@withFrameNanos
                    }
                }
                velocity *= 0.92f
            }
        }
        return velocity
    }
}

@Composable
fun rememberNativeFlingBehavior(handle: Long): FlingBehavior {
    val density = LocalDensity.current.density
    return remember(handle, density) { NativeFlingBehavior(handle, density) }
}
