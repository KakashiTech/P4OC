package dev.blazelight.p4oc.ui.screens.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.ui.components.TuiConfirmDialog
import dev.blazelight.p4oc.ui.components.TuiAlertDialog
import dev.blazelight.p4oc.ui.components.TuiInputDialog
import dev.blazelight.p4oc.ui.components.TuiButton
import dev.blazelight.p4oc.ui.components.TuiTextButton
import dev.blazelight.p4oc.ui.components.TuiTerminalMenu
import dev.blazelight.p4oc.ui.components.TuiTerminalMenuItem
import dev.blazelight.p4oc.ui.components.TuiTerminalMenuDivider
import dev.blazelight.p4oc.ui.components.TuiLoadingScreen
import dev.blazelight.p4oc.ui.components.TuiLoadingIndicator
import dev.blazelight.p4oc.domain.model.Session
import dev.blazelight.p4oc.domain.model.SessionStatus
import dev.blazelight.p4oc.ui.theme.ProjectColors
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.opencode.OpenCodeTheme
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.ui.theme.TuiCodeFontSize
import dev.blazelight.p4oc.ui.components.TuiTopBar
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.components.TuiCard
import dev.blazelight.p4oc.ui.components.TuiSnackbar
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

// Terminal style dimensions
private val CardRadius = 0.dp
private val BadgeRadius = 0.dp
private val DotRadius = 0.dp
private val ButtonRadius = 0.dp
private val TerminalLineHeight = 2.dp

