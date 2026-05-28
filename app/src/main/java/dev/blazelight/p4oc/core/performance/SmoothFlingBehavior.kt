package dev.blazelight.p4oc.core.performance

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

class SmoothFlingBehavior(
    private val density: Float
) : FlingBehavior {

    // iOS exponential decay: friction = 52% of Android default
    private val friction = 386.294f * 0.015f * 0.52f * density

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (abs(initialVelocity) < 1f) return 0f

        val absV = abs(initialVelocity)
        val sign = if (initialVelocity > 0f) 1f else -1f

        val k = friction / absV
        val durationS = ln(absV) / k
        if (durationS <= 0f) return 0f

        val v0OverK = absV / k
        val totalDistance = sign * v0OverK * (1f - exp(-k * durationS))

        var lastPosition = 0f
        var startNs = 0L
        withFrameNanos { startNs = it }

        while (true) {
            withFrameNanos { frameNs ->
                val elapsedS = (frameNs - startNs) * 1e-9f
                if (elapsedS >= durationS) {
                    val remaining = totalDistance - lastPosition
                    if (abs(remaining) > 0.5f) scrollBy(remaining)
                    return@withFrameNanos
                }
                val pos = sign * v0OverK * (1f - exp(-k * elapsedS))
                val delta = pos - lastPosition
                if (abs(delta) > 0.5f) {
                    val consumed = scrollBy(delta)
                    if (abs(consumed) < abs(delta) * 0.5f) return@withFrameNanos
                }
                lastPosition = pos
            }
        }
    }
}

@Composable
fun rememberSmoothFlingBehavior(): FlingBehavior {
    val density = LocalDensity.current.density
    return remember(density) { SmoothFlingBehavior(density) }
}
