package dev.blazelight.p4oc.ui.screens.server

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.core.datastore.RecentServer
import dev.blazelight.p4oc.core.network.DiscoveredServer
import dev.blazelight.p4oc.core.network.DiscoveryState
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.opencode.OptimizedThemeLoader
import dev.blazelight.p4oc.ui.components.TuiLoadingIndicator

// Corner radius tokens - moderado, no exagerado
private val CardRadius = 8.dp
private val ButtonRadius = 6.dp
private val InputRadius = 4.dp
private val BadgeRadius = 4.dp
private val DotRadius = 2.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    viewModel: ServerViewModel = koinViewModel(),
    onNavigateToSessions: () -> Unit,
    onNavigateToProjects: () -> Unit,
    onSettings: () -> Unit
) {
    val theme = LocalOpenCodeTheme.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // NO iniciar conexión automática inmediatamente
    // La conexión se iniciará solo después de que el tema esté cargado

    LaunchedEffect(uiState.navigationDestination) {
        when (uiState.navigationDestination) {
            is NavigationDestination.Sessions -> {
                viewModel.clearNavigationDestination()
                onNavigateToSessions()
            }
            is NavigationDestination.Projects -> {
                viewModel.clearNavigationDestination()
                onNavigateToProjects()
            }
            null -> {}
        }
    }

    // Only start connection after theme is ready
    LaunchedEffect(Unit) {
        // Wait for theme to be ready AND user theme is loaded before starting discovery
        while (!OptimizedThemeLoader.isThemeReady()) {
            delay(50) // Check every 50ms
        }
        
        // Additional delay to ensure user theme is completely loaded and entrance animation can complete
        delay(260)
        
        // Now start discovery after theme is ready
        viewModel.startDiscovery()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(theme.accent)
                        )
                        Text(
                            text = stringResource(R.string.server_connect_title),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = theme.text
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onSettings,
                        modifier = Modifier.testTag("server_settings_button")
                    ) {
                        Text(
                            text = "⚙",
                            color = theme.textMuted,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = theme.background
                )
            )
        },
        containerColor = theme.background
    ) { padding ->
        var started by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { started = true }
        val enterSpringDp = spring<Dp>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
        val enterSpringFloat = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
        val alpha by animateFloatAsState(
            targetValue = if (started) 1f else 0f,
            animationSpec = tween(220, easing = FastOutSlowInEasing),
            label = "server_enter_alpha"
        )
        val offsetY by animateDpAsState(
            targetValue = if (started) 0.dp else (-8).dp,
            animationSpec = enterSpringDp,
            label = "server_enter_offset"
        )
        val scaleMain by animateFloatAsState(
            targetValue = if (started) 1f else 0.985f,
            animationSpec = enterSpringFloat,
            label = "server_enter_scale"
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(alpha)
                .offset(y = offsetY)
                .graphicsLayer { scaleX = scaleMain; scaleY = scaleMain },
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Staggered section animations: lightweight alpha + small offset
            var showDiscover by remember { mutableStateOf(false) }
            var showRecent by remember { mutableStateOf(false) }
            var showRemote by remember { mutableStateOf(false) }
            var showHelp by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                // Tiny, non-blocking delays to stagger appearance
                delay(20)
                showDiscover = true
                delay(40)
                showRecent = true
                delay(40)
                showRemote = true
                delay(40)
                showHelp = true
            }

            if (uiState.discoveredServers.isNotEmpty() || uiState.discoveryState == DiscoveryState.SCANNING) {
                val dAlpha by animateFloatAsState(
                    targetValue = if (showDiscover) 1f else 0f,
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                    label = "discover_alpha"
                )
                val dOffset by animateDpAsState(
                    targetValue = if (showDiscover) 0.dp else (-6).dp,
                    animationSpec = enterSpringDp,
                    label = "discover_offset"
                )
                val dScale by animateFloatAsState(
                    targetValue = if (showDiscover) 1f else 0.995f,
                    animationSpec = enterSpringFloat,
                    label = "discover_scale"
                )
                Column(modifier = Modifier.alpha(dAlpha).offset(y = dOffset).graphicsLayer { scaleX = dScale; scaleY = dScale }) {
                    DiscoveredServersSection(
                        servers = uiState.discoveredServers,
                        discoveryState = uiState.discoveryState,
                        isConnecting = uiState.isConnecting,
                        onServerClick = viewModel::connectToDiscoveredServer,
                        onStopClick = viewModel::stopDiscovery
                    )
                }
            }

            if (uiState.recentServers.isNotEmpty()) {
                val rAlpha by animateFloatAsState(
                    targetValue = if (showRecent) 1f else 0f,
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                    label = "recent_alpha"
                )
                val rOffset by animateDpAsState(
                    targetValue = if (showRecent) 0.dp else (-6).dp,
                    animationSpec = enterSpringDp,
                    label = "recent_offset"
                )
                val rScale by animateFloatAsState(
                    targetValue = if (showRecent) 1f else 0.995f,
                    animationSpec = enterSpringFloat,
                    label = "recent_scale"
                )
                Column(modifier = Modifier.alpha(rAlpha).offset(y = rOffset).graphicsLayer { scaleX = rScale; scaleY = rScale }) {
                    RecentServersSection(
                        servers = uiState.recentServers,
                        isConnecting = uiState.isConnecting,
                        onServerClick = viewModel::connectToRecentServer,
                        onRemoveServer = viewModel::removeRecentServer
                    )
                }
            }

            val fAlpha by animateFloatAsState(
                targetValue = if (showRemote) 1f else 0f,
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                label = "form_alpha"
            )
            val fOffset by animateDpAsState(
                targetValue = if (showRemote) 0.dp else (-6).dp,
                animationSpec = enterSpringDp,
                label = "form_offset"
            )
            val fScale by animateFloatAsState(
                targetValue = if (showRemote) 1f else 0.995f,
                animationSpec = enterSpringFloat,
                label = "form_scale"
            )
            Column(modifier = Modifier.alpha(fAlpha).offset(y = fOffset).graphicsLayer { scaleX = fScale; scaleY = fScale }) {
                RemoteServerSection(
                    url = uiState.remoteUrl,
                    username = uiState.username,
                    password = uiState.password,
                    isConnecting = uiState.isConnecting,
                    onUrlChange = viewModel::setRemoteUrl,
                    onUsernameChange = viewModel::setUsername,
                    onPasswordChange = viewModel::setPassword,
                    onConnect = viewModel::connectToRemote
                )
            }

            uiState.error?.let { error ->
                ErrorBanner(error = error)
            }

            val hAlpha by animateFloatAsState(
                targetValue = if (showHelp) 1f else 0f,
                animationSpec = tween(200, easing = FastOutSlowInEasing),
                label = "help_alpha"
            )
            val hOffset by animateDpAsState(
                targetValue = if (showHelp) 0.dp else (-6).dp,
                animationSpec = enterSpringDp,
                label = "help_offset"
            )
            val hScale by animateFloatAsState(
                targetValue = if (showHelp) 1f else 0.995f,
                animationSpec = enterSpringFloat,
                label = "help_scale"
            )
            Column(modifier = Modifier.alpha(hAlpha).offset(y = hOffset).graphicsLayer { scaleX = hScale; scaleY = hScale }) {
                ServerSetupHelpSection()
            }
        }
    }
}

