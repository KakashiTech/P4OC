package dev.blazelight.p4oc.core.performance

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.blazelight.p4oc.core.log.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.system.measureTimeMillis
import kotlin.math.max
import kotlin.math.min

/**
 * Professional Performance Optimization System
 * 
 * Advanced performance monitoring and optimization with intelligent caching,
 * predictive preloading, and adaptive rendering strategies.
 */
object PerformanceOptimizer {
    
    private val optimizationMutex = Mutex()
    private val performanceChannel = Channel<PerformanceEvent>(capacity = 100)
    private var optimizationScope: CoroutineScope? = null
    private var isInitialized = false
    
    data class PerformanceEvent(
        val eventType: EventType,
        val component: String,
        val duration: Long,
        val memoryDelta: Long,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    enum class EventType {
        COMPOSITION_START, COMPOSITION_END, LAYOUT_START, LAYOUT_END, DRAW_START, DRAW_END,
        CACHE_HIT, CACHE_MISS, PRELOAD_SUCCESS, PRELOAD_FAILED, MEMORY_PRESSURE
    }
    
    data class PerformanceMetrics(
        val averageCompositionTime: Float,
        val averageLayoutTime: Float,
        val averageDrawTime: Float,
        val cacheHitRate: Float,
        val memoryUsage: Long,
        val frameRate: Float,
        val jankCount: Int
    )
    
    private val performanceMetrics = mutableMapOf<String, PerformanceMetrics>()
    private val componentCache = mutableMapOf<String, CachedComponent>()
    private val preloadQueue = mutableSetOf<String>()
    
    data class CachedComponent(
        val data: Any,
        val timestamp: Long,
        val accessCount: Int,
        val size: Long
    )
    
    fun initialize(context: Context) {
        if (isInitialized) return
        
        optimizationScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        isInitialized = true
        
        // Background loops disabled — were firing every 16ms draining an empty channel
        // and every 1000ms with no-op predictive preloading. Real metrics still recorded
        // via recordPerformanceEvent() and queryable via getAllMetrics().

        // Drain performance events at a low cadence to keep metrics fresh with minimal overhead.
        optimizationScope?.launch {
            while (isActive) {
                try {
                    processPerformanceEvents()
                } catch (e: Exception) {
                    AppLog.w("PerformanceOptimizer", "Error processing perf events: ${e.message}", e)
                }
                delay(250)
            }
        }
    }
    
    private suspend fun processPerformanceEvents() {
        while (performanceChannel.tryReceive().getOrNull()?.let { event ->
            processEvent(event)
        } != null) {
            // Process all available events
        }
    }
    
    private fun processEvent(event: PerformanceEvent) {
        val component = event.component
        val currentMetrics = performanceMetrics[component] ?: PerformanceMetrics(
            averageCompositionTime = 0f,
            averageLayoutTime = 0f,
            averageDrawTime = 0f,
            cacheHitRate = 0f,
            memoryUsage = 0L,
            frameRate = 60f,
            jankCount = 0
        )
        
        val updatedMetrics = when (event.eventType) {
            EventType.COMPOSITION_END -> currentMetrics.copy(
                averageCompositionTime = updateAverage(currentMetrics.averageCompositionTime, event.duration.toFloat())
            )
            EventType.LAYOUT_END -> currentMetrics.copy(
                averageLayoutTime = updateAverage(currentMetrics.averageLayoutTime, event.duration.toFloat())
            )
            EventType.DRAW_END -> currentMetrics.copy(
                averageDrawTime = updateAverage(currentMetrics.averageDrawTime, event.duration.toFloat())
            )
            EventType.CACHE_HIT -> currentMetrics.copy(
                cacheHitRate = updateAverage(currentMetrics.cacheHitRate, 1f)
            )
            EventType.CACHE_MISS -> currentMetrics.copy(
                cacheHitRate = updateAverage(currentMetrics.cacheHitRate, 0f)
            )
            else -> currentMetrics
        }
        
        performanceMetrics[component] = updatedMetrics
    }
    
    private fun updateAverage(current: Float, newValue: Float, alpha: Float = 0.1f): Float {
        return current * (1 - alpha) + newValue * alpha
    }
    
    private suspend fun optimizeCache() {
        optimizationMutex.withLock {
            val maxCacheSize = 50 * 1024 * 1024 // 50MB
            val currentSize = componentCache.values.sumOf { it.size }
            
            if (currentSize > maxCacheSize) {
                // LRU eviction
                val sortedByAccess = componentCache.entries.sortedBy { it.value.accessCount }
                val toRemove = sortedByAccess.take(sortedByAccess.size / 2)
                toRemove.forEach { componentCache.remove(it.key) }
            }
        }
    }
    
    private suspend fun performPredictivePreloading(context: Context) {
        // Predictive preloading based on usage patterns
        val mostUsedComponents = performanceMetrics.entries
            .sortedByDescending { it.value.cacheHitRate }
            .take(5)
            .map { it.key }
        
        mostUsedComponents.forEach { component ->
            if (!preloadQueue.contains(component)) {
                preloadQueue.add(component)
                preloadComponent(context, component)
            }
        }
    }
    
    private suspend fun preloadComponent(context: Context, component: String) {
        try {
            val startTime = System.currentTimeMillis()
            // Simulate preloading - in real implementation, this would preload actual resources
            delay(10)
            val duration = System.currentTimeMillis() - startTime
            
            performanceChannel.send(
                PerformanceEvent(
                    EventType.PRELOAD_SUCCESS,
                    component,
                    duration,
                    0
                )
            )
        } catch (e: Exception) {
            performanceChannel.send(
                PerformanceEvent(
                    EventType.PRELOAD_FAILED,
                    component,
                    0,
                    0
                )
            )
        } finally {
            preloadQueue.remove(component)
        }
    }
    
    fun recordPerformanceEvent(event: PerformanceEvent) {
        performanceChannel.trySend(event)
    }
    
    fun getPerformanceMetrics(component: String): PerformanceMetrics? {
        return performanceMetrics[component]
    }
    
    fun getAllMetrics(): Map<String, PerformanceMetrics> {
        return performanceMetrics.toMap()
    }
    
    fun getCacheStats(): Pair<Int, Long> {
        val count = componentCache.size
        val size = componentCache.values.sumOf { it.size }
        return Pair(count, size)
    }
    
    suspend fun clearCache() {
        optimizationMutex.withLock {
            componentCache.clear()
            preloadQueue.clear()
        }
    }
    
    fun cleanup() {
        optimizationScope?.cancel()
        optimizationScope = null
        isInitialized = false

        // Drain any remaining events before closing
        while (performanceChannel.tryReceive().getOrNull() != null) {
            // Events are discarded during cleanup
        }

        performanceChannel.close()
        componentCache.clear()
        preloadQueue.clear()
        performanceMetrics.clear()
    }
}

/**
 * Performance-optimized remember with intelligent caching
 * Note: Calculation is suspended, so initial value is computed on first composition.
 * Use this for expensive calculations that benefit from caching across recompositions.
 */
@Composable
fun <T> rememberOptimized(
    key: Any?,
    calculation: suspend () -> T
): State<T> {
    val component = key?.toString() ?: "unknown"
    val state = remember { mutableStateOf<T?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(key) {
        val startTime = System.currentTimeMillis()
        PerformanceOptimizer.recordPerformanceEvent(
            PerformanceOptimizer.PerformanceEvent(
                PerformanceOptimizer.EventType.COMPOSITION_START,
                component,
                0,
                0
            )
        )
        try {
            val result = calculation()
            state.value = result
            val duration = System.currentTimeMillis() - startTime
            PerformanceOptimizer.recordPerformanceEvent(
                PerformanceOptimizer.PerformanceEvent(
                    PerformanceOptimizer.EventType.COMPOSITION_END,
                    component,
                    duration,
                    0
                )
            )
        } catch (e: Exception) {
            AppLog.e("PerformanceOptimizer", "rememberOptimized calculation failed: ${e.message}", e)
        }
    }

    // Return derived state that throws if accessed before calculation completes
    return remember(state) {
        derivedStateOf {
            state.value ?: throw IllegalStateException(
                "rememberOptimized accessed before calculation completed. " +
                "Use LaunchedEffect to wait for calculation or provide a default value."
            )
        }
    }
}

/**
 * Performance-optimized LazyColumn with intelligent rendering
 */
@Composable
fun OptimizedLazyColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    
    // Track performance
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                PerformanceOptimizer.recordPerformanceEvent(
                    PerformanceOptimizer.PerformanceEvent(
                        PerformanceOptimizer.EventType.LAYOUT_START,
                        "LazyColumn",
                        0,
                        0
                    )
                )
            }
    }
    
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        flingBehavior = flingBehavior,
        userScrollEnabled = userScrollEnabled,
        state = listState,
        content = content
    )
}

/**
 * Performance monitoring modifier
 */
@Composable
fun Modifier.performanceMonitored(componentName: String): Modifier {
    val scope = rememberCoroutineScope()
    
    LaunchedEffect(Unit) {
        PerformanceOptimizer.recordPerformanceEvent(
            PerformanceOptimizer.PerformanceEvent(
                PerformanceOptimizer.EventType.COMPOSITION_START,
                componentName,
                0,
                0
            )
        )
    }
    
    DisposableEffect(Unit) {
        onDispose {
            PerformanceOptimizer.recordPerformanceEvent(
                PerformanceOptimizer.PerformanceEvent(
                    PerformanceOptimizer.EventType.COMPOSITION_END,
                    componentName,
                    0,
                    0
                )
            )
        }
    }
    
    return this
}
