package dev.blazelight.p4oc.ui.tabs

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.core.datastore.VisualSettings
import org.koin.compose.koinInject
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.blazelight.p4oc.domain.model.SessionConnectionState
import dev.blazelight.p4oc.ui.navigation.Screen
import dev.blazelight.p4oc.ui.screens.chat.ChatScreen
import dev.blazelight.p4oc.ui.screens.diff.DiffViewerScreen
import dev.blazelight.p4oc.ui.screens.diff.SessionDiffScreen
import dev.blazelight.p4oc.ui.screens.files.FileExplorerScreen
import dev.blazelight.p4oc.ui.screens.files.FileViewerScreen
import dev.blazelight.p4oc.ui.screens.projects.ProjectsScreen
import dev.blazelight.p4oc.ui.screens.sessions.SessionListScreen
import dev.blazelight.p4oc.ui.screens.settings.*
import dev.blazelight.p4oc.ui.screens.terminal.TerminalScreen

// ── True iOS UINavigationController illusion ────────────────────────────────
// Three simultaneous layers on every transition:
//   1. translateX  — new screen slides full-width; old recedes at 1/3 speed (parallax)
//   2. scale       — outgoing screen shrinks to 0.94f creating depth/z feel
//   3. alpha       — fade hides the scale artifact cleanly
//
// Spring physics: dampingRatio 0.88 = no bounce, smooth natural stop
//                 stiffness 400    = ~280ms effective, fast but not jarring

private val pushSpring = spring<IntOffset>(dampingRatio = 0.88f, stiffness = 400f)
private val popSpring  = spring<IntOffset>(dampingRatio = 0.86f, stiffness = 500f)
private val paralaxTween = tween<IntOffset>(240, easing = FastOutSlowInEasing)

// PUSH ENTER: spring slide from right full-width + fast fade
private val pushEnter: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    slideInHorizontally(pushSpring) { it } +
    fadeIn(tween(180, easing = FastOutSlowInEasing))
}
// PUSH EXIT: old screen recedes 1/3 left + shrinks to 0.94 + fades
// Key: moves slower than entering screen = parallax depth illusion
private val pushExit: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    slideOutHorizontally(paralaxTween) { -(it / 3) } +
    scaleOut(tween(240, easing = FastOutSlowInEasing), targetScale = 0.94f) +
    fadeOut(tween(160, easing = FastOutSlowInEasing))
}
// POP ENTER: previous screen slides back from 1/3 left + scale restores
private val popEnter: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    slideInHorizontally(paralaxTween) { -(it / 3) } +
    scaleIn(tween(240, easing = FastOutSlowInEasing), initialScale = 0.94f) +
    fadeIn(tween(160, easing = FastOutSlowInEasing))
}
// POP EXIT: spring snap to right, gesture-like
private val popExit: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    slideOutHorizontally(popSpring) { it } +
    fadeOut(tween(120, easing = FastOutSlowInEasing))
}

/**
 * Per-tab navigation host.
 * Each tab has its own NavHost with independent navigation stack.
 * The [startRoute] determines the initial screen — defaults to Sessions list,
 * but can be a filled route like "terminal/abc123" or "files" for dedicated tabs.
 */