// ── Error Banner ──────────────────────────────────────────────────────────────

@Composable
private fun ErrorBanner(error: String) {
    val theme = LocalOpenCodeTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(theme.error.copy(alpha = 0.08f))
            .border(1.dp, theme.error.copy(alpha = 0.3f), RoundedCornerShape(CardRadius))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "✗",
            color = theme.error,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = error,
            color = theme.error,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}

// ── Section Header ────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(
    title: String,
    trailing: @Composable (() -> Unit)? = null
) {
    val theme = LocalOpenCodeTheme.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Accent indicator dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(DotRadius))
                    .background(theme.accent)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = theme.text
            )
        }
        trailing?.invoke()
    }
}

// ── Discovered Servers ───────────────────────────────────────────────────────

@Composable
private fun DiscoveredServersSection(
    servers: List<DiscoveredServer>,
    discoveryState: DiscoveryState,
    isConnecting: Boolean,
    onServerClick: (DiscoveredServer) -> Unit,
    onStopClick: () -> Unit
) {
    val theme = LocalOpenCodeTheme.current
    val scanning = discoveryState == DiscoveryState.SCANNING

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardRadius),
        colors = CardDefaults.cardColors(containerColor = theme.backgroundElement),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(
                title = stringResource(R.string.discovery_section_title),
                trailing = {
                    if (scanning) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ScanPulse()
                            Text(
                                text = "scanning",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.textMuted
                            )
                            Text(
                                text = "·",
                                color = theme.border,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "stop",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.accent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(BadgeRadius))
                                    .clickable(role = Role.Button) { onStopClick() }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            )

            if (servers.isEmpty() && scanning) {
                Text(
                    text = stringResource(R.string.discovery_scanning_hint),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = theme.textMuted,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            if (servers.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    servers.forEachIndexed { index, server ->
                        DiscoveredServerItem(
                            server = server,
                            isConnecting = isConnecting,
                            onClick = { onServerClick(server) }
                        )
                        if (index < servers.size - 1) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .height(1.dp)
                                    .background(theme.borderSubtle.copy(alpha = 0.5f))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveredServerItem(
    server: DiscoveredServer,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    val theme = LocalOpenCodeTheme.current
    val isTailscale = server.host.startsWith("100.") || server.serviceName.contains(".ts.net")
    val networkLabel = if (isTailscale) "VPN" else "LAN"
    val networkColor = if (isTailscale) theme.info else theme.success

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = !isConnecting, role = Role.Button) { onClick() }
            .background(theme.background.copy(alpha = 0.5f))
            .testTag("discovered_server_${server.serviceName}")
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status indicator with rounded corners
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(networkColor)
        )

        // Server info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.serviceName
                    .removePrefix("opencode-")
                    .ifEmpty { server.serviceName },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = theme.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${server.host}:${server.port}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = theme.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Network badge
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(BadgeRadius))
                .background(networkColor.copy(alpha = 0.12f))
                .border(1.dp, networkColor.copy(alpha = 0.3f), RoundedCornerShape(BadgeRadius))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = networkLabel,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = networkColor,
                fontWeight = FontWeight.Medium
            )
        }

        // Arrow
        Text(
            text = "→",
            color = theme.textMuted,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

// ── Recent Servers ────────────────────────────────────────────────────────────

@Composable
private fun RecentServersSection(
    servers: List<RecentServer>,
    isConnecting: Boolean,
    onServerClick: (RecentServer) -> Unit,
    onRemoveServer: (RecentServer) -> Unit
) {
    val theme = LocalOpenCodeTheme.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardRadius),
        colors = CardDefaults.cardColors(containerColor = theme.backgroundElement),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(title = stringResource(R.string.server_recent_servers))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                servers.forEachIndexed { index, server ->
                    RecentServerItem(
                        server = server,
                        isConnecting = isConnecting,
                        onClick = { onServerClick(server) },
                        onRemove = { onRemoveServer(server) }
                    )
                    if (index < servers.size - 1) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .height(1.dp)
                                .background(theme.borderSubtle.copy(alpha = 0.5f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentServerItem(
    server: RecentServer,
    isConnecting: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val theme = LocalOpenCodeTheme.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = !isConnecting, role = Role.Button) { onClick() }
            .background(theme.background.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(theme.textMuted.copy(alpha = 0.5f))
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = theme.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = server.url,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = theme.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = "×",
            color = theme.textMuted,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(role = Role.Button) { onRemove() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

// ── Remote Server Form ────────────────────────────────────────────────────────

@Composable
private fun RemoteServerSection(
    url: String,
    username: String,
    password: String,
    isConnecting: Boolean,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit
) {
    val theme = LocalOpenCodeTheme.current
    var passwordVisible by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardRadius),
        colors = CardDefaults.cardColors(containerColor = theme.backgroundElement),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(title = stringResource(R.string.server_remote_title))

            Text(
                text = stringResource(R.string.server_remote_description),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = theme.textMuted
            )

            Spacer(Modifier.height(4.dp))

            // URL field
            ModernTextField(
                value = url,
                onValueChange = onUrlChange,
                label = stringResource(R.string.field_server_url),
                placeholder = stringResource(R.string.field_server_url_placeholder),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("server_url_input")
            )

            // Username
            ModernTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = stringResource(R.string.field_username),
                modifier = Modifier.fillMaxWidth()
            )

            // Password
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = {
                    Text(
                        stringResource(R.string.field_password),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (passwordVisible)
                    VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(InputRadius),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                colors = modernTextFieldColors(theme),
                trailingIcon = {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(role = Role.Button) { passwordVisible = !passwordVisible }
                            .padding(8.dp)
                    ) {
                        Text(
                            text = if (passwordVisible) "◉" else "○",
                            color = theme.textMuted,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            )

            Spacer(Modifier.height(4.dp))

            // Modern connect button
            Button(
                onClick = onConnect,
                enabled = url.isNotBlank() && !isConnecting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("server_connect_button"),
                shape = RoundedCornerShape(ButtonRadius),
                colors = ButtonDefaults.buttonColors(
                    containerColor = theme.accent,
                    contentColor = theme.background,
                    disabledContainerColor = theme.accent.copy(alpha = 0.2f),
                    disabledContentColor = theme.textMuted.copy(alpha = 0.5f)
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 2.dp,
                    pressedElevation = 0.dp,
                    disabledElevation = 0.dp
                )
            ) {
                if (isConnecting) {
                    TuiLoadingIndicator()
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.button_connecting),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        "▶  ${stringResource(R.string.button_connect)}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// ── Setup Help Section ────────────────────────────────────────────────────────

@Composable
private fun ServerSetupHelpSection() {
    val theme = LocalOpenCodeTheme.current
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardRadius),
        colors = CardDefaults.cardColors(containerColor = theme.backgroundElement.copy(alpha = 0.7f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(DotRadius))
                            .background(theme.textMuted)
                    )
                    Text(
                        text = stringResource(R.string.server_setup_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        color = theme.textMuted
                    )
                }
                Text(
                    text = if (expanded) "▾" else "▸",
                    fontFamily = FontFamily.Monospace,
                    color = theme.textMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.server_setup_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = theme.textMuted
                    )

                    SetupStep("1", stringResource(R.string.server_setup_step1_title), stringResource(R.string.server_setup_step1_cmd))
                    SetupStep("2", stringResource(R.string.server_setup_step2_title), stringResource(R.string.server_setup_step2_cmd))
                    SetupStep("3", stringResource(R.string.server_setup_step3_title), stringResource(R.string.server_setup_step3_cmd))

                    // Tip box
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(theme.accent.copy(alpha = 0.08f))
                            .border(1.dp, theme.accent.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(theme.accent)
                            )
                            Text(
                                text = stringResource(R.string.server_setup_tip_label),
                                fontFamily = FontFamily.Monospace,
                                color = theme.accent,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Text(
                            text = stringResource(R.string.server_setup_tip_text),
                            fontFamily = FontFamily.Monospace,
                            color = theme.textMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                        ModernCodeBlock(stringResource(R.string.server_setup_find_ip))
                        Text(
                            text = stringResource(R.string.server_setup_test_hint),
                            fontFamily = FontFamily.Monospace,
                            color = theme.textMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupStep(number: String, title: String, command: String) {
    val theme = LocalOpenCodeTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(theme.accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = number,
                    fontFamily = FontFamily.Monospace,
                    color = theme.accent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = title,
                fontFamily = FontFamily.Monospace,
                color = theme.text,
                style = MaterialTheme.typography.bodySmall
            )
        }
        ModernCodeBlock(command = command)
    }
}

@Composable
private fun ModernCodeBlock(command: String) {
    val theme = LocalOpenCodeTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.background)
            .border(1.dp, theme.border.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$",
            fontFamily = FontFamily.Monospace,
            color = theme.textMuted,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = command,
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            color = theme.accent,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

@Composable
private fun ModernTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = {
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall
            )
        },
        placeholder = if (placeholder.isNotEmpty()) ({
            Text(
                placeholder,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = theme.textMuted.copy(alpha = 0.6f)
            )
        }) else null,
        singleLine = true,
        modifier = modifier,
        shape = RoundedCornerShape(InputRadius),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        colors = modernTextFieldColors(theme)
    )
}

@Composable
private fun modernTextFieldColors(theme: dev.blazelight.p4oc.ui.theme.opencode.OpenCodeTheme) =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = theme.accent,
        unfocusedBorderColor = theme.border,
        focusedLabelColor = theme.accent,
        unfocusedLabelColor = theme.textMuted,
        cursorColor = theme.accent,
        focusedTextColor = theme.text,
        unfocusedTextColor = theme.text,
        focusedContainerColor = theme.background.copy(alpha = 0.5f),
        unfocusedContainerColor = theme.background.copy(alpha = 0.3f)
    )

@Composable
private fun ScanPulse() {
    val theme = LocalOpenCodeTheme.current
    val infiniteTransition = rememberInfiniteTransition(label = "scanPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(2.dp))
            .alpha(alpha)
            .background(theme.accent)
    )
}

@Composable
private fun ScanningIndicator() {
    val theme = LocalOpenCodeTheme.current
    val infiniteTransition = rememberInfiniteTransition(label = "scanning")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanPulse"
    )
    Text(
        text = "● ${stringResource(R.string.discovery_scanning)}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = theme.accent.copy(alpha = alpha)
    )
}