private data class SessionNode(
    val sessionWithProject: SessionWithProject,
    val children: List<SessionNode>
) {
    val totalDescendants: Int by lazy { children.size + children.sumOf { it.totalDescendants } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    viewModel: SessionListViewModel = koinViewModel(),
    filterProjectId: String? = null,
    onSessionClick: (sessionId: String, directory: String?) -> Unit,
    onNewSession: (sessionId: String, directory: String?) -> Unit,
    onSettings: () -> Unit,
    onProjects: () -> Unit = {},
    onProjectClick: (projectId: String) -> Unit = {},
    onViewChanges: (sessionId: String) -> Unit = {},
    onNavigateBack: (() -> Unit)? = null,
    showTopBar: Boolean = true
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showNewSessionDialog by remember { mutableStateOf(false) }
    var showNewSessionCustomDir by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<Session?>(null) }
    var showRenameDialog by remember { mutableStateOf<Session?>(null) }
    val context = LocalContext.current
    
    val displayedSessions = remember(uiState.sessions, filterProjectId, uiState.selectedWorkspace, uiState.showArchived) {
        val byProject = if (filterProjectId != null) {
            uiState.sessions.filter { it.projectId == filterProjectId || it.projectId == null }
        } else {
            uiState.sessions
        }
        val byArchive = if (uiState.showArchived) {
            byProject.filter { it.archived }
        } else {
            byProject.filter { !it.archived }
        }
        if (uiState.selectedWorkspace != null) {
            when (uiState.selectedWorkspace) {
                "Work" -> byArchive.filter { it.projectId != null }
                else -> byArchive.filter { it.workspace == uiState.selectedWorkspace }
            }
        } else {
            byArchive
        }
    }

    val projectName = remember(uiState.projects, filterProjectId) {
        if (filterProjectId != null) {
            uiState.projects.find { it.id == filterProjectId }?.name
        } else null
    }

    // Refresh session statuses after returning to this screen (e.g. pop from Chat/Settings).
    // Delayed past the transition duration so API-driven recompositions don’t compete
    // with the pop animation and cause frame drops / visual cuts.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            kotlinx.coroutines.delay(320) // wait for pop transition to finish (~250ms spring)
            viewModel.refreshOnResume()
        }
    }

    LaunchedEffect(uiState.newSessionId, uiState.newSessionDirectory) {
        uiState.newSessionId?.let { sessionId ->
            onNewSession(sessionId, uiState.newSessionDirectory)
            viewModel.clearNewSession()
        }
    }

    LaunchedEffect(uiState.shareUrl) {
        uiState.shareUrl?.let { shareUrl ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareUrl)
            }
            context.startActivity(Intent.createChooser(intent, null))
            viewModel.clearShareUrl()
        }
    }

    val theme = LocalOpenCodeTheme.current

    // Pre-compute session tree with derivedStateOf for optimal recompositions
    val expandedSessions = remember { mutableStateMapOf<String, Boolean>() }
    val sessionTree by remember(displayedSessions) {
        derivedStateOf { buildSessionTree(displayedSessions) }
    }

    Scaffold(
        containerColor = theme.background,
        topBar = {
            if (showTopBar) {
                SessionsTopBar(
                    projectName = projectName,
                    isSelectionMode = uiState.isSelectionMode,
                    selectedCount = uiState.selectedSessionIds.size,
                    showArchived = uiState.showArchived,
                    archivedCount = uiState.sessions.count { it.archived },
                    onNavigateBack = onNavigateBack,
                    onProjects = onProjects,
                    onRefresh = viewModel::refresh,
                    onSettings = onSettings,
                    onSelectModeToggle = if (uiState.isSelectionMode) viewModel::exitSelectionMode else viewModel::enterSelectionMode,
                    onSelectAll = viewModel::selectAllSessions,
                    onArchiveSelected = viewModel::archiveSelectedSessions,
                    onDeleteSelected = viewModel::deleteSelectedSessions,
                    onArchiveToggle = viewModel::toggleShowArchived
                )
            }
        },
        floatingActionButton = {
            if (!uiState.isSelectionMode) {
                Text(
                    text = "+",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = theme.accent,
                    modifier = Modifier
                        .clickable { showNewSessionDialog = true }
                        .testTag("fab_new_session")
                        .padding(16.dp)
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (uiState.isLoading && displayedSessions.isEmpty()) {
                TuiLoadingScreen(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                // OPTIMIZED LazyColumn for smooth session scrolling
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("sessions_list"),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 12.dp,
                        end = 16.dp,
                        bottom = if (uiState.isSelectionMode) 64.dp else 12.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Animated PocketCode Logo Header — isolated on its own GPU layer
                    // so its 45ms typewriter recompositions don't invalidate the parent list
                    item(
                        key = "logo_header",
                        contentType = "header"
                    ) {
                        Box(modifier = Modifier) {
                            PocketCodeLogoHeader()
                        }
                    }

                    // Workspace filter bar
                    if (uiState.workspaces.size > 1 && filterProjectId == null) {
                        item(key = "workspace_bar", contentType = "actions") {
                            WorkspaceFilterBar(
                                workspaces = uiState.workspaces,
                                selectedWorkspace = uiState.selectedWorkspace,
                                onSelectWorkspace = viewModel::selectWorkspace,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // Pinned quick actions (only on unfiltered list)
                    if (filterProjectId == null) {
                        item(key = "quick_actions_row", contentType = "actions") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                QuickActionCard(
                                    icon = Icons.Default.PlayArrow,
                                    label = stringResource(R.string.sessions_quick_global),
                                    onClick = { viewModel.createSession(title = "global", directory = null) },
                                    modifier = Modifier.weight(1f).testTag("quick_action_global")
                                )
                                QuickActionCard(
                                    icon = Icons.Default.CreateNewFolder,
                                    label = stringResource(R.string.sessions_quick_custom),
                                    onClick = {
                                        showNewSessionCustomDir = true
                                        showNewSessionDialog = true
                                    },
                                    modifier = Modifier.weight(1f).testTag("quick_action_custom")
                                )
                            }
                        }
                    }

                    if (displayedSessions.isEmpty() && filterProjectId == null) {
                        item(key = "empty_hint") {
                            EmptySessionsHint(stringResource(R.string.sessions_empty_hint))
                        }
                    } else if (displayedSessions.isEmpty()) {
                        item(key = "empty_hint") {
                            EmptySessionsHint(stringResource(R.string.sessions_empty_title))
                        }
                    } else {
                        items(
                            items = sessionTree,
                            key = { it.sessionWithProject.session.id },
                            contentType = { node -> if (node.children.isEmpty()) "leaf" else "branch" }
                        ) { node ->
                            SessionTreeNode(
                                node = node,
                                depth = 0,
                                expandedSessions = expandedSessions,
                                sessionStatuses = uiState.sessionStatuses,
                                showProjectChip = filterProjectId == null,
                                workspaces = uiState.workspaces,
                                isSelectionMode = uiState.isSelectionMode,
                                selectedIds = uiState.selectedSessionIds,
                                onSessionClick = { session -> onSessionClick(session.id, session.directory) },
                                onToggleSelection = viewModel::toggleSessionSelection,
                                onSelectSession = { session ->
                                    viewModel.enterSelectionMode()
                                    viewModel.toggleSessionSelection(session.id)
                                },
                                onDeleteSession = { showDeleteDialog = it },
                                onRenameSession = { showRenameDialog = it },
                                onShareSession = { session ->
                                    if (session.shareUrl != null) {
                                        viewModel.unshareSession(session.id, session.directory)
                                    } else {
                                        viewModel.shareSession(session.id, session.directory)
                                    }
                                },
                                onViewChanges = { session ->
                                    onViewChanges(session.id)
                                },
                                onSummarizeSession = { session ->
                                    viewModel.summarizeSession(session.id, session.directory)
                                },
                                onAssignWorkspace = { session, workspace ->
                                    viewModel.overrideWorkspace(session.id, workspace)
                                },
                                onProjectClick = onProjectClick,
                                onToggleExpand = { id ->
                                    expandedSessions[id] = !(expandedSessions[id] ?: false)
                                }
                            )
                        }
                    }
                }
            }

            // Selection mode bottom bar — terminal-style
            if (uiState.isSelectionMode) {
                val selectedColor = theme.accent
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(theme.backgroundPanel.copy(alpha = 0.92f))
                ) {
                    // Top border: └─ SELECT ──────────────┘
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("└", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = theme.border)
                        Box(Modifier.width(6.dp).height(1.dp).background(theme.border))
                        Text("─", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = theme.border)
                        Text("SELECT", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = selectedColor)
                        Text("─", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = theme.border)
                        Box(Modifier.weight(1f).height(1.dp).background(theme.border))
                        Text(
                            text = "${uiState.selectedSessionIds.size}",
                            fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = selectedColor
                        )
                        Text("─", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = theme.border)
                        Box(Modifier.width(6.dp).height(1.dp).background(theme.border))
                        Text("┘", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = theme.border)
                    }
                    // Action buttons row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Count label
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("│", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = theme.border)
                            val noun = if (uiState.selectedSessionIds.size == 1) "session" else "sessions"
                            Text(
                                text = "${uiState.selectedSessionIds.size} $noun",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = theme.textMuted
                            )
                        }

                        // Archive button
                        TuiBottomBarButton(
                            symbol = "🖫",
                            label = "archive",
                            color = theme.warning,
                            onClick = { viewModel.archiveSelectedSessions() }
                        )

                        // Delete button
                        TuiBottomBarButton(
                            symbol = "✗",
                            label = "delete",
                            color = theme.error,
                            onClick = { viewModel.deleteSelectedSessions() }
                        )

                        // Cancel button
                        TuiBottomBarButton(
                            symbol = "esc",
                            label = "cancel",
                            color = theme.textMuted,
                            onClick = { viewModel.exitSelectionMode() }
                        )
                    }
                }
            }

            uiState.error?.let { error ->
                TuiSnackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = viewModel::clearError, shape = RoundedCornerShape(4.dp)) {
                            Text(
                                stringResource(R.string.sessions_dismiss),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                ) {
                    Text(error, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }

    if (showNewSessionDialog) {
        NewSessionDialog(
            projects = uiState.projects,
            defaultProjectId = filterProjectId,
            initialUseCustomDirectory = showNewSessionCustomDir,
            onDismiss = {
                showNewSessionDialog = false
                showNewSessionCustomDir = false
            },
            onCreate = { title, directory ->
                viewModel.createSession(title, directory)
                showNewSessionDialog = false
                showNewSessionCustomDir = false
            },
            onScan = { directory ->
                viewModel.discoverSessions(directory)
                showNewSessionDialog = false
                showNewSessionCustomDir = false
            }
        )
    }

    showDeleteDialog?.let { session ->
        TuiConfirmDialog(
            onDismissRequest = { showDeleteDialog = null },
            onConfirm = { viewModel.deleteSession(session.id, session.directory) },
            title = stringResource(R.string.sessions_delete_title),
            message = stringResource(R.string.sessions_delete_confirm, session.title),
            confirmText = stringResource(R.string.sessions_delete),
            dismissText = stringResource(R.string.button_cancel),
            isDestructive = true
        )
    }

    showRenameDialog?.let { session ->
        TuiInputDialog(
            onDismissRequest = { showRenameDialog = null },
            onConfirm = { newTitle ->
                viewModel.renameSession(session.id, newTitle, session.directory)
                showRenameDialog = null
            },
            title = stringResource(R.string.sessions_rename_title),
            initialValue = session.title,
            label = stringResource(R.string.sessions_title_optional),
            confirmText = stringResource(R.string.sessions_rename),
            dismissText = stringResource(R.string.button_cancel)
        )
    }
}

private fun buildSessionTree(sessions: List<SessionWithProject>): List<SessionNode> {
    val childrenByParent = sessions
        .mapNotNull { swp -> swp.session.parentID?.let { parentId -> parentId to swp } }
        .groupBy({ it.first }, { it.second })

    val childIds = childrenByParent.values.flatten().map { it.session.id }.toSet()

    fun buildNode(sessionWithProject: SessionWithProject): SessionNode {
        val children = childrenByParent[sessionWithProject.session.id]?.map { buildNode(it) } ?: emptyList()
        return SessionNode(sessionWithProject, children)
    }

    // Show root sessions AND any session the server might have given a parentID
    // that has no actual parent in the list (e.g. fresh sessions in custom directories
    // where the server assigns a synthetic parentID).
    return sessions
        .filter { it.session.parentID == null || it.session.id !in childIds }
        .map { buildNode(it) }
}

@Composable
private fun SessionTreeNode(
    node: SessionNode,
    depth: Int,
    expandedSessions: MutableMap<String, Boolean>,
    sessionStatuses: Map<String, SessionStatus>,
    showProjectChip: Boolean,
    workspaces: List<String>,
    isSelectionMode: Boolean,
    selectedIds: Set<String>,
    onSessionClick: (Session) -> Unit,
    onToggleSelection: (String) -> Unit,
    onSelectSession: (Session) -> Unit,
    onDeleteSession: (Session) -> Unit,
    onRenameSession: (Session) -> Unit,
    onShareSession: (Session) -> Unit,
    onViewChanges: (Session) -> Unit,
    onSummarizeSession: (Session) -> Unit,
    onAssignWorkspace: (Session, String) -> Unit,
    onProjectClick: (String) -> Unit,
    onToggleExpand: (String) -> Unit
) {
    val swp = node.sessionWithProject
    val session = swp.session
    val isExpanded = expandedSessions[session.id] ?: false
    val hasChildren = node.children.isNotEmpty()
    val indentPadding: Dp = Sizing.treeIndent * depth
    
    Column(modifier = Modifier.padding(start = indentPadding)) {
        // Memoize all lambdas so SessionCard gets stable references and can skip recomposition
        // Prefix with _ to avoid shadowing the parent function parameter names
        val _onClick = remember(session, onSessionClick) { { onSessionClick(session) } }
        val _onDelete = remember(session, onDeleteSession) { { onDeleteSession(session) } }
        val _onRename = remember(session, onRenameSession) { { onRenameSession(session) } }
        val _onShare = remember(session, onShareSession) { { onShareSession(session) } }
        val _onViewChanges = remember(session, onViewChanges) { { onViewChanges(session) } }
        val _onSummarize = remember(session, onSummarizeSession) { { onSummarizeSession(session) } }
        val _onToggleSel = remember(session, onToggleSelection) { { onToggleSelection(session.id) } }
        val _onSelect = remember(session, onSelectSession) { { onSelectSession(session) } }
        val _onAssign = remember(session, onAssignWorkspace) { { ws: String -> onAssignWorkspace(session, ws) } }
        val _onExpand = remember(session, onToggleExpand) {
            if (hasChildren) { { onToggleExpand(session.id) } } else null
        }
        SessionCard(
            session = session,
            projectId = swp.projectId,
            projectName = swp.projectName,
            showProjectChip = showProjectChip,
            status = sessionStatuses[session.id],
            isShared = session.shareUrl != null,
            isSelectionMode = isSelectionMode,
            isSelected = session.id in selectedIds,
            onToggleSelection = _onToggleSel,
            onSelectSession = _onSelect,
            workspaces = workspaces,
            workspace = swp.workspace,
            onAssignWorkspace = _onAssign,
            onClick = _onClick,
            onDelete = _onDelete,
            onRename = _onRename,
            onShare = _onShare,
            onViewChanges = _onViewChanges,
            onSummarize = _onSummarize,
            onProjectClick = onProjectClick,
            childCount = node.totalDescendants,
            isExpanded = isExpanded,
            onExpandToggle = _onExpand,
            isSubAgent = depth > 0
        )
        
        AnimatedVisibility(
            visible = isExpanded && hasChildren,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier.padding(top = 8.dp, start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                    node.children.forEach { child ->
                    SessionTreeNode(
                        node = child,
                        depth = depth + 1,
                        expandedSessions = expandedSessions,
                        sessionStatuses = sessionStatuses,
                        showProjectChip = showProjectChip,
                        workspaces = workspaces,
                        isSelectionMode = isSelectionMode,
                        selectedIds = selectedIds,
                        onSessionClick = onSessionClick,
                        onToggleSelection = onToggleSelection,
                        onSelectSession = onSelectSession,
                        onDeleteSession = onDeleteSession,
                        onRenameSession = onRenameSession,
                        onShareSession = onShareSession,
                        onViewChanges = onViewChanges,
                        onSummarizeSession = onSummarizeSession,
                        onAssignWorkspace = onAssignWorkspace,
                        onProjectClick = onProjectClick,
                        onToggleExpand = onToggleExpand
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: Session,
    projectId: String?,
    projectName: String?,
    showProjectChip: Boolean,
    status: SessionStatus?,
    isShared: Boolean,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit = {},
    onSelectSession: () -> Unit = {},
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onViewChanges: () -> Unit,
    onSummarize: () -> Unit,
    workspaces: List<String>,
    workspace: String,
    onAssignWorkspace: (String) -> Unit,
    onProjectClick: (String) -> Unit,
    childCount: Int = 0,
    isExpanded: Boolean = false,
    onExpandToggle: (() -> Unit)? = null,
    isSubAgent: Boolean = false
) {
    val theme = LocalOpenCodeTheme.current
    val isBusy = status is SessionStatus.Busy
    val isRetrying = status is SessionStatus.Retry
    var showContextMenu by remember { mutableStateOf(false) }
    var showWorkspaceDialog by remember { mutableStateOf(false) }

    val cardColor by remember(isSelectionMode, isSelected, isBusy, isRetrying, isSubAgent, theme) {
        derivedStateOf {
            when {
                isSelectionMode && isSelected -> theme.accent.copy(alpha = 0.18f)
                isBusy    -> theme.accent.copy(alpha = 0.08f)
                isRetrying -> theme.error.copy(alpha = 0.08f)
                isSubAgent -> theme.backgroundElement.copy(alpha = 0.6f)
                else      -> theme.backgroundElement
            }
        }
    }
    val indicatorColor by remember(isSelectionMode, isSelected, isBusy, isRetrying, theme) {
        derivedStateOf {
            when {
                isSelectionMode && isSelected -> theme.accent
                isBusy    -> theme.accent
                isRetrying -> theme.error
                else      -> theme.success
            }
        }
    }

    // Terminal TUI style with left accent bar - wrapped in Box for menu positioning
    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .combinedClickable(
                    onClick = if (isSelectionMode) onToggleSelection else onClick,
                    onLongClick = { if (!isSelectionMode) showContextMenu = true },
                    role = Role.Button
                )
                .drawBehind {
                    val barWidth = 4.dp.toPx()
                    drawRect(
                        color = indicatorColor,
                        size = Size(barWidth, size.height)
                    )
                    drawRect(
                        color = cardColor,
                        topLeft = Offset(barWidth, 0f),
                        size = Size(size.width - barWidth, size.height)
                    )
                }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp)
            ) {
            Row(
                modifier = Modifier
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Selection checkbox
                if (isSelectionMode) {
                    Text(
                        text = if (isSelected) "[✓]" else "[ ]",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) theme.accent else theme.textMuted.copy(alpha = 0.5f),
                        modifier = Modifier.clickable(role = Role.Button) { onToggleSelection() }
                    )
                }

                // Status prefix icon
                val statusChar = when {
                    isBusy -> "▶"
                    isRetrying -> "⚠"
                    isSubAgent -> "├"
                    else -> if (status != null) "●" else "○"
                }
                Text(
                    text = statusChar,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = indicatorColor.copy(alpha = if (isSubAgent) 0.5f else 1f)
                )

                // Expand toggle (parent sessions with children)
                if (onExpandToggle != null) {
                    Text(
                        text = if (isExpanded) "▼" else "▶",
                        modifier = Modifier.clickable(role = Role.Button) { onExpandToggle() },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.textMuted
                    )
                } else if (isSubAgent) {
                    Text(
                        text = "╰",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.textMuted
                    )
                }

                // Session title
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isBusy || (isSelectionMode && isSelected)) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isBusy -> theme.accent
                        isSelectionMode && isSelected -> theme.accent
                        else -> theme.text
                    },
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Menu indicator
                if (!isSelectionMode) {
                    Text(
                        text = "≡",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.textMuted.copy(alpha = 0.5f),
                        modifier = Modifier.clickable(role = Role.Button) { showContextMenu = true }
                    )
                }
            }

            // Bottom info line
            Row(
                modifier = Modifier
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "[${session.id.take(6)}]",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = theme.textMuted.copy(alpha = 0.7f)
                )

                SessionStatusIndicator(status = status)

                Text(
                    text = formatDateTime(session.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = theme.textMuted
                )

                if (childCount > 0) {
                    MetaBadge(
                        text = "$childCount sub",
                        color = theme.info
                    )
                }

                session.summary?.let { summary ->
                    if (summary.additions > 0) {
                        Text(
                            text = "+${summary.additions}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = theme.success
                        )
                    }
                    if (summary.deletions > 0) {
                        Text(
                            text = "-${summary.deletions}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = theme.error
                        )
                    }
                }

                if (session.shareUrl != null) {
                    MetaBadge(text = stringResource(R.string.sessions_shared_badge), color = theme.info)
                }

                MetaBadge(text = workspace, color = theme.textMuted)

                // Project chip on far right
                if (showProjectChip && projectId != null && !projectName.isNullOrEmpty()) {
                    ProjectChip(
                        projectId = projectId,
                        projectName = projectName,
                        onClick = { onProjectClick(projectId) }
                    )
                }
            }
            }
        }

        // Workspace assignment dialog
        if (showWorkspaceDialog) {
            Dialog(onDismissRequest = { showWorkspaceDialog = false }) {
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Assign workspace",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.height(8.dp))
                        workspaces.forEach { ws ->
                            TextButton(
                                onClick = {
                                    onAssignWorkspace(ws)
                                    showWorkspaceDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (ws == workspace) "● $ws" else "○ $ws",
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }

        // Long-press context menu (terminal-style ASCII) - anchored to top-end of card
        TuiTerminalMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            modifier = Modifier.align(Alignment.TopEnd),
            offset = DpOffset((-4).dp, 4.dp),
            title = session.title.take(16)
        ) {
            TuiTerminalMenuItem(
                text = "Rename",
                symbol = "✎",
                onClick = { showContextMenu = false; onRename() }
            )
            TuiTerminalMenuItem(
                text = "Changes",
                symbol = "±",
                onClick = { showContextMenu = false; onViewChanges() }
            )
            TuiTerminalMenuItem(
                text = "Summarize",
                symbol = "Σ",
                onClick = { showContextMenu = false; onSummarize() }
            )
            if (isShared) {
                TuiTerminalMenuItem(
                    text = "Unshare",
                    symbol = "◈",
                    onClick = { showContextMenu = false; onShare() }
                )
            } else {
                TuiTerminalMenuItem(
                    text = "Share",
                    symbol = "◈",
                    onClick = { showContextMenu = false; onShare() }
                )
            }
            TuiTerminalMenuDivider()
            TuiTerminalMenuItem(
                text = "Select",
                symbol = "☰",
                onClick = { showContextMenu = false; onSelectSession() }
            )
            TuiTerminalMenuDivider()
            TuiTerminalMenuItem(
                text = "Workspace",
                symbol = "⌗",
                onClick = { showContextMenu = false; showWorkspaceDialog = true }
            )
            TuiTerminalMenuDivider()
            TuiTerminalMenuItem(
                text = "Delete",
                symbol = "×",
                onClick = { showContextMenu = false; onDelete() },
                isDestructive = true
            )
        }
    }
}

@Composable
private fun ProjectChip(
    projectId: String,
    projectName: String,
    onClick: () -> Unit
) {
    val bgColor = ProjectColors.colorForProject(projectId)
    val textColor = ProjectColors.textColorForProject(projectId)
    Box(
        modifier = Modifier
            .background(bgColor)
            .clickable(role = Role.Button, onClick = onClick)
            .widthIn(max = Sizing.chipMaxWidth)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = projectName,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MetaBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.3f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun BusyDot(color: androidx.compose.ui.graphics.Color) {
    val transition = rememberInfiniteTransition(label = "busyDot")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "alpha"
    )
    Text(
        text = "▶",
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        color = color.copy(alpha = alpha)
    )
}

@Composable
private fun SessionStatusIndicator(status: SessionStatus?) {
    val theme = LocalOpenCodeTheme.current
    when (status) {
        is SessionStatus.Busy -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "▶",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.accent
                )
                Text(
                    text = stringResource(R.string.session_working),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = theme.accent
                )
            }
        }
        is SessionStatus.Retry -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "↻",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.error
                )
                Text(
                    text = stringResource(R.string.session_retry_format, status.attempt),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = theme.error
                )
            }
        }
        is SessionStatus.Idle -> {
            // idle — dot handled by SessionCard indicator
        }
        null -> {}
    }
}

