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
        android.util.Log.d("THEME_LOADER", "🔍 loadThemeImmediate: theme='$themeName', dark=$isDark, cacheKey='$cacheKey'")
        
        // Try to get from cache first (instant)
        themeCache[cacheKey]?.let { cachedTheme ->
            android.util.Log.d("THEME_LOADER", "✅ Cache HIT! Found cached theme: ${cachedTheme.name}")
            // Start background update to ensure we have the latest theme
            initializationScope?.launch {
                try {
                    android.util.Log.d("THEME_LOADER", "🔄 Background update: Loading latest theme...")
                    val latestTheme = ThemeCacheManager.loadTheme(context, themeName, isDark)
                    themeCache[cacheKey] = latestTheme
                    android.util.Log.d("THEME_LOADER", "✅ Background update completed: ${latestTheme.name}")
                } catch (e: Exception) {
                    android.util.Log.e("THEME_LOADER", "❌ Background update failed, keeping cached theme", e)
                }
            }
            return cachedTheme
        }
        
        android.util.Log.d("THEME_LOADER", "❌ Cache MISS! Theme not found in cache")
        android.util.Log.d("THEME_LOADER", "🏗️ Creating fallback theme...")
        
        // If not in cache, create fallback and start loading
        val fallbackTheme = createFallbackTheme(isDark)
        android.util.Log.d("THEME_LOADER", "📦 Fallback theme created: ${fallbackTheme.name}")
        themeCache[cacheKey] = fallbackTheme
        
        android.util.Log.d("THEME_LOADER", "🔄 Starting background load of real theme...")
        // Load real theme in background
        initializationScope?.launch {
            try {
                android.util.Log.d("THEME_LOADER", "🔄 Background loading: Loading real theme...")
                val realTheme = ThemeCacheManager.loadTheme(context, themeName, isDark)
                themeCache[cacheKey] = realTheme
                android.util.Log.d("THEME_LOADER", "✅ Real theme loaded in background: ${realTheme.name}")
            } catch (e: Exception) {
                android.util.Log.e("THEME_LOADER", "❌ Background load failed, keeping fallback theme", e)
            }
        }
        
        android.util.Log.d("THEME_LOADER", "⚠️ Returning FALLBACK theme: ${fallbackTheme.name}")
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
