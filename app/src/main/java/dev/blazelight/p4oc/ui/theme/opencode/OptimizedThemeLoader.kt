package dev.blazelight.p4oc.ui.theme.opencode

import android.content.Context
import dev.blazelight.p4oc.ui.theme.createFallbackTheme
import kotlinx.coroutines.*

object OptimizedThemeLoader {
    
    private var isInitialized = false
    private var initializationScope: CoroutineScope? = null
    
    fun initialize(context: Context) {
        if (isInitialized) return
        
        initializationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        // Preload fallback theme synchronously for instant startup
        preloadFallbackThemes()
        
        // Start async preload of all themes
        initializationScope?.launch {
            ThemeCacheManager.preloadThemes(context)
            isInitialized = true
        }
    }
    
    /**
     * Preload fallback themes synchronously for instant startup
     */
    fun preloadFallbackThemes() {
        val lightFallback = createFallbackTheme(false)
        val darkFallback = createFallbackTheme(true)
        
        themeCache["opencode_false"] = lightFallback
        themeCache["opencode_true"] = darkFallback
        themeCache["fallback_false"] = lightFallback
        themeCache["fallback_true"] = darkFallback
    }
    
    suspend fun loadTheme(context: Context, themeName: String, isDark: Boolean): OpenCodeTheme {
        if (!isInitialized) {
            initialize(context)
        }
        return ThemeCacheManager.loadTheme(context, themeName, isDark)
    }
    
    suspend fun loadThemeSync(context: Context, themeName: String, isDark: Boolean): OpenCodeTheme {
        return ThemeCacheManager.loadTheme(context, themeName, isDark)
    }
    
    /**
     * Load theme immediately without async for initial app startup
     * Uses preloaded cache for instant loading, then updates with real theme in background
     */
    fun loadThemeImmediate(context: Context, themeName: String, isDark: Boolean): OpenCodeTheme {
        val cacheKey = "${themeName}_${isDark}"
        themeCache[cacheKey]?.let { return it }
        val fallbackTheme = createFallbackTheme(isDark)
        themeCache[cacheKey] = fallbackTheme
        return fallbackTheme
    }
    
    /**
     * Get cached theme if available, null otherwise
     */
    fun getCachedTheme(themeName: String, isDark: Boolean): OpenCodeTheme? {
        val cacheKey = "${themeName}_${isDark}"
        return themeCache[cacheKey]
    }
    
    /**
     * Check if theme is ready for use
     */
    fun isThemeReady(): Boolean = isInitialized
    
    private val themeCache = mutableMapOf<String, OpenCodeTheme>()
    
    fun getAvailableThemes(context: Context): List<String> {
        return ThemeCacheManager.getAvailableThemes(context)
    }
    
    suspend fun clearCache() {
        ThemeCacheManager.clearCache()
        isInitialized = false
    }
    
    fun getCacheStats(): Pair<Int, Int> {
        return ThemeCacheManager.getCacheStats()
    }
    
    fun cleanup() {
        initializationScope?.cancel()
        initializationScope = null
    }
}