@Composable
private fun EmptySessionsHint(text: String) {
    val theme = LocalOpenCodeTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(theme.backgroundElement),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.ChatBubbleOutline,
                    contentDescription = null,
                    tint = theme.textMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = theme.textMuted
            )
        }
    }
}

// Animated PocketCode Logo Header - ultra terminal style with typewriter effect
@Composable
private fun PocketCodeLogoHeader(
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current

    // Funny rotating terminal commands
    val funnyCommands = remember {
        listOf(
            "make coffee",
            "sudo apt-get life",
            "git commit -m \"oops\"",
            "rm -rf /problems",
            "./fix-bugs.sh",
            "npm install happiness",
            "docker run sanity",
            "chmod 777 world",
            "echo \"hello world\"",
            "ping 127.0.0.1",
            "while(alive) { code() }",
            "import antigravity",
            "System.exit(0)",
            ":(){ :|&: };:",
            "tree /dev/brain",
            "cat /etc/motd",
            "man happiness",
            "alias sleep=code",
            "kill -9 procrastination",
            "service life restart"
        )
    }

    // Glow derived from wall-clock time — zero Compose animation overhead
    val glowPosition by remember {
        derivedStateOf {
            val period = 4000L
            val phase = (System.currentTimeMillis() % period).toFloat() / period
            if (phase < 0.5f) 2f * phase else 2f * (1f - phase)
        }
    }
    val accentGlow by remember {
        derivedStateOf {
            val period = 2000L
            val phase = (System.currentTimeMillis() % period).toFloat() / period
            0.3f + 0.6f * (if (phase < 0.5f) 2f * phase else 2f * (1f - phase))
        }
    }

    // Animation states
    var typedLogo by remember { mutableStateOf("") }
    var displayedCommand by remember { mutableStateOf(" ") }
    var currentCommandIndex by remember { mutableIntStateOf(0) }
    var cursorVisible by remember { mutableStateOf(true) }
    var animationPhase by remember { mutableIntStateOf(0) } // 0=typing logo, 1=blink logo, 2=typing cmd, 3=show cmd, 4=clearing

    // Main animation loop - single controlled coroutine
    LaunchedEffect(Unit) {
        val logoText = "PocketCode"

        // Phase 0: Type logo
        animationPhase = 0
        logoText.forEachIndexed { i, _ ->
            typedLogo = logoText.substring(0, i + 1)
            delay(60)
        }

        // Phase 1: Blink cursor after logo
        animationPhase = 1
        repeat(3) {
            cursorVisible = !cursorVisible
            delay(400)
        }
        cursorVisible = true
        delay(500)

        // Loop through commands forever
        while (true) {
            val command = funnyCommands[currentCommandIndex]

            // Phase 2: Type command
            animationPhase = 2
            command.forEachIndexed { i, _ ->
                displayedCommand = command.substring(0, i + 1)
                delay(45)
            }

            // Phase 3: Show command with blinking cursor
            animationPhase = 3
            repeat(5) {
                cursorVisible = !cursorVisible
                delay(350)
            }
            cursorVisible = true
            delay(600)

            // Next command - keep text visible while blinking then clear
            repeat(2) {
                cursorVisible = !cursorVisible
                delay(250)
            }

            // Clear and move to next - maintain space to prevent resizing
            currentCommandIndex = (currentCommandIndex + 1) % funnyCommands.size
            displayedCommand = " "  // Use space instead of empty to maintain layout
            delay(150)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column {
            // Top connection line
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(2.dp)
                        .background(theme.accent.copy(alpha = accentGlow))
                )
                Text(
                    text = "┬",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.accent.copy(alpha = accentGlow)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(theme.border.copy(alpha = 0.3f))
                )
            }

            // Terminal-style header container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = Spacing.hairline,
                        color = theme.border.copy(alpha = 0.6f)
                    )
                    .background(
                        color = theme.backgroundElement.copy(alpha = 0.12f)
                    )
                    .padding(vertical = Spacing.xs),
                contentAlignment = Alignment.Center
            ) {
                // Subtle terminal scanline effect
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    theme.accent.copy(alpha = 0.03f),
                                    androidx.compose.ui.graphics.Color.Transparent,
                                    androidx.compose.ui.graphics.Color.Transparent,
                                    theme.accent.copy(alpha = 0.02f)
                                )
                            )
                        )
                )

                // Terminal-style content
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    // Compact terminal header line
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Compact terminal window controls
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(theme.error.copy(alpha = 0.9f))
                            )
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(theme.warning.copy(alpha = 0.9f))
                            )
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(theme.success.copy(alpha = 0.9f))
                            )
                        }
                        
                        // Compact terminal title
                        Text(
                            text = "p4oc@terminal",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.85
                            ),
                            color = theme.text.copy(alpha = 0.6f)
                        )
                        
                        Spacer(Modifier.width(24.dp)) // Balance the controls
                    }
                    
                    // Main logo section - compact terminal style
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.md),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Compact terminal prompt
                        Text(
                            text = "$",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = theme.accent.copy(alpha = accentGlow)
                        )
                        
                        Spacer(Modifier.width(Spacing.xs))
                        
                        // Compact logo brackets
                        Text(
                            text = "[",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold
                            ),
                            color = theme.accent.copy(alpha = accentGlow)
                        )
                        
                        // Compact core symbol
                        Text(
                            text = "◈",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleMedium,
                            color = theme.accent
                        )
                        
                        Text(
                            text = "]",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold
                            ),
                            color = theme.accent.copy(alpha = accentGlow)
                        )
                        
                        // Compact terminal pipe separator
                        Text(
                            text = " ",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleSmall,
                            color = theme.border.copy(alpha = 0.6f)
                        )
                        
                        // Compact typewriter effect with reduced spacing
                        Text(
                            text = typedLogo,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp
                            ),
                            color = theme.text
                        )
                        
                        // Compact blinking cursor - always takes space
                        Text(
                            text = "▊",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (cursorVisible && animationPhase <= 1) {
                                theme.accent.copy(alpha = 0.9f)
                            } else {
                                Color.Transparent // Takes space but invisible
                            }
                        )
                    }

                    // Row 2: Terminal prompt + animated command (estilo terminal pegado a la izquierda)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        // Prompt
                        Text(
                            text = "~$",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.textMuted.copy(alpha = 0.5f)
                        )

                        // Spacer para separar prompt del comando
                        Box(modifier = Modifier.width(6.dp))

                        // Command text
                        Text(
                            text = displayedCommand,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.success.copy(alpha = 0.9f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        // Blinking cursor after command - always takes space
                        Text(
                            text = "█",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (cursorVisible && animationPhase >= 2 && displayedCommand.isNotEmpty()) {
                                theme.accent.copy(alpha = 0.6f)
                            } else {
                                Color.Transparent // Takes space but invisible
                            }
                        )
                    }
                }
            }

            // Master unified connector system - topbar integration
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "├",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.accent.copy(alpha = 0.7f) // Matches topbar connector
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    theme.border.copy(alpha = 0.3f),
                                    theme.accent.copy(alpha = 0.4f),
                                    theme.border.copy(alpha = 0.3f)
                                )
                            )
                        )
                )
                Text(
                    text = "┤",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.border.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun QuickActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    val isGlobal = icon == Icons.Default.PlayArrow
    val symbol = if (isGlobal) "▶" else "►"
    val color = if (isGlobal) theme.success else theme.accent

    Row(
        modifier = modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Animated/cycling bracket effect
        Text(
            text = if (isGlobal) "┌" else "╭",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = color.copy(alpha = 0.6f)
        )

        Column {
            Text(
                text = "$symbol $label",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
            Text(
                text = if (isGlobal) "~/global" else "~/custom",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = theme.textMuted.copy(alpha = 0.7f)
            )
        }

        Text(
            text = if (isGlobal) "┘" else "╯",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = color.copy(alpha = 0.6f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewSessionDialog(
    projects: List<ProjectInfo>,
    defaultProjectId: String? = null,
    initialUseCustomDirectory: Boolean = false,
    onDismiss: () -> Unit,
    onCreate: (String?, String?) -> Unit,
    onScan: (String) -> Unit = {},
) {
    var title by remember { mutableStateOf("") }
    // Default to null (Global) unless a specific project is requested
    var selectedProject by remember(defaultProjectId, projects) { 
        mutableStateOf(if (defaultProjectId != null && !initialUseCustomDirectory) projects.find { it.id == defaultProjectId } else null) 
    }
    var expanded by remember { mutableStateOf(false) }
    var useCustomDirectory by remember { mutableStateOf(initialUseCustomDirectory) }
    var customDirectory by remember { mutableStateOf("") }

    val globalText = stringResource(R.string.sessions_global)
    val customText = stringResource(R.string.sessions_custom_directory)
    val theme = LocalOpenCodeTheme.current
    
    // Resolve the effective directory for session creation
    val effectiveDirectory = when {
        useCustomDirectory -> customDirectory.takeIf { it.isNotBlank() }
        else -> selectedProject?.worktree
    }
    
    TuiAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.sessions_new),
        confirmButton = {
            TuiButton(
                onClick = { onCreate(title.takeIf { it.isNotBlank() }, effectiveDirectory) }
            ) {
                Text(stringResource(R.string.sessions_create))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (effectiveDirectory != null) {
                    TuiButton(
                        onClick = { onScan(effectiveDirectory) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = theme.accent.copy(alpha = 0.2f)
                        )
                    ) {
                        Text(stringResource(R.string.sessions_scan), fontSize = TuiCodeFontSize.sm)
                    }
                }
                TuiTextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.button_cancel))
                }
            }
        }
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = when {
                    useCustomDirectory -> customText
                    selectedProject != null -> selectedProject!!.name
                    else -> globalText
                },
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.sessions_project)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                // Global option first
                DropdownMenuItem(
                    text = { 
                        Column {
                            Text(stringResource(R.string.sessions_global), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.sessions_no_project_context),
                                style = MaterialTheme.typography.bodySmall,
                                color = theme.textMuted
                            )
                        }
                    },
                    onClick = {
                        selectedProject = null
                        useCustomDirectory = false
                        expanded = false
                    }
                )
                
                // Project options
                projects.forEach { project ->
                    DropdownMenuItem(
                        text = { 
                            Column {
                                Text(project.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    project.worktree,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = theme.textMuted
                                )
                            }
                        },
                        onClick = {
                            selectedProject = project
                            useCustomDirectory = false
                            expanded = false
                        }
                    )
                }
                
                // Custom directory option
                DropdownMenuItem(
                    text = { 
                        Column {
                            Text(stringResource(R.string.sessions_custom_directory), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.sessions_custom_directory_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = theme.textMuted
                            )
                        }
                    },
                    onClick = {
                        useCustomDirectory = true
                        selectedProject = null
                        expanded = false
                    }
                )
            }
        }
        
        // Show custom directory text field when selected
        if (useCustomDirectory) {
            OutlinedTextField(
                value = customDirectory,
                onValueChange = { customDirectory = it },
                label = { Text(stringResource(R.string.sessions_directory_path)) },
                placeholder = { Text(stringResource(R.string.sessions_directory_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(stringResource(R.string.sessions_title_optional)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SessionsTopBar(
    projectName: String?,
    isSelectionMode: Boolean,
    selectedCount: Int,
    showArchived: Boolean,
    archivedCount: Int,
    onNavigateBack: (() -> Unit)?,
    onProjects: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onSelectModeToggle: () -> Unit,
    onSelectAll: () -> Unit,
    onArchiveSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onArchiveToggle: () -> Unit
) {
    val theme = LocalOpenCodeTheme.current

    val glowAlpha by remember {
        derivedStateOf {
            val period = 3000L
            val phase = (System.currentTimeMillis() % period).toFloat() / period
            0.3f + 0.7f * (if (phase < 0.5f) 2f * phase else 2f * (1f - phase))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.background)
    ) {
        Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars))

        // Terminal-style unified header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Top connector line
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(16.dp)
                        .height(1.dp)
                        .background(theme.border.copy(alpha = 0.3f))
                )
                Text(
                    text = "┌",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.border.copy(alpha = 0.5f)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    theme.border.copy(alpha = 0.3f),
                                    theme.accent.copy(alpha = glowAlpha),
                                    theme.border.copy(alpha = 0.3f)
                                )
                            )
                        )
                )
                Text(
                    text = "┐",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.border.copy(alpha = 0.5f)
                )
                Box(
                    modifier = Modifier
                        .width(16.dp)
                        .height(1.dp)
                        .background(theme.border.copy(alpha = 0.3f))
                )
            }

            // Main content box
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, theme.border.copy(alpha = 0.4f))
                    .background(theme.backgroundElement.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left side: Navigation and path
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Back button or root indicator
                    if (onNavigateBack != null) {
                        Text(
                            text = "⟨",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.accent,
                            modifier = Modifier.clickable(role = Role.Button) { onNavigateBack() }
                        )
                    } else {
                        Text(
                            text = "~",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.accent.copy(alpha = 0.6f)
                        )
                    }

                    // Path separator
                    Text(
                        text = "/",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.border.copy(alpha = 0.4f)
                    )

                    // Current location
                    val locationText = if (isSelectionMode) "SELECT" else (projectName ?: "sessions")
                    val subText = if (isSelectionMode) "$selectedCount selected" else if (projectName != null) "project" else "all"

                    Column {
                        Text(
                            text = locationText,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = if (isSelectionMode) theme.accent else theme.text
                        )
                        Text(
                            text = subText,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelectionMode) theme.accent.copy(alpha = 0.7f) else theme.textMuted.copy(alpha = 0.7f)
                        )
                    }
                }

                // Right side: Action buttons in terminal style
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isSelectionMode) {
                        // Selection mode actions
                        Text(
                            text = "[${selectedCount}]",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.accent
                        )
                        Text(
                            text = "│",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.border.copy(alpha = 0.3f)
                        )
                        Text(
                            text = "[*]",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.textMuted,
                            modifier = Modifier.clickable(role = Role.Button) { onSelectAll() }
                        )
                        Text(
                            text = "│",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.border.copy(alpha = 0.3f)
                        )
                        Text(
                            text = "[🗄]",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.warning,
                            modifier = Modifier.clickable(role = Role.Button) { onArchiveSelected() }
                        )
                        Text(
                            text = "│",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.border.copy(alpha = 0.3f)
                        )
                        Text(
                            text = "[×]",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.error,
                            modifier = Modifier.clickable(role = Role.Button) { onDeleteSelected() }
                        )
                        Text(
                            text = "│",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.border.copy(alpha = 0.3f)
                        )
                        Text(
                            text = "[esc]",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.textMuted,
                            modifier = Modifier.clickable(role = Role.Button) { onSelectModeToggle() }
                        )
                    } else {
                        // Projects button
                        if (projectName.isNullOrEmpty()) {
                            Text(
                                text = "[P]",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.textMuted,
                                modifier = Modifier.clickable(role = Role.Button) { onProjects() }
                            )
                        }

                        // Separator
                        Text(
                            text = "│",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.border.copy(alpha = 0.3f)
                        )

                        // Archived toggle
                        Text(
                            text = if (showArchived) "[✓]" else "[🗄]",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (showArchived) theme.accent else theme.textMuted,
                            modifier = Modifier.clickable(role = Role.Button) { onArchiveToggle() }
                        )
                        if (archivedCount > 0 && !showArchived) {
                            Text(
                                text = "$archivedCount",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.textMuted.copy(alpha = 0.5f)
                            )
                        }

                        // Separator
                        Text(
                            text = "│",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.border.copy(alpha = 0.3f)
                        )

                        // Select mode toggle
                        Text(
                            text = "[☰]",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.accent,
                            modifier = Modifier.clickable(role = Role.Button) { onSelectModeToggle() }
                        )

                        // Separator
                        Text(
                            text = "│",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.border.copy(alpha = 0.3f)
                        )

                        // Refresh
                        Text(
                            text = "[↻]",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.textMuted,
                            modifier = Modifier.clickable(role = Role.Button) { onRefresh() }
                        )

                        // Separator
                        Text(
                            text = "│",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.border.copy(alpha = 0.3f)
                        )

                        // Settings with accent
                        Text(
                            text = "[⚙]",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.accent,
                            modifier = Modifier.clickable(role = Role.Button) { onSettings() }
                        )
                    }
                }
            }

            // Bottom connector to TabBar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(16.dp)
                        .height(1.dp)
                        .background(theme.border.copy(alpha = 0.3f))
                )
                Text(
                    text = "├",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.border.copy(alpha = 0.5f)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(theme.border.copy(alpha = 0.3f))
                )
                // Center connector for tabs
                Text(
                    text = "┴",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.accent.copy(alpha = glowAlpha)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(theme.border.copy(alpha = 0.3f))
                )
                Text(
                    text = "┤",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.border.copy(alpha = 0.5f)
                )
                Box(
                    modifier = Modifier
                        .width(16.dp)
                        .height(1.dp)
                        .background(theme.border.copy(alpha = 0.3f))
                )
            }
        }
    }
}

