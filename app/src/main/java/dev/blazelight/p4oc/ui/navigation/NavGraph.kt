package dev.blazelight.p4oc.ui.navigation

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
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

// ── True iOS UINavigationController illusion ──────────────────────────────
// Push easing : cubic-bezier(0.25, 0.46, 0.45, 0.94) — exact UIKit ease-out
// Exit easing : cubic-bezier(0.42, 0.0,  0.58, 1.0)  — ease-in-out for departures
private val iosEaseOut   = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)
private val iosEaseInOut = CubicBezierEasing(0.42f, 0.00f, 0.58f, 1.00f)

private val iosEnterSpring = spring<IntOffset>(dampingRatio = 0.90f, stiffness = 460f)
private val iosPopSpring   = spring<IntOffset>(dampingRatio = 0.86f, stiffness = 600f)
private val iosBgSlide     = tween<IntOffset>(280, easing = iosEaseOut)
private val iosFadeIn      = tween<Float>(200, easing = iosEaseOut)
private val iosFadeOut     = tween<Float>(160, easing = iosEaseInOut)

// PUSH ENTER: new screen springs in from full right + fade
private val iosPushEnter: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    slideInHorizontally(iosEnterSpring) { it } + fadeIn(iosFadeIn)
}
// PUSH EXIT: old screen recedes 1/3 to left + depth scale + fade
private val iosPushExit: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    slideOutHorizontally(iosBgSlide) { -(it / 3) } +
    scaleOut(tween(280, easing = iosEaseOut), targetScale = 0.94f) +
    fadeOut(iosFadeOut)
}
// POP ENTER: background screen slides back from 1/3 left — NO scale
// iOS: the background screen was never scaled, it only slid away
private val iosPopEnter: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    slideInHorizontally(iosBgSlide) { -(it / 3) } + fadeIn(iosFadeIn)
}
// POP EXIT: top screen springs back to right + subtle scale + fade
private val iosPopExit: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    slideOutHorizontally(iosPopSpring) { it } +
    scaleOut(tween(200, easing = iosEaseInOut), targetScale = 0.98f) +
    fadeOut(iosFadeOut)
}

// CROSSFADE: used for stack-replace transitions (autoconnect Server→Sessions, disconnect
// Sessions→Server). These have no inherent directionality so a slide would feel wrong.
// A clean fade communicates "level change" without implying forward or back.
private val crossFadeEnter: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    fadeIn(tween(300, easing = iosEaseOut)) +
    scaleIn(tween(300, easing = iosEaseOut), initialScale = 0.97f)
}
private val crossFadeExit: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    fadeOut(tween(220, easing = iosEaseInOut)) +
    scaleOut(tween(220, easing = iosEaseInOut), targetScale = 0.97f)
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
        composable(
            route = Screen.Setup.route,
            enterTransition    = { crossFadeEnter(this) },
            exitTransition     = { crossFadeExit(this) },
            popEnterTransition = { crossFadeEnter(this) },
            popExitTransition  = { crossFadeExit(this) }
        ) {
            SetupScreen(
                onSetupComplete = {
                    navController.navigate(Screen.Server.route) {
                        popUpTo(Screen.Setup.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.Server.route,
            enterTransition    = { crossFadeEnter(this) },
            exitTransition     = { crossFadeExit(this) },
            popEnterTransition = { crossFadeEnter(this) },
            popExitTransition  = { crossFadeExit(this) }
        ) {
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
        composable(
            route = Screen.Sessions.route,
            enterTransition    = { crossFadeEnter(this) },
            exitTransition     = { crossFadeExit(this) },
            popEnterTransition = { crossFadeEnter(this) },
            popExitTransition  = { crossFadeExit(this) }
        ) {
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
