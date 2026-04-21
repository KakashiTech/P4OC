package dev.blazelight.p4oc.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.blazelight.p4oc.ui.theme.opencode.OpenCodeTheme
import dev.blazelight.p4oc.ui.theme.opencode.OptimizedThemeLoader
import dev.blazelight.p4oc.ui.theme.opencode.toMaterial3ColorScheme
import kotlinx.coroutines.launch

/**
 * CompositionLocal for accessing the current OpenCode theme.
 * Use `LocalOpenCodeTheme.current` to access theme colors in composables.
 */
val LocalOpenCodeTheme = staticCompositionLocalOf<OpenCodeTheme> {
    error("No OpenCodeTheme provided - wrap content in PocketCodeTheme")
}

/**
 * Create a fallback theme for immediate rendering while the actual theme loads.
 * This prevents the blank screen issue during app startup.
 */
fun createFallbackTheme(isDark: Boolean): OpenCodeTheme {
    return if (isDark) {
        OpenCodeTheme(
            name = "fallback-dark",
            isDark = true,
            primary = androidx.compose.ui.graphics.Color(0xFFBB86FC),
            secondary = androidx.compose.ui.graphics.Color(0xFF03DAC6),
            accent = androidx.compose.ui.graphics.Color(0xFFBB86FC),
            text = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
            textMuted = androidx.compose.ui.graphics.Color(0xFFB3B3B3),
            background = androidx.compose.ui.graphics.Color(0xFF121212),
            backgroundPanel = androidx.compose.ui.graphics.Color(0xFF1E1E1E),
            backgroundElement = androidx.compose.ui.graphics.Color(0xFF2D2D2D),
            border = androidx.compose.ui.graphics.Color(0xFF404040),
            borderActive = androidx.compose.ui.graphics.Color(0xFFBB86FC),
            borderSubtle = androidx.compose.ui.graphics.Color(0xFF333333),
            error = androidx.compose.ui.graphics.Color(0xFFCF6679),
            warning = androidx.compose.ui.graphics.Color(0xFFFFB74D),
            success = androidx.compose.ui.graphics.Color(0xFF81C784),
            info = androidx.compose.ui.graphics.Color(0xFF64B5F6),
            diffAdded = androidx.compose.ui.graphics.Color(0xFF81C784),
            diffRemoved = androidx.compose.ui.graphics.Color(0xFFCF6679),
            diffContext = androidx.compose.ui.graphics.Color(0xFF666666),
            diffHunkHeader = androidx.compose.ui.graphics.Color(0xFF404040),
            diffHighlightAdded = androidx.compose.ui.graphics.Color(0xFF4CAF50),
            diffHighlightRemoved = androidx.compose.ui.graphics.Color(0xFFF44336),
            diffAddedBg = androidx.compose.ui.graphics.Color(0xFF1B5E20),
            diffRemovedBg = androidx.compose.ui.graphics.Color(0xFFB71C1C),
            diffContextBg = androidx.compose.ui.graphics.Color(0xFF1A1A1A),
            diffLineNumber = androidx.compose.ui.graphics.Color(0xFF888888),
            diffAddedLineNumberBg = androidx.compose.ui.graphics.Color(0xFF2E7D32),
            diffRemovedLineNumberBg = androidx.compose.ui.graphics.Color(0xFFC62828),
            markdownText = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
            markdownHeading = androidx.compose.ui.graphics.Color(0xFFBB86FC),
            markdownLink = androidx.compose.ui.graphics.Color(0xFF03DAC6),
            markdownLinkText = androidx.compose.ui.graphics.Color(0xFF03DAC6),
            markdownCode = androidx.compose.ui.graphics.Color(0xFF81C784),
            markdownBlockQuote = androidx.compose.ui.graphics.Color(0xFF9E9E9E),
            markdownEmph = androidx.compose.ui.graphics.Color(0xFFBB86FC),
            markdownStrong = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
            markdownHorizontalRule = androidx.compose.ui.graphics.Color(0xFF404040),
            markdownListItem = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
            markdownListEnumeration = androidx.compose.ui.graphics.Color(0xFFBB86FC),
            markdownImage = androidx.compose.ui.graphics.Color(0xFF03DAC6),
            markdownImageText = androidx.compose.ui.graphics.Color(0xFF03DAC6),
            markdownCodeBlock = androidx.compose.ui.graphics.Color(0xFF1E1E1E),
            syntaxComment = androidx.compose.ui.graphics.Color(0xFF6A9955),
            syntaxKeyword = androidx.compose.ui.graphics.Color(0xFF569CD6),
            syntaxFunction = androidx.compose.ui.graphics.Color(0xFFDCDCAA),
            syntaxVariable = androidx.compose.ui.graphics.Color(0xFF9CDCFE),
            syntaxString = androidx.compose.ui.graphics.Color(0xFFCE9178),
            syntaxNumber = androidx.compose.ui.graphics.Color(0xFFB5CEA8),
            syntaxType = androidx.compose.ui.graphics.Color(0xFF4EC9B0),
            syntaxOperator = androidx.compose.ui.graphics.Color(0xFFD4D4D4),
            syntaxPunctuation = androidx.compose.ui.graphics.Color(0xFFD4D4D4)
        )
    } else {
        OpenCodeTheme(
            name = "fallback-light",
            isDark = false,
            primary = androidx.compose.ui.graphics.Color(0xFF6750A4),
            secondary = androidx.compose.ui.graphics.Color(0xFF625B71),
            accent = androidx.compose.ui.graphics.Color(0xFF6750A4),
            text = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
            textMuted = androidx.compose.ui.graphics.Color(0xFF49454F),
            background = androidx.compose.ui.graphics.Color(0xFFFFFBFE),
            backgroundPanel = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
            backgroundElement = androidx.compose.ui.graphics.Color(0xFFF7F2FA),
            border = androidx.compose.ui.graphics.Color(0xFFE7E0EC),
            borderActive = androidx.compose.ui.graphics.Color(0xFF6750A4),
            borderSubtle = androidx.compose.ui.graphics.Color(0xFFE6E1E5),
            error = androidx.compose.ui.graphics.Color(0xFFBA1A1A),
            warning = androidx.compose.ui.graphics.Color(0xFF7D5700),
            success = androidx.compose.ui.graphics.Color(0xFF004D3D),
            info = androidx.compose.ui.graphics.Color(0xFF004494),
            diffAdded = androidx.compose.ui.graphics.Color(0xFF004D3D),
            diffRemoved = androidx.compose.ui.graphics.Color(0xFFBA1A1A),
            diffContext = androidx.compose.ui.graphics.Color(0xFF79747E),
            diffHunkHeader = androidx.compose.ui.graphics.Color(0xFFE7E0EC),
            diffHighlightAdded = androidx.compose.ui.graphics.Color(0xFF006D3D),
            diffHighlightRemoved = androidx.compose.ui.graphics.Color(0xFFBA1A1A),
            diffAddedBg = androidx.compose.ui.graphics.Color(0xFFE8F5E8),
            diffRemovedBg = androidx.compose.ui.graphics.Color(0xFFFFE8E8),
            diffContextBg = androidx.compose.ui.graphics.Color(0xFFF5F5F5),
            diffLineNumber = androidx.compose.ui.graphics.Color(0xFF666666),
            diffAddedLineNumberBg = androidx.compose.ui.graphics.Color(0xFFC8E6C9),
            diffRemovedLineNumberBg = androidx.compose.ui.graphics.Color(0xFFFFCDD2),
            markdownText = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
            markdownHeading = androidx.compose.ui.graphics.Color(0xFF6750A4),
            markdownLink = androidx.compose.ui.graphics.Color(0xFF004494),
            markdownLinkText = androidx.compose.ui.graphics.Color(0xFF004494),
            markdownCode = androidx.compose.ui.graphics.Color(0xFF004D3D),
            markdownBlockQuote = androidx.compose.ui.graphics.Color(0xFF79747E),
            markdownEmph = androidx.compose.ui.graphics.Color(0xFF6750A4),
            markdownStrong = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
            markdownHorizontalRule = androidx.compose.ui.graphics.Color(0xFFE7E0EC),
            markdownListItem = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
            markdownListEnumeration = androidx.compose.ui.graphics.Color(0xFF6750A4),
            markdownImage = androidx.compose.ui.graphics.Color(0xFF004494),
            markdownImageText = androidx.compose.ui.graphics.Color(0xFF004494),
            markdownCodeBlock = androidx.compose.ui.graphics.Color(0xFFF5F5F5),
            syntaxComment = androidx.compose.ui.graphics.Color(0xFF6A8759),
            syntaxKeyword = androidx.compose.ui.graphics.Color(0xFF0000FF),
            syntaxFunction = androidx.compose.ui.graphics.Color(0xFF7A7A43),
            syntaxVariable = androidx.compose.ui.graphics.Color(0xFF001080),
            syntaxString = androidx.compose.ui.graphics.Color(0xFFA31515),
            syntaxNumber = androidx.compose.ui.graphics.Color(0xFF098658),
            syntaxType = androidx.compose.ui.graphics.Color(0xFF0000FF),
            syntaxOperator = androidx.compose.ui.graphics.Color(0xFF000000),
            syntaxPunctuation = androidx.compose.ui.graphics.Color(0xFF000000)
        )
    }
}

