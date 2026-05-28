package dev.blazelight.p4oc.core.performance

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.*

object NativeAnimationOptimizer {

    @Volatile private var loadAttempted = false
    @Volatile private var nativeAvailable = false
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val animationMutex = Mutex()
    private val animationCache = mutableMapOf<String, Long>()
    private val transitionCache = mutableMapOf<String, InfiniteTransition>()
    private val startTime = System.currentTimeMillis()

    @Synchronized fun ensureLoaded(): Boolean {
        if (loadAttempted) return nativeAvailable
        loadAttempted = true
        return try {
            System.loadLibrary("p4oc_animation")
            nativeInitialize()
            nativeAvailable = true
            true
        } catch (_: Throwable) {
            nativeAvailable = false
            false
        }
    }

    enum class EasingType(val value: Int) {
        LINEAR(0),
        EASE_IN_OUT(1),
        FAST_OUT_SLOW_IN(2),
        EASE_OUT(3),
        EASE_IN(4),
        ELASTIC(5)
    }

    data class AnimationConfig(
        val duration: Int,
        val easing: EasingType = EasingType.LINEAR,
        val repeatMode: RepeatMode = RepeatMode.Restart,
        val infinite: Boolean = false
    )

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    suspend fun initialize() {
        if (ensureLoaded()) {
            coroutineScope.launch {
                while (isActive) {
                    delay(60000)
                    cleanupUnusedAnimations()
                }
            }
        }
    }

    // ── Animation handle management ───────────────────────────────────────────

    suspend fun getAnimationHandle(key: String, config: AnimationConfig): Long {
        if (ensureLoaded()) {
            return animationMutex.withLock {
                animationCache.getOrPut(key) {
                    val reverse = config.repeatMode == RepeatMode.Reverse
                    nativeGetOrCreateAnimation(
                        key, config.duration, config.easing.value, config.infinite, reverse
                    )
                }
            }
        }
        return -1L
    }

    suspend fun calculateValue(key: String, config: AnimationConfig): Float {
        if (ensureLoaded()) {
            val handle = getAnimationHandle(key, config)
            val elapsed = System.currentTimeMillis() - startTime
            return nativeGetValue(handle, elapsed)
        }
        // Fallback: simple cyclical interpolation
        val elapsed = (System.currentTimeMillis() - startTime) % config.duration
        val t = elapsed.toFloat() / config.duration
        return interpolate(0f, 1f, t, config.easing)
    }

    // ── Composables ───────────────────────────────────────────────────────────