@Composable
private fun WorkspaceFilterBar(
    workspaces: List<String>,
    selectedWorkspace: String?,
    onSelectWorkspace: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TuiFilterChip(
            label = "All",
            selected = selectedWorkspace == null,
            onClick = { onSelectWorkspace(null) }
        )
        workspaces.forEach { ws ->
            TuiFilterChip(
                label = ws,
                selected = selectedWorkspace == ws,
                onClick = { onSelectWorkspace(ws) }
            )
        }
    }
}

@Composable
private fun TuiFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val theme = LocalOpenCodeTheme.current
    val borderColor = if (selected) theme.accent.copy(alpha = 0.5f) else Color.Transparent
    val bgColor = if (selected) theme.accent.copy(alpha = 0.12f) else Color.Transparent
    val textColor = if (selected) theme.accent else theme.textMuted

    Surface(
        onClick = onClick,
        shape = RectangleShape,
        color = bgColor,
        border = BorderStroke(Sizing.strokeMd, borderColor)
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs)
        )
    }
}

@Composable
private fun TuiBottomBarButton(
    symbol: String,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = symbol,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = color.copy(alpha = 0.8f)
        )
    }
}

private fun formatDateTime(epochMillis: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMillis)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.monthNumber}/${local.dayOfMonth}/${local.year} ${local.hour}:${local.minute.toString().padStart(2, '0')}"
}
