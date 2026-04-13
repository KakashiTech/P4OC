package dev.blazelight.p4oc.ui.navigation

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.blazelight.p4oc.ui.screens.server.ServerScreen
import dev.blazelight.p4oc.ui.screens.settings.SettingsScreen
import dev.blazelight.p4oc.ui.screens.settings.ProviderConfigScreen
import dev.blazelight.p4oc.ui.screens.settings.VisualSettingsScreen
import dev.blazelight.p4oc.ui.screens.setup.SetupScreen
import dev.blazelight.p4oc.ui.tabs.MainTabScreen

// ── True iOS UINavigationController illusion ────────────────────────────────
// Three simultaneous layers on every transition:
//   1. translateX  — new screen slides full-width; old recedes at 30% speed (parallax)
//   2. scale       — outgoing screen shrinks to 0.94f creating depth/z feel
//   3. alpha       — fade hides the scale artifact cleanly
//
// Spring physics: dampingRatio 0.88 = no bounce, smooth natural stop
//                 stiffness 400    = ~280ms effective, fast but not jarring

private val iosEnterSpring = spring<IntOffset>(
    dampingRatio = 0.88f, stiffness = 400f
)
private val iosPopSpring = spring<IntOffset>(
    dampingRatio = 0.86f, stiffness = 500f
)
private val iosFastTween  = tween<IntOffset>(240, easing = FastOutSlowInEasing)
@Suppress("unused")
private val iosFloatTween = tween<Float>(200, easing = FastOutSlowInEasing)

// PUSH ENTER: spring slide in from right full-width + fade up
private val iosPushEnter: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    slideInHorizontally(iosEnterSpring) { fullWidth -> fullWidth } +
    fadeIn(tween(180, easing = FastOutSlowInEasing))
}
// PUSH EXIT: old screen recedes 28% left + shrinks slightly + fades out
// This is the key: it moves at 1/3.5 speed = paralax depth
private val iosPushExit: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    slideOutHorizontally(iosFastTween) { fullWidth -> -(fullWidth / 3) } +
    scaleOut(tween(240, easing = FastOutSlowInEasing), targetScale = 0.94f) +
    fadeOut(tween(160, easing = FastOutSlowInEasing))
}
// POP ENTER: previous screen slides back from left 28% + scale restores to 1f
private val iosPopEnter: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    slideInHorizontally(iosFastTween) { fullWidth -> -(fullWidth / 3) } +
    scaleIn(tween(240, easing = FastOutSlowInEasing), initialScale = 0.94f) +
    fadeIn(tween(160, easing = FastOutSlowInEasing))
}
// POP EXIT: spring snap to right (gesture-like) + fade
private val iosPopExit: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    slideOutHorizontally(iosPopSpring) { fullWidth -> fullWidth } +
    fadeOut(tween(120, easing = FastOutSlowInEasing))
}

/**
 * Root navigation graph.
 * Handles initial setup/server screens, then hands off to MainTabScreen
 * which manages its own per-tab navigation.
 */
@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = iosPushEnter,
        exitTransition = iosPushExit,
        popEnterTransition = iosPopEnter,
        popExitTransition = iosPopExit
    ) {
        composable(Screen.Setup.route) {
            SetupScreen(
                onSetupComplete = {
                    navController.navigate(Screen.Server.route) {
                        popUpTo(Screen.Setup.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Server.route) {
            ServerScreen(
                onNavigateToSessions = {
                    navController.navigate(Screen.Sessions.route) {
                        popUpTo(Screen.Server.route) { inclusive = true }
                    }
                },
                onNavigateToProjects = {
                    navController.navigate(Screen.Sessions.route) {
                        popUpTo(Screen.Server.route) { inclusive = true }
                    }
                },
                onSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        // Main tab container - this is where the tab-based UI lives
        composable(Screen.Sessions.route) {
            MainTabScreen(
                onDisconnect = {
                    navController.navigate(Screen.Server.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // Settings accessible from Server screen (before connecting)
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onDisconnect = {
                    navController.navigate(Screen.Server.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onProviderConfig = {
                    navController.navigate(Screen.ProviderConfig.route)
                },
                onVisualSettings = {
                    navController.navigate(Screen.VisualSettings.route)
                },
                onAgentsConfig = {},
                onSkills = {},
                onNotificationSettings = {
                    navController.navigate(Screen.NotificationSettings.route)
                },
                onConnectionSettings = {
                    navController.navigate(Screen.ConnectionSettings.route)
                }
            )
        }

        composable(Screen.ProviderConfig.route) {
            ProviderConfigScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable(Screen.VisualSettings.route) {
            VisualSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.NotificationSettings.route) {
            dev.blazelight.p4oc.ui.screens.settings.NotificationSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.ConnectionSettings.route) {
            dev.blazelight.p4oc.ui.screens.settings.ConnectionSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
