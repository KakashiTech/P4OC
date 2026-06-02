package dev.blazelight.p4oc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.ui.components.chat.StreamingMarkdown
import dev.blazelight.p4oc.ui.navigation.NavGraph
import dev.blazelight.p4oc.ui.navigation.Screen
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.PocketCodeTheme
import dev.blazelight.p4oc.ui.theme.opencode.OptimizedThemeLoader
import kotlinx.coroutines.delay
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val settingsDataStore: SettingsDataStore by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Pre-warm theme cache before first composition frame
        OptimizedThemeLoader.initialize(this@MainActivity)
        OptimizedThemeLoader.preloadFallbackThemes()

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
                    Box(Modifier.fillMaxSize()) {
                        val navController = rememberNavController()
                        NavGraph(
                            navController = navController,
                            startDestination = Screen.Server.route
                        )
                        PreWarmComposables()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

/**
 * Pre-warms shader compilation, font loading, and markdown AST parser
 * by composing a hidden StreamingMarkdown during initial screen display.
 * Self-removes after 300ms to avoid ongoing traversal overhead.
 */
@Composable
private fun PreWarmComposables() {
    var show by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(300)
        show = false
    }
    if (show) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(0f)
        ) {
            StreamingMarkdown(
                text = "# Header 1\n## Header 2\n```\nval x = 1\nfun hello() = println(x)\n```\n| Column A | Column B |\n|---|---|\n| Cell 1 | Cell 2 |\n**bold text** *italic text* `inline code`\n- List item 1\n- List item 2"
            )
        }
    }
}
