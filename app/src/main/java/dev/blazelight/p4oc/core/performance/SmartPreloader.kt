package dev.blazelight.p4oc.core.performance

import android.content.Context
import androidx.compose.ui.graphics.Color
import dev.blazelight.p4oc.ui.theme.opencode.OpenCodeTheme
import dev.blazelight.p4oc.ui.theme.opencode.OptimizedThemeLoader
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object SmartPreloader {
    private val preloadMutex = Mutex()
    private val preloadedThemes = ConcurrentHashMap<String, Boolean>()
    private val usageFrequency = ConcurrentHashMap<String, Int>()
    private var isInitialized = false
    private var preloaderScope: CoroutineScope? = null
    
    data class UsagePattern(
        val themeName: String,
        val frequency: Int,
        val lastUsed: Long,
        val priority: Int
    )
    
    suspend fun initialize(context: Context) {
        if (isInitialized) return
        
        preloaderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        preloaderScope?.launch {
            analyzeUsagePatterns(context)
            preloadHighPriorityThemes(context)
        }
        
        isInitialized = true
    }
    
    private suspend fun analyzeUsagePatterns(context: Context) {
        preloadMutex.withLock {
            val availableThemes = OptimizedThemeLoader.getAvailableThemes(context)
            
            availableThemes.forEach { theme ->
                val frequency = usageFrequency.getOrDefault(theme, 0)
                val lastUsed = System.currentTimeMillis() - (frequency * 1000L)
                val priority = calculatePriority(theme, frequency, lastUsed)
                
                preloadedThemes[theme] = false
            }
        }
    }
    
    private fun calculatePriority(themeName: String, frequency: Int, lastUsed: Long): Int {
        val baseScore = frequency * 10
        val recencyScore = maxOf(0, (System.currentTimeMillis() - lastUsed) / 1000 / 60).toInt()
        val themeScore = when {
            themeName.contains("catppuccin") -> 50
            themeName.contains("dark") -> 30
            themeName.contains("light") -> 25
            else -> 10
        }
        return baseScore + recencyScore + themeScore
    }
    
    private suspend fun preloadHighPriorityThemes(context: Context) {
        val sortedThemes = preloadedThemes.keys.sortedByDescending { theme ->
            calculatePriority(theme, usageFrequency.getOrDefault(theme, 0), System.currentTimeMillis())
        }
        
        sortedThemes.take(3).forEach { theme ->
            try {
                OptimizedThemeLoader.loadTheme(context, theme, true)
                OptimizedThemeLoader.loadTheme(context, theme, false)
                preloadedThemes[theme] = true
                delay(50) // Small delay to prevent overwhelming
            } catch (e: Exception) {
                // Continue with other themes
            }
        }
    }
    
    suspend fun recordThemeUsage(themeName: String) {
        preloadMutex.withLock {
            val current = usageFrequency.getOrDefault(themeName, 0)
            usageFrequency[themeName] = current + 1
            
            // Trigger adaptive preload if this is a frequently used theme
            if (current >= 2) {
                preloaderScope?.launch {
                    preloadRelatedThemes(themeName)
                }
            }
        }
    }
    
    private suspend fun preloadRelatedThemes(themeName: String) {
        val relatedThemes = when {
            themeName.contains("dark") -> listOf("light", "catppuccin")
            themeName.contains("light") -> listOf("dark", "catppuccin")
            themeName.contains("catppuccin") -> listOf("dracula", "nord")
            else -> listOf("catppuccin", "dark")
        }
        
        relatedThemes.forEach { theme ->
            if (!preloadedThemes.getOrDefault(theme, false)) {
                try {
                    // This will be implemented by the theme loader
                    preloadedThemes[theme] = true
                } catch (e: Exception) {
                    // Continue
                }
            }
        }
    }
    
    fun getPreloadStats(): Map<String, Any> {
        return mapOf(
            "total_themes" to preloadedThemes.size,
            "preloaded_themes" to preloadedThemes.values.count { it },
            "usage_patterns" to usageFrequency.toMap()
        )
    }
    
    suspend fun clearCache() {
        preloadMutex.withLock {
            preloadedThemes.clear()
            usageFrequency.clear()
        }
        preloaderScope?.cancel()
        preloaderScope = null
        isInitialized = false
    }
    
    fun cleanup() {
        preloaderScope?.cancel()
        preloaderScope = null
    }
}
