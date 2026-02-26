@file:Suppress("DEPRECATION") // LocalLifecycleOwner – platform version until lifecycle-runtime-compose upgrade
package dev.blazelight.p4oc.ui.tabs

import dev.blazelight.p4oc.core.log.AppLog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.blazelight.p4oc.core.network.ApiResult
import dev.blazelight.p4oc.core.network.ConnectionManager
import dev.blazelight.p4oc.core.network.ConnectionState
import dev.blazelight.p4oc.core.network.safeApiCall
import dev.blazelight.p4oc.data.remote.dto.CreatePtyRequest
import dev.blazelight.p4oc.domain.model.SessionConnectionState
import dev.blazelight.p4oc.ui.navigation.Screen
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val TAG = "MainTabScreen"

/**
 * Main container for the tab-based UI.
 * Shows TabBar at top and active tab's content below.
 */
@Composable
fun MainTabScreen(
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tabManager: TabManager = koinInject()
    val connectionManager: ConnectionManager = koinInject()
    val coroutineScope = rememberCoroutineScope()
    val theme = LocalOpenCodeTheme.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val tabs by tabManager.tabs.collectAsState()
    val activeTabId by tabManager.activeTabId.collectAsState()
    val showTabWarning by tabManager.showTabWarning.collectAsState()
    val connectionState by connectionManager.connectionState.collectAsState()
    
    var wasEverConnected by remember { mutableStateOf(false) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val eventSource = connectionManager.getEventSource()
            if (eventSource != null && connectionManager.isConnected) {
                eventSource.reconnect()
            }
        }
    }

    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) {
            wasEverConnected = true
        }
        if (!wasEverConnected) return@LaunchedEffect

        when (connectionState) {
            is ConnectionState.Disconnected -> {
                // Hard disconnect - navigate immediately
                // But only if we were previously connected (not initial state)
                if (tabs.isNotEmpty()) {
                    onDisconnect()
                }
            }

            is ConnectionState.Error -> {
                // Transient error - wait before escalating
                // SSE retries every 3s; 15s = ~5 consecutive failures
                delay(15_000)
                // Re-check after delay
                val currentState = connectionManager.connectionState.value
                if (currentState is ConnectionState.Error || currentState is ConnectionState.Disconnected) {
                    connectionManager.disconnect()
                    onDisconnect()
                }
            }

            else -> { /* Connected or Connecting - do nothing */ }
        }
    }
    
    // Create initial tab if needed (no NavController required)
    LaunchedEffect(Unit) {
        if (!tabManager.hasTabs()) {
            val initialTab = TabInstance(TabState())
            tabManager.registerTab(initialTab, focus = true)
        }
    }
    
    // Build tab titles and icons from current routes (updated inside pager pages)
    val tabTitles = remember { mutableStateMapOf<String, String>() }
    val tabIcons = remember { mutableStateMapOf<String, ImageVector>() }
    val tabConnectionStates = remember { mutableStateMapOf<String, SessionConnectionState>() }
    // Track current routes per tab (for PTY cleanup on tab close)
    val tabRoutes = remember { mutableStateMapOf<String, String>() }
    val tabPtyIds = remember { mutableStateMapOf<String, String>() }
    
    // Collect per-tab session connection states (busy/idle/awaiting)
    tabs.forEach { tab ->
        val tabSessionState by tab.connectionState.collectAsState()
        LaunchedEffect(tabSessionState) {
            if (tabSessionState != null) {
                tabConnectionStates[tab.id] = tabSessionState!!
            } else {
                tabConnectionStates.remove(tab.id)
            }
        }
    }
    
    // Snackbar for tab warning
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(showTabWarning) {
        if (showTabWarning) {
            snackbarHostState.showSnackbar(
                message = "Multiple tabs may affect performance",
                duration = SnackbarDuration.Short
            )
            tabManager.dismissTabWarning()
        }
    }
    
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = theme.background,
        contentWindowInsets = WindowInsets(0),
        modifier = modifier
    ) { _ ->
        // We consume the status bar insets here so child Scaffolds don't double-pad.
        // The tab bar gets the status bar padding, then consumeWindowInsets tells
        // downstream composables that the status bar is already accounted for.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .consumeWindowInsets(WindowInsets.statusBars)
        ) {
            // Tab bar (no longer needs its own statusBarsPadding)
            TabBar(
                tabs = tabs,
                activeTabId = activeTabId,
                tabTitles = tabTitles,
                tabIcons = tabIcons,
                tabConnectionStates = tabConnectionStates,
                onTabClick = { tabId ->
                    tabManager.focusTab(tabId)
                },
                onTabClose = { tabId ->
                    coroutineScope.launch {
                        // Check if it's a terminal tab and delete the PTY
                        val route = tabRoutes[tabId]
                        if (route != null && route.startsWith("terminal/")) {
                            val ptyId = tabPtyIds[tabId]
                            if (ptyId != null) {
                                val api = connectionManager.getApi()
                                if (api != null) {
                                    val result = safeApiCall { api.deletePtySession(ptyId) }
                                    if (result is ApiResult.Error) {
                                        AppLog.e(TAG, "Failed to delete PTY $ptyId: ${result.message}")
                                    }
                                }
                            }
                        }
                        
                        // Clean up tracked state for this tab
                        tabRoutes.remove(tabId)
                        tabPtyIds.remove(tabId)
                        tabTitles.remove(tabId)
                        tabIcons.remove(tabId)
                        tabConnectionStates.remove(tabId)
                        
                        tabManager.closeTab(tabId)
                    }
                },
                onAddClick = {
                    tabManager.createTab(focus = true)
                },
            )
            
            // Pager state for swipe between tabs
            val pagerState = rememberPagerState(
                initialPage = tabs.indexOfFirst { it.id == activeTabId }.coerceAtLeast(0),
                pageCount = { tabs.size }
            )
            
            // Sync activeTabId -> pager (when tab clicked or closed)
            LaunchedEffect(activeTabId, tabs.size) {
                val index = tabs.indexOfFirst { it.id == activeTabId }
                if (index >= 0 && pagerState.currentPage != index) {
                    pagerState.animateScrollToPage(index)
                }
            }
            
            // Sync pager -> activeTabId (when user swipes)
            LaunchedEffect(pagerState.settledPage) {
                tabs.getOrNull(pagerState.settledPage)?.let { tab ->
                    if (tab.id != activeTabId) {
                        tabManager.focusTab(tab.id)
                    }
                }
            }
            
            // Tab content area with HorizontalPager for swipe between tabs
            val saveableStateHolder = rememberSaveableStateHolder()
            
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                key = { tabs.getOrNull(it)?.id ?: it.toString() },
                beyondViewportPageCount = 0
            ) { pageIndex ->
                tabs.getOrNull(pageIndex)?.let { tab ->
                    saveableStateHolder.SaveableStateProvider(tab.id) {
                        val navController = rememberNavController()
                        
                        // Track route for title/icon
                        val backStackEntry by navController.currentBackStackEntryAsState()
                        LaunchedEffect(backStackEntry?.destination?.route, tab.sessionTitle) {
                            val route = backStackEntry?.destination?.route
                            tabTitles[tab.id] = getTitleForRoute(route, tab.sessionTitle)
                            tabIcons[tab.id] = getIconForRoute(route)
                            // Track route for PTY cleanup on tab close
                            if (route != null) {
                                tabRoutes[tab.id] = route
                            }
                            // Track PTY ID if on a terminal route
                            val ptyId = backStackEntry?.arguments?.getString(Screen.Terminal.ARG_PTY_ID)
                            if (ptyId != null) {
                                tabPtyIds[tab.id] = ptyId
                            }
                        }
                        
                        val isActive = tab.id == activeTabId
                        TabNavHost(
                            navController = navController,
                            tabManager = tabManager,
                            tabId = tab.id,
                            onDisconnect = onDisconnect,
                            pendingRoute = tab.pendingRoute,
                            onNewFilesTab = {
                                tabManager.createTab(pendingRoute = Screen.Files.route, focus = true)
                            },
                            onNewTerminalTab = {
                                coroutineScope.launch {
                                    val api = connectionManager.getApi() ?: run {
                                        AppLog.e(TAG, "Cannot create terminal: not connected")
                                        snackbarHostState.showSnackbar("Not connected to server")
                                        return@launch
                                    }
                                    val result = safeApiCall { api.createPtySession(CreatePtyRequest()) }
                                    when (result) {
                                        is ApiResult.Success -> {
                                            val ptyId = result.data.id
                                            tabManager.createTab(
                                                pendingRoute = Screen.Terminal.createRoute(ptyId),
                                                focus = true
                                            )
                                        }
                                        is ApiResult.Error -> {
                                            AppLog.e(TAG, "Failed to create PTY: ${result.message}")
                                            snackbarHostState.showSnackbar("Failed to create terminal: ${result.message}")
                                        }
                                    }
                                }
                            },
                            isActiveTab = isActive,
                            onConnectionStateChanged = { state ->
                                tab.updateConnectionState(state)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}
