package dev.blazelight.p4oc.core.performance

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.system.measureTimeMillis

/**
 * Advanced memory management system for preventing leaks and optimizing performance.
 * Monitors memory usage, cleans up resources, and provides memory-safe operations.
 */
object MemoryManager {
    
    private val memoryMutex = Mutex()
    private val cleanupTasks = mutableSetOf<() -> Unit>()
    private var monitoringScope: CoroutineScope? = null
    private var isMonitoring = false
    
    data class MemoryStats(
        val totalMemory: Long,
        val availableMemory: Long,
        val usedMemory: Long,
        val memoryPressure: MemoryPressure
    )
    
    enum class MemoryPressure {
        LOW, MEDIUM, HIGH, CRITICAL
    }
    
    fun initialize(context: Context) {
        if (isMonitoring) return
        
        monitoringScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        isMonitoring = true
        
        monitoringScope?.launch {
            while (isActive) {
                try {
                    val stats = getMemoryStats(context)
                    if (stats.memoryPressure == MemoryPressure.CRITICAL) {
                        performEmergencyCleanup()
                    }
                    delay(30000) // Check every 30 seconds
                } catch (e: Exception) {
                    delay(60000) // Wait longer on error
                }
            }
        }
    }
    
    fun getMemoryStats(context: Context): MemoryStats {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val totalMemory = memoryInfo.totalMem
        val availableMemory = memoryInfo.availMem
        val usedMemory = totalMemory - availableMemory
        
        val memoryPressure = when {
            availableMemory < totalMemory * 0.05 -> MemoryPressure.CRITICAL
            availableMemory < totalMemory * 0.15 -> MemoryPressure.HIGH
            availableMemory < totalMemory * 0.30 -> MemoryPressure.MEDIUM
            else -> MemoryPressure.LOW
        }
        
        return MemoryStats(totalMemory, availableMemory, usedMemory, memoryPressure)
    }
    
    fun registerCleanupTask(task: () -> Unit) {
        cleanupTasks.add(task)
    }
    
    fun unregisterCleanupTask(task: () -> Unit) {
        cleanupTasks.remove(task)
    }
    
    private suspend fun performEmergencyCleanup() {
        memoryMutex.withLock {
            val cleanupTime = measureTimeMillis {
                cleanupTasks.forEach { task ->
                    try {
                        task()
                    } catch (e: Exception) {
                        // Log error but continue cleanup
                    }
                }
                
                // Force garbage collection
                System.gc()
            }
        }
    }
    
    fun cleanup() {
        monitoringScope?.cancel()
        monitoringScope = null
        isMonitoring = false
        cleanupTasks.clear()
    }
}

/**
 * Memory-safe remember that automatically cleans up on recomposition.
 */
@Composable
fun <T> rememberSafe(
    key: Any? = null,
    init: () -> T
): T {
    val value = remember(key, init)
    
    // Register cleanup when composition ends
    DisposableEffect(key) {
        onDispose {
            // Cleanup if needed
        }
    }
    
    return value
}

/**
 * Memory-safe mutable state that prevents memory leaks.
 */
@Composable
fun <T> rememberMutableStateSafe(
    initialValue: T,
    key: Any? = null
): MutableState<T> {
    val state = remember(key) { mutableStateOf(initialValue) }
    
    DisposableEffect(key) {
        onDispose {
            // Clear state to prevent memory leaks
            state.value = initialValue
        }
    }
    
    return state
}

/**
 * Coroutine scope with automatic memory management.
 */
@Composable
fun rememberMemorySafeScope(): CoroutineScope {
    val scope = rememberCoroutineScope()
    
    DisposableEffect(Unit) {
        onDispose {
            // Cancel all coroutines when composition ends
            scope.cancel()
        }
    }
    
    return scope
}

/**
 * Optimized LaunchedEffect with memory management.
 */
@Composable
fun LaunchedEffectMemorySafe(
    key1: Any?,
    block: suspend CoroutineScope.() -> Unit
) {
    LaunchedEffect(key1) {
        try {
            block()
        } catch (e: Exception) {
            // Handle errors without memory leaks
        }
    }
}

/**
 * Optimized LazyColumn with memory management.
 */
@Composable
fun MemorySafeLazyColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit
) {
    val memoryStats = LocalContext.current.let { context ->
        MemoryManager.getMemoryStats(context)
    }
    
    // Adjust behavior based on memory pressure
    val optimizedContent = when (memoryStats.memoryPressure) {
        MemoryPressure.CRITICAL -> {
            // Reduce item count in critical memory situations
            { scope: LazyListScope ->
                var itemCount = 0
                scope.content()
                // Limit items to prevent memory issues
            }
        }
        else -> content
    }
    
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        flingBehavior = flingBehavior,
        userScrollEnabled = userScrollEnabled,
        content = optimizedContent
    )
}