    @Composable
    fun optimizedPulseAnimation(
        key: String,
        initialValue: Float = 0.6f,
        targetValue: Float = 1.0f,
        duration: Int = 1000
    ): State<Float> {
        if (!ensureLoaded()) {
            val infiniteTransition = rememberInfiniteTransition(label = key)
            return infiniteTransition.animateFloat(
                initialValue = initialValue,
                targetValue = targetValue,
                animationSpec = infiniteRepeatable(
                    animation = tween(duration, easing = EaseInOutCubic),
                    repeatMode = RepeatMode.Reverse
                ),
                label = key
            )
        }
        return produceState(initialValue = initialValue, key) {
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                val nativeValue = nativeCalculatePulse(elapsed, duration, EasingType.EASE_IN_OUT.value)
                val scaledValue = initialValue + (targetValue - initialValue) * ((nativeValue - 0.6f) / 0.4f)
                this.value = scaledValue
                delay(16)
            }
        }
    }

    @Composable
    fun optimizedRotationAnimation(
        key: String,
        duration: Int = 1000
    ): State<Float> {
        if (!ensureLoaded()) {
            val infiniteTransition = rememberInfiniteTransition(label = key)
            return infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(duration, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = key
            )
        }
        return produceState(initialValue = 0f, key) {
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                this.value = nativeCalculateRotation(elapsed, duration)
                delay(16)
            }
        }
    }

    @Composable
    fun rememberOptimizedInfiniteTransition(
        key: String,
        label: String = key
    ): InfiniteTransition {
        val transition = rememberInfiniteTransition(label)
        LaunchedEffect(key) {
            animationMutex.withLock {
                transitionCache[key] = transition
            }
        }
        return transition
    }

    // ── Stateless math ────────────────────────────────────────────────────────

    fun interpolate(
        start: Float,
        end: Float,
        progress: Float,
        easing: EasingType = EasingType.LINEAR
    ): Float {
        if (ensureLoaded()) return nativeInterpolate(start, end, progress, easing.value)
        val eased = calculateEasing(progress.coerceIn(0f, 1f), easing)
        return start + (end - start) * eased
    }

    fun calculateEasing(t: Float, easing: EasingType = EasingType.LINEAR): Float {
        if (ensureLoaded()) return nativeCalculateEasing(t, easing.value)
        val x = t.coerceIn(0f, 1f)
        return when (easing) {
            EasingType.LINEAR -> x
            EasingType.EASE_IN_OUT -> if (x < 0.5f) 2f * x * x else 1f - (-2f * x + 2f).pow(2) / 2f
            EasingType.FAST_OUT_SLOW_IN -> x * x * (3f - 2f * x)
            EasingType.EASE_OUT -> 1f - (1f - x) * (1f - x)
            EasingType.EASE_IN -> x * x
            EasingType.ELASTIC -> {
                val c4 = (2f * PI.toFloat()) / 3f
                if (x == 0f || x == 1f) x
                else (-2f * 10f.pow(x - 1f) * sin((x - 1f) * c4)).coerceIn(0f, 1f)
            }
        }
    }

    suspend fun preloadCommonAnimations() {
        if (ensureLoaded()) nativePreloadCommon()
    }

    suspend fun batchAnimationUpdates(updates: List<suspend () -> Unit>) {
        withContext(Dispatchers.Main) {
            updates.forEach { it() }
        }
    }

    suspend fun cleanupUnusedAnimations() {
        if (ensureLoaded()) nativeCleanupUnused()
    }

    fun getAnimationStats(): Map<String, Any> {
        if (ensureLoaded()) {
            @Suppress("UNCHECKED_CAST")
            return nativeGetStats() as Map<String, Any>
        }
        return emptyMap()
    }

    suspend fun clearCache() {
        animationMutex.withLock {
            animationCache.clear()
            transitionCache.clear()
        }
        if (ensureLoaded()) nativeClearCache()
    }

    fun cleanup() {
        coroutineScope.cancel()
    }

    // ── JNI ───────────────────────────────────────────────────────────────────

    @JvmStatic private external fun nativeInitialize()
    @JvmStatic private external fun nativeGetOrCreateAnimation(
        key: String, durationMs: Int, easingType: Int, infinite: Boolean, reverse: Boolean
    ): Long
    @JvmStatic private external fun nativeGetValue(animationPtr: Long, elapsedMs: Long): Float
    @JvmStatic private external fun nativeInterpolate(
        start: Float, end: Float, progress: Float, easingType: Int
    ): Float
    @JvmStatic private external fun nativeCalculatePulse(elapsedMs: Long, durationMs: Int, easingType: Int): Float
    @JvmStatic private external fun nativeCalculateRotation(elapsedMs: Long, durationMs: Int): Float
    @JvmStatic external fun nativeCalculateWithRepeat(
        elapsedMs: Long, durationMs: Int, easingType: Int, reverse: Boolean
    ): Float
    @JvmStatic external fun nativeCalculateEasing(t: Float, easingType: Int): Float

    @JvmStatic fun calculateWithRepeat(elapsedMs: Long, durationMs: Int, easingType: Int, reverse: Boolean): Float {
        if (nativeAvailable) {
            return nativeCalculateWithRepeat(elapsedMs, durationMs, easingType, reverse)
        }
        val t = (elapsedMs % durationMs).toFloat() / durationMs
        return if (reverse) {
            if (t < 0.5f) t * 2f else (1f - t) * 2f
        } else {
            t
        }
    }
    @JvmStatic private external fun nativePreloadCommon()
    @JvmStatic private external fun nativeClearCache()
    @JvmStatic private external fun nativeCleanupUnused()
    @JvmStatic private external fun nativeGetStats(): Map<String, Long>
}

@Composable
fun rememberNativeOptimizedLoadingRotation(): State<Float> {
    return NativeAnimationOptimizer.optimizedRotationAnimation(
        key = "loading_rotation",
        duration = 1000
    )
}

@Composable
fun rememberNativeOptimizedPulse(
    key: String,
    fast: Boolean = false
): State<Float> {
    return NativeAnimationOptimizer.optimizedPulseAnimation(
        key = if (fast) "${key}_pulse_fast" else "${key}_pulse_slow",
        duration = if (fast) 800 else 1500
    )
}
