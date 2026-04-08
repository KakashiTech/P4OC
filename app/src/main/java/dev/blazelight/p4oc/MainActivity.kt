package dev.blazelight.p4oc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.collectAsState
import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.ui.navigation.NavGraph
import dev.blazelight.p4oc.ui.navigation.Screen
import dev.blazelight.p4oc.ui.tabs.MainTabScreen
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.PocketCodeTheme
import dev.blazelight.p4oc.ui.theme.opencode.OptimizedThemeLoader
import dev.blazelight.p4oc.core.performance.SmartPreloader
import dev.blazelight.p4oc.core.performance.ConnectionPool
import dev.blazelight.p4oc.core.performance.MessageBufferManager
import dev.blazelight.p4oc.core.performance.AnimationOptimizer
import dev.blazelight.p4oc.core.performance.MemoryManager
import dev.blazelight.p4oc.core.performance.PerformanceOptimizer
import dev.blazelight.p4oc.core.performance.PredictiveOptimizer
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    
    private val settingsDataStore: SettingsDataStore by inject()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Initialize theme loader
        OptimizedThemeLoader.initialize(this@MainActivity)

        // Initialize other systems
        lifecycleScope.launch {
            // Preload fallback themes (but user theme should already be in cache)
            OptimizedThemeLoader.preloadFallbackThemes()
            
            // Initialize other systems
            SmartPreloader.initialize(this@MainActivity)
            ConnectionPool.initialize()
            AnimationOptimizer.initialize()
            AnimationOptimizer.preloadCommonAnimations()
            MemoryManager.initialize(this@MainActivity)
            PerformanceOptimizer.initialize(this@MainActivity)
            PredictiveOptimizer.initialize(this@MainActivity)
        }

        setContent {
            val themeMode by settingsDataStore.themeMode.collectAsStateWithLifecycle(
                initialValue = settingsDataStore.getCachedThemeMode()
            )
            val themeName by settingsDataStore.themeName.collectAsStateWithLifecycle(
                initialValue = settingsDataStore.getCachedThemeName()
            )
            val darkTheme = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            
            // Apply user theme directly
            PocketCodeTheme(themeName = themeName, darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = LocalOpenCodeTheme.current.background
                ) {
                    // Use NavGraph for initial Server/Setup screens,
                    // but ensure theme is applied before any connection
                    val navController = rememberNavController()
                    NavGraph(
                        navController = navController,
                        startDestination = Screen.Server.route
                    )
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch {
            SmartPreloader.clearCache()
            ConnectionPool.clearPool()
            MessageBufferManager().cleanup()
            AnimationOptimizer.clearCache()
            MemoryManager.cleanup()
            PerformanceOptimizer.cleanup()
            PredictiveOptimizer.cleanup()
        }
    }
}