/**
 * TUI shapes - ZERO roundness, full terminal aesthetic.
 * All shapes use RoundedCornerShape(0.dp) for consistent sharp corners.
 */
val TuiShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp)
)

@Composable
fun PocketCodeTheme(
    themeName: String = "dracula",
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var openCodeTheme by remember { mutableStateOf<OpenCodeTheme?>(null) }
    var colorScheme by remember { mutableStateOf<androidx.compose.material3.ColorScheme?>(null) }

    LaunchedEffect(Unit) {
        OptimizedThemeLoader.initialize(context)
    }

    val fallbackTheme = remember(themeName, darkTheme) {
        OptimizedThemeLoader.loadThemeImmediate(context, themeName, darkTheme)
    }

    LaunchedEffect(themeName, darkTheme) {
        try {
            val loadedTheme = OptimizedThemeLoader.loadTheme(context, themeName, darkTheme)
            openCodeTheme = loadedTheme
            colorScheme = loadedTheme.toMaterial3ColorScheme()
        } catch (e: Exception) {
            // Keep fallback
        }
    }

    val currentTheme = openCodeTheme ?: fallbackTheme
    val currentColorScheme = colorScheme ?: currentTheme.toMaterial3ColorScheme()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            @Suppress("DEPRECATION")
            window.navigationBarColor = currentColorScheme.background.toArgb()
            @Suppress("DEPRECATION")
            window.statusBarColor = currentColorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalOpenCodeTheme provides currentTheme,
        LocalTuiSpacing provides TuiDefaults
    ) {
        MaterialTheme(
            colorScheme = currentColorScheme,
            shapes = TuiShapes,
            typography = Typography,
            content = content
        )
    }
}
