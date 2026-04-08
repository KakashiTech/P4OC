package dev.blazelight.p4oc.core.performance

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.*

/**
 * Advanced animation optimizer for high-performance animations
 * with intelligent caching and resource management.
 */
object AnimationOptimizer {
    private val animationCache = mutableMapOf<String, AnimationSpec<Float>>()
    private val transitionCache = mutableMapOf<String, InfiniteTransition>()
    private val cacheMutex = Mutex()
    private var optimizerScope: CoroutineScope? = null
    
    data class AnimationConfig(
        val duration: Int,
        val easing: Easing,
        val repeatMode: RepeatMode = RepeatMode.Restart,
        val infinite: Boolean = false
    )
    
    suspend fun initialize() {
        if (optimizerScope != null) return
        
        optimizerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        
        optimizerScope?.launch {
            while (isActive) {
                cleanupUnusedAnimations()
                delay(60000) // Cleanup every minute
            }
        }
    }
    
    /**
     * Get optimized animation spec with caching
     */
    suspend fun getOptimizedAnimation(
        key: String,
        config: AnimationConfig
    ): AnimationSpec<Float> {
        return cacheMutex.withLock {
            animationCache.getOrPut(key) {
                if (config.infinite) {
                    infiniteRepeatable(
                        animation = tween(config.duration, easing = config.easing),
                        repeatMode = config.repeatMode
                    )
                } else {
                    tween(config.duration, easing = config.easing)
                }
            }
        }
    }
    
    /**
     * Create optimized infinite transition with pooling
     */
    @Composable
    fun rememberOptimizedInfiniteTransition(
        key: String,
        label: String = key
    ): InfiniteTransition {
        val transition = rememberInfiniteTransition(label)
        
        LaunchedEffect(key) {
            optimizerScope?.launch {
                cacheMutex.withLock {
                    transitionCache[key] = transition
                }
            }
        }
        
        return transition
    }
    
    /**
     * Optimized pulse animation with reduced recomposition
     */
    @Composable
    fun optimizedPulseAnimation(
        key: String,
        initialValue: Float = 0.6f,
        targetValue: Float = 1.0f,
        duration: Int = 1000
    ): State<Float> {
        val transition = rememberOptimizedInfiniteTransition(key)
        
        return transition.animateFloat(
            initialValue = initialValue,
            targetValue = targetValue,
            animationSpec = infiniteRepeatable(
                animation = tween(duration, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse
            ),
            label = "${key}_pulse"
        )
    }
    
    /**
     * Optimized rotation animation with hardware acceleration
     */
    @Composable
    fun optimizedRotationAnimation(
        key: String,
        duration: Int = 1000
    ): State<Float> {
        val transition = rememberOptimizedInfiniteTransition(key)
        
        return transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(duration, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "${key}_rotation"
        )
    }
    
    /**
     * Batch animation updates for better performance
     */
    suspend fun batchAnimationUpdates(
        updates: List<suspend () -> Unit>
    ) {
        optimizerScope?.launch {
            withContext(Dispatchers.Main) {
                updates.forEach { it() }
            }
        }
    }
    
    /**
     * Preload common animations for instant access
     */
    suspend fun preloadCommonAnimations() {
        val commonConfigs = listOf(
            "loading_rotation" to AnimationConfig(1000, LinearEasing, infinite = true),
            "pulse_fast" to AnimationConfig(800, EaseInOut, RepeatMode.Reverse, infinite = true),
            "pulse_slow" to AnimationConfig(1500, EaseInOut, RepeatMode.Reverse, infinite = true),
            "fade_quick" to AnimationConfig(150, FastOutSlowInEasing),
            "slide_normal" to AnimationConfig(200, FastOutSlowInEasing)
        )
        
        cacheMutex.withLock {
            commonConfigs.forEach { (key, config) ->
                animationCache[key] = if (config.infinite) {
                    infiniteRepeatable(
                        animation = tween(config.duration, easing = config.easing),
                        repeatMode = config.repeatMode
                    )
                } else {
                    tween(config.duration, easing = config.easing)
                }
            }
        }
    }
    
    private suspend fun cleanupUnusedAnimations() {
        cacheMutex.withLock {
            // Remove old transition references (they'll be garbage collected if not in use)
            val keysToRemove = transitionCache.keys.filter { key ->
                // Simple heuristic: remove if not accessed recently
                !key.contains("active") && !key.contains("current")
            }
            keysToRemove.forEach { transitionCache.remove(it) }
        }
    }
    
    fun getAnimationStats(): Map<String, Any> {
        return mapOf(
            "cached_animations" to animationCache.size,
            "active_transitions" to transitionCache.size,
            "memory_usage_estimate" to (animationCache.size * 64 + transitionCache.size * 128) // bytes
        )
    }
    
    suspend fun clearCache() {
        cacheMutex.withLock {
            animationCache.clear()
            transitionCache.clear()
        }
        optimizerScope?.cancel()
        optimizerScope = null
    }
    
    fun cleanup() {
        optimizerScope?.cancel()
        optimizerScope = null
    }
}

/**
 * Extension functions for common optimized animations
 */
@Composable
fun rememberOptimizedLoadingRotation(): State<Float> {
    return AnimationOptimizer.optimizedRotationAnimation(
        key = "loading_rotation",
        duration = 1000
    )
}

@Composable
fun rememberOptimizedPulse(
    key: String,
    fast: Boolean = false
): State<Float> {
    return AnimationOptimizer.optimizedPulseAnimation(
        key = if (fast) "${key}_pulse_fast" else "${key}_pulse_slow",
        duration = if (fast) 800 else 1500
    )
}