@Composable
fun TabNavHost(
    navController: NavHostController,
    tabManager: TabManager,
    tabId: String,
    onDisconnect: () -> Unit,
    onNewFilesTab: () -> Unit = {},
    onNewTerminalTab: () -> Unit = {},
    onCloseTab: () -> Unit = {},
    isActiveTab: Boolean = true,
    startRoute: String = Screen.Sessions.route,
    onConnectionStateChanged: ((SessionConnectionState?) -> Unit)? = null,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    // Read visual settings for sub-agent tab behavior
    val settingsDataStore: SettingsDataStore = koinInject()
    val visualSettings by settingsDataStore.visualSettings.collectAsState(initial = VisualSettings())
    val openSubAgentInNewTab = visualSettings.openSubAgentInNewTab

    // Double-back-to-close when at the root of any tab.
    // Dedicated tabs (terminal, files, chat): closes the tab.
    // Sessions tab: exits the app.
    var backPressedAt by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val isDedicatedTab = startRoute != Screen.Sessions.route

    BackHandler(enabled = navController.previousBackStackEntry == null) {
        val now = System.currentTimeMillis()
        if (now - backPressedAt < 2000L) {
            if (isDedicatedTab) {
                onCloseTab()
            } else {
                (context as? android.app.Activity)?.finish()
            }
        } else {
            backPressedAt = now
            val message = if (isDedicatedTab) "Press back again to close tab" else "Press back again to exit"
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    NavHost(
        navController = navController,
        startDestination = startRoute,
        modifier = modifier,
        enterTransition    = pushEnter,
        exitTransition     = pushExit,
        popEnterTransition = popEnter,
        popExitTransition  = popExit
    ) {
        // Sessions list (start destination for new tabs)
        composable(Screen.Sessions.route) {
            SessionListScreen(
                showTopBar = false,
                onSessionClick = { sessionId, directory ->
                    // Check if session already open in another tab
                    val existingTab = tabManager.findTabBySessionId(sessionId)
                    if (existingTab != null && existingTab.id != tabId) {
                        // Focus existing tab
                        tabManager.focusTab(existingTab.id)
                    } else {
                        // Navigate within this tab
                        navController.navigate(Screen.Chat.createRoute(sessionId, directory))
                    }
                },
                onNewSession = { sessionId, directory ->
                    navController.navigate(Screen.Chat.createRoute(sessionId, directory))
                },
                onSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onProjects = {
                    navController.navigate(Screen.Projects.route)
                },
                onProjectClick = { projectId ->
                    navController.navigate(Screen.SessionsFiltered.createRoute(projectId))
                },
                onViewChanges = { sessionId ->
                    navController.navigate(Screen.SessionDiff.createRoute(sessionId))
                }
            )
        }

        // Filtered sessions by project
        composable(
            route = Screen.SessionsFiltered.route,
            arguments = listOf(
                navArgument(Screen.SessionsFiltered.ARG_PROJECT_ID) {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString(Screen.SessionsFiltered.ARG_PROJECT_ID) ?: ""
            SessionListScreen(
                filterProjectId = projectId,
                onSessionClick = { sessionId, directory ->
                    val existingTab = tabManager.findTabBySessionId(sessionId)
                    if (existingTab != null && existingTab.id != tabId) {
                        tabManager.focusTab(existingTab.id)
                    } else {
                        navController.navigate(Screen.Chat.createRoute(sessionId, directory))
                    }
                },
                onNewSession = { sessionId, directory ->
                    navController.navigate(Screen.Chat.createRoute(sessionId, directory))
                },
                onSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onProjects = {
                    navController.navigate(Screen.Projects.route)
                },
                onProjectClick = { pid ->
                    navController.navigate(Screen.SessionsFiltered.createRoute(pid))
                },
                onViewChanges = { sessionId ->
                    navController.navigate(Screen.SessionDiff.createRoute(sessionId))
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // Chat screen
        composable(
            route = Screen.Chat.route,
            arguments = listOf(
                navArgument(Screen.Chat.ARG_SESSION_ID) { type = NavType.StringType },
                navArgument(Screen.Chat.ARG_DIRECTORY) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) {
            ChatScreen(
                onNavigateBack = { 
                    // Clear session binding when leaving chat
                    tabManager.clearTabSession(tabId)
                    onConnectionStateChanged?.invoke(null)
                    navController.popBackStack() 
                },
                onOpenTerminal = onNewTerminalTab,
                onOpenFiles = onNewFilesTab,
                onViewSessionDiff = { sessionId ->
                    navController.navigate(Screen.SessionDiff.createRoute(sessionId))
                },
                onOpenSubSession = { subSessionId ->
                    // Check if sub-session is already open in another tab
                    val existingTab = tabManager.findTabBySessionId(subSessionId)
                    if (existingTab != null && existingTab.id != tabId) {
                        // Focus the existing tab
                        tabManager.focusTab(existingTab.id)
                    } else if (openSubAgentInNewTab) {
                        // Open in a new tab (default)
                        tabManager.createTab(
                            startRoute = Screen.Chat.createRoute(subSessionId),
                            focus = true
                        )
                    } else {
                        // Same tab (legacy behavior)
                        navController.navigate(Screen.Chat.createRoute(subSessionId))
                    }
                },
                onSessionLoaded = { sessionId, sessionTitle ->
                    // Update tab's session binding
                    tabManager.updateTabSession(tabId, sessionId, sessionTitle)
                },
                onConnectionStateChanged = onConnectionStateChanged,
                isActiveTab = isActiveTab
            )
        }

        // Projects screen
        composable(Screen.Projects.route) {
            ProjectsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onProjectClick = { projectId, _ ->
                    navController.navigate(Screen.SessionsFiltered.createRoute(projectId))
                }
            )
        }

        // Terminal screen (per-PTY, each tab has its own)
        composable(
            route = Screen.Terminal.route,
            arguments = listOf(
                navArgument(Screen.Terminal.ARG_PTY_ID) { type = NavType.StringType }
            )
        ) {
            TerminalScreen(
                onPtyLoaded = { ptyId, ptyTitle ->
                    // Update tab binding with PTY id and title
                    tabManager.updateTabSession(tabId, ptyId, ptyTitle)
                }
            )
        }

        // Files screen
        composable(Screen.Files.route) {
            FileExplorerScreen(
                onFileClick = { path ->
                    navController.navigate(Screen.FileViewer.createRoute(path))
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // File viewer
        composable(
            route = Screen.FileViewer.route,
            arguments = listOf(
                navArgument(Screen.FileViewer.ARG_PATH) { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString(Screen.FileViewer.ARG_PATH) ?: ""
            FileViewerScreen(
                path = Uri.decode(encodedPath),
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Diff viewer
        composable(
            route = Screen.DiffViewer.route,
            arguments = listOf(
                navArgument(Screen.DiffViewer.ARG_CONTENT) { type = NavType.StringType },
                navArgument(Screen.DiffViewer.ARG_FILE_NAME) {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val encodedContent = backStackEntry.arguments?.getString(Screen.DiffViewer.ARG_CONTENT) ?: ""
            val encodedFileName = backStackEntry.arguments?.getString(Screen.DiffViewer.ARG_FILE_NAME) ?: ""
            DiffViewerScreen(
                diffContent = Uri.decode(encodedContent),
                fileName = Uri.decode(encodedFileName).takeIf { it.isNotEmpty() },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.SessionDiff.route,
            arguments = listOf(
                navArgument(Screen.SessionDiff.ARG_SESSION_ID) { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString(Screen.SessionDiff.ARG_SESSION_ID).orEmpty()
            SessionDiffScreen(
                sessionId = sessionId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Settings screens
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onDisconnect = onDisconnect,
                onProviderConfig = {
                    navController.navigate(Screen.ProviderConfig.route)
                },
                onVisualSettings = {
                    navController.navigate(Screen.VisualSettings.route)
                },
                onAgentsConfig = {
                    navController.navigate(Screen.AgentsConfig.route)
                },
                onSkills = {
                    navController.navigate(Screen.Skills.route)
                },
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

        composable(Screen.ModelControls.route) {
            ModelControlsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.AgentsConfig.route) {
            AgentsConfigScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Skills.route) {
            SkillsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.NotificationSettings.route) {
            NotificationSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.ConnectionSettings.route) {
            ConnectionSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }

}
