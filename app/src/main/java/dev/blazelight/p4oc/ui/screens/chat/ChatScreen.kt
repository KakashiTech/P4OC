package dev.blazelight.p4oc.ui.screens.chat

import dev.blazelight.p4oc.core.log.AppLog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import dev.blazelight.p4oc.ui.components.chat.SkillPickerBottomSheet
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.core.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import android.app.Activity
import android.view.WindowManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.core.network.ConnectionState
import dev.blazelight.p4oc.domain.model.MessageWithParts
import dev.blazelight.p4oc.domain.model.Part
import dev.blazelight.p4oc.domain.model.SessionConnectionState
import dev.blazelight.p4oc.domain.model.Agent
import dev.blazelight.p4oc.domain.model.Model
import dev.blazelight.p4oc.core.datastore.VisualSettings
import dev.blazelight.p4oc.domain.model.Session
import dev.blazelight.p4oc.domain.model.PermissionResponse
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.sample
import dev.blazelight.p4oc.ui.components.chat.AbortSummaryCard
import dev.blazelight.p4oc.ui.components.chat.LocalIsThinkingPhase
import dev.blazelight.p4oc.ui.components.chat.ChatInputBar
import dev.blazelight.p4oc.ui.components.chat.FilePickerDialog
import dev.blazelight.p4oc.ui.components.chat.JumpToBottomButton
import dev.blazelight.p4oc.ui.components.chat.ModelAgentSelectorBar
import dev.blazelight.p4oc.ui.components.command.CommandPalette
import dev.blazelight.p4oc.ui.components.question.InlineQuestionCard
import dev.blazelight.p4oc.ui.components.todo.TodoLiveDot
import dev.blazelight.p4oc.ui.components.todo.TodoTrackerSheet
import dev.blazelight.p4oc.ui.components.toolwidgets.ToolWidgetState
import dev.blazelight.p4oc.ui.components.TuiTerminalMenu
import dev.blazelight.p4oc.ui.components.TuiTerminalMenuItem
import dev.blazelight.p4oc.ui.components.ContextUsage
import dev.blazelight.p4oc.ui.components.TuiTopBar
import dev.blazelight.p4oc.ui.components.TuiConfirmDialog
import dev.blazelight.p4oc.ui.components.TuiLoadingScreen
import dev.blazelight.p4oc.ui.components.TuiSnackbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.components.LocalAnimationsPaused

private val ASSISTANT_TYPES = setOf("text", "reasoning", "tools", "file", "patch", "reasoning_group")

// Data classes for optimized state management (currently not used but kept for future optimization)
data class CombinedChatState(
    val uiState: ChatUiState,
    val connectionState: ConnectionState,
    val sessionConnectionState: SessionConnectionState,
    val branchName: String?,
    val visualSettings: VisualSettings
)

data class ModelAgentState(
    val availableAgents: List<Agent>,
    val selectedAgent: Agent?,
    val availableModels: List<Model>,
    val selectedModel: Model?,
    val favoriteModels: Set<Model>
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = koinViewModel(),
    onNavigateBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenFiles: () -> Unit,
    onViewSessionDiff: ((String) -> Unit)? = null,
    onOpenSubSession: ((String) -> Unit)? = null,
    onSessionLoaded: ((sessionId: String, sessionTitle: String) -> Unit)? = null,
    onConnectionStateChanged: ((SessionConnectionState?) -> Unit)? = null,
    isActiveTab: Boolean = true,
    scrollState: LazyListState? = null,
) {
    // Granular flows — each emits only when its specific field changes.
    // Keeps ChatScreen recomposition scoped to what actually changed.
    val messages = viewModel.messages.collectAsStateWithLifecycle()
    val messagesVersion = viewModel.messagesVersion.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val branchName by viewModel.branchName.collectAsStateWithLifecycle()
    val sessionConnectionState by viewModel.sessionConnectionState.collectAsStateWithLifecycle()
    val visualSettings by viewModel.visualSettings.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val abortSummary by viewModel.abortSummary.collectAsStateWithLifecycle()
    val errorMsg by viewModel.errorMsg.collectAsStateWithLifecycle()
    val todos by viewModel.todos.collectAsStateWithLifecycle()
    val contextUsage by viewModel.contextUsage.collectAsStateWithLifecycle()

    val screenContext = LocalContext.current
    DisposableEffect(isBusy) {
        val window = (screenContext as? Activity)?.window
        if (isBusy) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val pendingQuestion by viewModel.dialogManager.pendingQuestion.collectAsStateWithLifecycle()
    val pendingPermissionsByCallId by viewModel.dialogManager.pendingPermissionsByCallId.collectAsStateWithLifecycle()
    val reasoningEffort by viewModel.reasoningEffort.collectAsStateWithLifecycle()
    val availableAgents by viewModel.modelAgentManager.availableAgents.collectAsStateWithLifecycle()
    val selectedAgent by viewModel.modelAgentManager.selectedAgent.collectAsStateWithLifecycle()
    val availableModels by viewModel.modelAgentManager.availableModels.collectAsStateWithLifecycle()
    val selectedModel by viewModel.modelAgentManager.selectedModel.collectAsStateWithLifecycle()
    val favoriteModels by viewModel.modelAgentManager.favoriteModels.collectAsStateWithLifecycle()
    val recentModels by viewModel.modelAgentManager.recentModels.collectAsStateWithLifecycle()
    val attachedFiles by viewModel.filePickerManager.attachedFiles.collectAsStateWithLifecycle()
    val pickerFiles by viewModel.filePickerManager.pickerFiles.collectAsStateWithLifecycle()
    val pickerCurrentPath by viewModel.filePickerManager.pickerCurrentPath.collectAsStateWithLifecycle()
    val isPickerLoading by viewModel.filePickerManager.isPickerLoading.collectAsStateWithLifecycle()

    LaunchedEffect(session) {
        session?.let { onSessionLoaded?.invoke(it.id, it.title) }
    }
    LaunchedEffect(sessionConnectionState) {
        onConnectionStateChanged?.invoke(sessionConnectionState)
    }
    LaunchedEffect(isActiveTab) {
        if (isActiveTab) viewModel.markAsRead()
    }

    val defaultToolWidgetState = remember(visualSettings.toolWidgetDefaultState) {
        ToolWidgetState.fromString(visualSettings.toolWidgetDefaultState)
    }
    val listState = scrollState ?: rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val smoothFling = dev.blazelight.p4oc.core.performance.rememberSmoothFlingBehavior()
    val isAtBottom = remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val userScrolledAway = remember { mutableStateOf(false) }
    val hasNewContentWhileAway = remember { mutableStateOf(false) }
    var focusTriggerCount by remember { mutableIntStateOf(0) }
    var showCommandPalette by remember { mutableStateOf(false) }
    var showTodoTracker by remember { mutableStateOf(false) }
    var showFilePicker by remember { mutableStateOf(false) }
    var showRevertDialog by remember { mutableStateOf<String?>(null) }
    var showSkillPicker by remember { mutableStateOf(false) }

    val isThinking by remember {
        derivedStateOf {
            messages.value.lastOrNull()?.parts?.any { it is Part.Reasoning && it.time?.end == null } == true
        }
    }

    val thinkingMessageIds by remember {
        derivedStateOf {
            messages.value
                .filter { it.parts.any { p -> p is Part.Reasoning && p.time?.end == null } }
                .map { it.message.id }
                .toSet()
        }
    }

    val flatItems = FlatItemsProvider(
        viewModel = viewModel,
        messages = messages,
        messagesVersion = messagesVersion,
        listState = listState,
    )

    ScrollObservers(
        viewModel = viewModel,
        messages = messages,
        messagesVersion = messagesVersion,
        listState = listState,
        isAtBottom = isAtBottom,
        session = session,
        userScrolledAway = userScrolledAway,
        hasNewContentWhileAway = hasNewContentWhileAway,
        flatItems = flatItems,
        onScrollToBottom = {},
    )

    val hasMoreMessages by remember {
        derivedStateOf { messagesVersion.value; viewModel.hasMoreMessages() }
    }
    val totalMessageCount by remember {
        derivedStateOf { messagesVersion.value; viewModel.getTotalMessageCount() }
    }
    val visibleMessageCount by remember {
        derivedStateOf { messages.value.size }
    }

    val onToolApprove = remember(viewModel) { { id: String -> viewModel.respondToPermission(id, PermissionResponse.ONCE.value) } }
    val onToolDeny    = remember(viewModel) { { id: String -> viewModel.respondToPermission(id, PermissionResponse.REJECT.value) } }
    val onToolAlways  = remember(viewModel) { { id: String -> viewModel.respondToPermission(id, PermissionResponse.ALWAYS.value) } }
    val onRevert      = remember<(String) -> Unit> { { id -> showRevertDialog = id } }
    val onFork        = remember(viewModel, onOpenSubSession) {
        { messageId: String ->
            viewModel.forkSession(
                messageId = messageId,
                onForkCreated = { forkSessionId -> onOpenSubSession?.invoke(forkSessionId) }
            )
        }
    }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    BackHandler {
        focusManager.clearFocus()
        keyboardController?.hide()
        onNavigateBack()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            ChatTopBar(
                modifier = Modifier,
                title = session?.title ?: "Chat",
                connectionState = connectionState,
                onBack = onNavigateBack,
                onTerminal = onOpenTerminal,
                onFiles = onOpenFiles,
                onCommands = {
                    viewModel.loadCommands()
                    showCommandPalette = true
                },
                onViewChanges = {
                    session?.id?.let { onViewSessionDiff?.invoke(it) }
                },
                onAbort = viewModel::abortSession,
                isBusy = isBusy,
                branchName = branchName,
                todoCount = todos.count { it.status == "in_progress" || it.status == "pending" },
                inProgressCount = todos.count { it.status == "in_progress" },
                onTodos = {
                    viewModel.loadTodos()
                    showTodoTracker = true
                },
                onSkills = {
                    showSkillPicker = true
                },
                contextUsage = contextUsage
            )
        },
        bottomBar = {
            val isSubAgent = session?.parentID != null
            if (!isSubAgent) {
                val inputText by viewModel.inputText.collectAsStateWithLifecycle()
                val isSending by viewModel.isSending.collectAsStateWithLifecycle()
                val queuedMessage by viewModel.queuedMessage.collectAsStateWithLifecycle()
                val commands by viewModel.commands.collectAsStateWithLifecycle()
                Column(
                    modifier = Modifier
                        .imePadding()
                        .navigationBarsPadding()
                        .background(LocalOpenCodeTheme.current.backgroundElement)
                ) {
                    var localInput by remember { mutableStateOf(inputText) }
                    LaunchedEffect(inputText) {
                        if (inputText != localInput) localInput = inputText
                    }
                    ChatInputBar(
                        value = localInput,
                        focusTriggerCount = focusTriggerCount,
                        isThinking = isThinking,
                        modelSelector = {
                            val t = LocalOpenCodeTheme.current
                            val next = when (reasoningEffort) {
                                "auto" -> "low"
                                "low" -> "medium"
                                "medium" -> "high"
                                "high" -> "max"
                                else -> "auto"
                            }
                            ModelAgentSelectorBar(
                                availableAgents = availableAgents,
                                selectedAgent = selectedAgent,
                                onAgentSelected = viewModel.modelAgentManager::selectAgent,
                                availableModels = availableModels,
                                selectedModel = selectedModel,
                                onModelSelected = viewModel.modelAgentManager::selectModel,
                                favoriteModels = favoriteModels,
                                recentModels = recentModels,
                                onToggleFavorite = viewModel.modelAgentManager::toggleFavoriteModel,
                                trailingContent = {
                                    Text(
                                        text = reasoningEffort,
                                        modifier = Modifier
                                            .clickable(role = Role.Button) { viewModel.updateReasoningEffort(next) }
                                            .padding(horizontal = 4.dp),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = when (reasoningEffort) {
                                            "low" -> t.success
                                            "medium" -> t.warning
                                            "high" -> t.warning.copy(alpha = 0.8f, red = 1f, green = 0.5f, blue = 0f)
                                            "max" -> t.error
                                            else -> t.textMuted
                                        }
                                    )
                                }
                            )
                        },
                        agentSelector = { },
                        onValueChange = { text ->
                            localInput = text
                            if (text.startsWith("/") && commands.isEmpty()) {
                                viewModel.loadCommands()
                            }
                        },
                        onSend = {
                            viewModel.updateInput(localInput)
                            viewModel.sendMessage()
                            localInput = ""
                            focusTriggerCount++
                        },
                        isLoading = isSending,
                        enabled = connectionState is ConnectionState.Connected,
                        isBusy = isBusy,
                        hasQueuedMessage = queuedMessage != null,
                        onQueueMessage = {
                            viewModel.updateInput(localInput)
                            viewModel.queueMessage()
                            localInput = ""
                            focusTriggerCount++
                        },
                        onCancelQueue = { /* TODO: Implementar cancelacion de mensaje encolado */ },
                        queuedMessagePreview = queuedMessage?.text,
                        attachedFiles = attachedFiles,
                        onAttachClick = {
                            viewModel.filePickerManager.loadPickerFiles()
                            showFilePicker = true
                        },
                        onRemoveAttachment = viewModel.filePickerManager::detachFile,
                        commands = commands,
                        onCommandSelected = { },
                        requestFocus = isActiveTab
                    )
                }
            }
        }
    ) { padding ->
        CompositionLocalProvider(LocalAnimationsPaused provides listState.isScrollInProgress) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
            val revert = session?.revert
            if (revert != null) {
                RevertActiveBanner(
                    onUnrevert = viewModel::unrevertSession,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }

            // Main message list — isolated composable, no uiState/connectionState reads.
            // Only recomposes when flatItems, pendingQuestion, abortSummary, or pagination change.
            CompositionLocalProvider(LocalIsThinkingPhase provides (isBusy && isThinking)) {
                ChatMessageList(
                    listState = listState,
                    flingBehavior = smoothFling,
                    flatItems = flatItems,
                    pendingQuestion = pendingQuestion,
                    abortSummary = abortSummary,
                    hasMoreMessages = hasMoreMessages,
                    visibleMessageCount = visibleMessageCount,
                    totalMessageCount = totalMessageCount,
                    onLoadMore = { viewModel.loadOlderMessages() },
                    onDismissQuestion = viewModel::dismissQuestion,
                    onRespondQuestion = { id, r -> viewModel.respondToQuestion(id, r) },
                    onToolApprove = onToolApprove,
                    onToolDeny = onToolDeny,
                    onToolAlways = onToolAlways,
                    onOpenSubSession = onOpenSubSession,
                    defaultToolWidgetState = defaultToolWidgetState,
                    pendingPermissionsByCallId = pendingPermissionsByCallId,
                    onRevert = onRevert,
                    onFork = onFork,
                    thinkingMessageIds = thinkingMessageIds,
                    showEmpty = messages.value.isEmpty() && !isBusy && !isLoading,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Spacing.lg)
                )
            }

            if (isLoading) {
                TuiLoadingScreen(modifier = Modifier.align(Alignment.Center))
            }

            errorMsg?.let { error ->
                TuiSnackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = viewModel::clearError, shape = RoundedCornerShape(4.dp)) {
                            Text(stringResource(R.string.dismiss), fontFamily = FontFamily.Monospace)
                        }
                    }
                ) {
                    Text(error, fontFamily = FontFamily.Monospace)
                }
            }

            JumpToBottomButton(
                visible = userScrolledAway.value,
                hasNewContent = hasNewContentWhileAway.value,
                onClick = {
                    coroutineScope.launch {
                        userScrolledAway.value = false
                        hasNewContentWhileAway.value = false
                        listState.animateScrollToItem(firstContentIndex(flatItems))
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = Spacing.xl, bottom = Spacing.md)
            )
            }
        }
    }

    if (showCommandPalette) {
        val commands by viewModel.commands.collectAsStateWithLifecycle()
        val isLoadingCommands by viewModel.isLoadingCommands.collectAsStateWithLifecycle()
        CommandPalette(
            commands = commands,
            isLoading = isLoadingCommands,
            onCommandSelected = { command, args ->
                viewModel.executeCommand(command.name, args)
            },
            onDismiss = { showCommandPalette = false }
        )
    }

    if (showTodoTracker) {
        val isLoadingTodos by viewModel.isLoadingTodos.collectAsStateWithLifecycle()
        TodoTrackerSheet(
            todos = todos,
            isLoading = isLoadingTodos,
            onDismiss = { showTodoTracker = false },
            onRefresh = { viewModel.loadTodos() }
        )
    }

    if (showSkillPicker) {
        val skillViewModel = koinViewModel<dev.blazelight.p4oc.ui.screens.settings.SkillsViewModel>()
        val skillState by skillViewModel.state.collectAsStateWithLifecycle()
        val skillItems = remember(skillState.skills) {
            skillState.skills.map { 
                dev.blazelight.p4oc.ui.components.chat.SkillItem(
                    name = it.name,
                    description = it.description,
                    isEnabled = it.isEnabled
                )
            }
        }
        SkillPickerBottomSheet(
            skills = skillItems,
            onSkillSelected = { skillName: String ->
                viewModel.injectSkill(skillName)
            },
            onDismiss = { showSkillPicker = false },
            isLoading = skillState.isLoading
        )
    }

    if (showFilePicker) {
        FilePickerDialog(
            files = pickerFiles,
            currentPath = pickerCurrentPath,
            isLoading = isPickerLoading,
            selectedFiles = attachedFiles,
            onNavigateTo = { path -> viewModel.filePickerManager.loadPickerFiles(path.ifBlank { "." }) },
            onNavigateUp = {
                val parent = pickerCurrentPath.substringBeforeLast("/", "")
                viewModel.filePickerManager.loadPickerFiles(parent.ifBlank { "." })
            },
            onFileSelected = { viewModel.filePickerManager.attachFile(it) },
            onFileDeselected = { viewModel.filePickerManager.detachFile(it) },
            onConfirm = { showFilePicker = false },
            onDismiss = { showFilePicker = false }
        )
    }

    showRevertDialog?.let { messageId ->
        TuiConfirmDialog(
            onDismissRequest = { showRevertDialog = null },
            onConfirm = { viewModel.revertMessage(messageId) },
            title = stringResource(R.string.revert_confirm_title),
            message = stringResource(R.string.revert_confirm_message),
            confirmText = stringResource(R.string.revert_changes),
            dismissText = stringResource(R.string.button_cancel),
            isDestructive = true
        )
    }

}

private fun firstContentIndex(items: List<FlatChatItem>): Int {
    val idx = items.indexOfFirst {
        it !is FlatChatItem.AssistantBarStart && it !is FlatChatItem.AssistantBarEnd
    }
    return if (idx < 0) 0 else idx
}

@OptIn(FlowPreview::class)
@Composable
private fun ScrollObservers(
    viewModel: ChatViewModel,
    messages: State<List<MessageWithParts>>,
    messagesVersion: State<Long>,
    listState: LazyListState,
    isAtBottom: State<Boolean>,
    session: Session?,
    userScrolledAway: MutableState<Boolean>,
    hasNewContentWhileAway: MutableState<Boolean>,
    flatItems: List<FlatChatItem>,
    onScrollToBottom: () -> Unit = {},
) {
    var isAutoScrolling by remember { mutableStateOf(false) }

    var prevFlatItemsSize by remember { mutableStateOf(flatItems.size) }
    LaunchedEffect(Unit) {
        snapshotFlow { messagesVersion.value to flatItems.size }
            .collect { (version, size) ->
                if (size > prevFlatItemsSize && !userScrolledAway.value && !listState.isScrollInProgress) {
                    prevFlatItemsSize = size
                    isAutoScrolling = true
                    try {
                        listState.scrollToItem(firstContentIndex(flatItems))
                    } finally {
                        isAutoScrolling = false
                    }
                }
                if (size < prevFlatItemsSize) {
                    prevFlatItemsSize = size
                }
            }
    }

    LaunchedEffect(Unit) {
        snapshotFlow {
            Pair(listState.isScrollInProgress, isAtBottom.value)
        }.collect { (scrolling, atBottom) ->
            viewModel.messageStore.setFlushDelayWhileScrolling(scrolling)
            if (!atBottom && scrolling && !userScrolledAway.value && !isAutoScrolling) {
                userScrolledAway.value = true
            }
            val wasAway = userScrolledAway.value
            if (atBottom && !scrolling && wasAway) {
                userScrolledAway.value = false
                hasNewContentWhileAway.value = false
                onScrollToBottom()
            }
        }
    }

    LaunchedEffect(session?.id) {
        if (messages.value.isNotEmpty() &&
            listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset == 0
        ) {
            isAutoScrolling = true
            try {
                listState.scrollToItem(firstContentIndex(flatItems))
            } finally {
                isAutoScrolling = false
            }
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { messagesVersion.value }
            .sample(80)
            .collect { version ->
                if (!userScrolledAway.value && isAtBottom.value && !listState.isScrollInProgress) {
                    isAutoScrolling = true
                    try {
                        listState.scrollToItem(firstContentIndex(flatItems))
                    } finally {
                        isAutoScrolling = false
                    }
                } else if (userScrolledAway.value) {
                    hasNewContentWhileAway.value = true
                }
            }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun FlatItemsProvider(
    viewModel: ChatViewModel,
    messages: State<List<MessageWithParts>>,
    messagesVersion: State<Long>,
    listState: LazyListState,
): List<FlatChatItem> {
    val lastChangedIds = viewModel.lastChangedIds.collectAsStateWithLifecycle()

    class Ref<T>(var value: T)
    val prevFlatItemsRef = remember { Ref<List<FlatChatItem>>(emptyList()) }

    val compute: () -> List<FlatChatItem> = {
        val currentMessages = messages.value
        val currentChangedIds = lastChangedIds.value
        val prevList = prevFlatItemsRef.value
        val patched = patchFlatItems(prevList, currentMessages, currentChangedIds)
        if (patched != null) {
            AppLog.d("ChatScreen", "flatItems patched: old=${prevList.size} new=${patched.size} changed=${currentChangedIds.size}")
        } else {
            AppLog.d("ChatScreen", "flatItems rebuild: messages=${currentMessages.size}")
        }
        val result = patched ?: buildFlatItems(groupMessagesIntoBlocks(currentMessages))
        if (result !== prevList) {
            prevFlatItemsRef.value = result
        }
        result
    }

    val flatItems by produceState(initialValue = compute(), key1 = Unit) {
        value = compute()
        snapshotFlow { messagesVersion.value }
            .drop(1)
            .debounce(33)
            .collectLatest { value = compute() }
    }

    return flatItems
}

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    modifier: Modifier = Modifier,
    title: String,
    connectionState: ConnectionState,
    onBack: () -> Unit,
    onTerminal: () -> Unit,
    onFiles: () -> Unit,
    onCommands: () -> Unit,
    onViewChanges: () -> Unit,
    onAbort: () -> Unit,
    isBusy: Boolean,
    branchName: String? = null,
    todoCount: Int = 0,
    inProgressCount: Int = 0,
    onTodos: () -> Unit = {},
    onSkills: () -> Unit = {},
    contextUsage: ContextUsage? = null
) {
    val theme = LocalOpenCodeTheme.current
    var showOverflow by remember { mutableStateOf(false) }

        Column(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.background)
            .consumeWindowInsets(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 0.dp)
    ) {
        // Top connector line
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(12.dp)
                    .height(1.dp)
                    .background(theme.border.copy(alpha = 0.3f))
            )
            Text(
                text = "├",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = theme.accent.copy(alpha = 0.7f)
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
            Box(
                modifier = Modifier
                    .width(12.dp)
                    .height(1.dp)
                    .background(theme.border.copy(alpha = 0.3f))
            )
        }

        // Main content row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(theme.background)
                .offset(x = (-8).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
                    // Back button [←]
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "[",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = theme.textMuted.copy(alpha = 0.6f),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "←",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = theme.accent.copy(alpha = 0.8f),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable(role = Role.Button, onClick = onBack)
                                .padding(horizontal = 4.dp, vertical = 0.dp)
                        )
                        Text(
                            text = "]",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = theme.textMuted.copy(alpha = 0.6f),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // ┼ connector
                    Text(
                        text = "┼",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.accent.copy(alpha = 0.7f)
                    )

                    // Token count (left)
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        contextUsage?.let { usage ->
                            val usedFormatted = formatTokenCount(usage.usedTokens)
                            val pct = if (usage.maxTokens > 0) (usage.usedTokens.toFloat() / usage.maxTokens * 100).toInt() else 0
                            val baseAlpha = 0.4f
                            val tokenColor = when {
                                pct >= 95 -> theme.error.copy(alpha = baseAlpha + 0.3f)
                                pct >= 85 -> theme.warning.copy(alpha = baseAlpha + 0.2f)
                                pct >= 70 -> theme.warning.copy(alpha = baseAlpha)
                                else -> theme.textMuted.copy(alpha = baseAlpha)
                            }
                            Text(
                                text = usedFormatted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = tokenColor,
                                modifier = Modifier.padding(start = 4.dp, end = 4.dp)
                            )
                        }

                        // Honeycomb animation (center, weight 1f)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(16.dp)
                        .offset(x = (-4).dp),
                    contentAlignment = Alignment.Center
                ) {
                            HoneycombAnimation(isBusy = isBusy)
                        }

                        // Context percent + abort + todo (right)
                        contextUsage?.let { usage ->
                            if (usage.maxTokens > 0) {
                                val pct = ((usage.usedTokens.toFloat() / usage.maxTokens) * 100).toInt()
                                val baseAlpha = 0.4f
                                val pctColor = when {
                                    pct >= 95 -> theme.error.copy(alpha = baseAlpha + 0.3f)
                                    pct >= 85 -> theme.warning.copy(alpha = baseAlpha + 0.2f)
                                    pct >= 70 -> theme.warning.copy(alpha = baseAlpha)
                                    else -> theme.textMuted.copy(alpha = baseAlpha)
                                }
                                Text(
                                    text = "${pct}%",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    color = pctColor,
                                    modifier = Modifier.padding(horizontal = 2.dp)
                                )
                            }
                        }

                        if (isBusy) {
                            var clickCount by remember { mutableIntStateOf(0) }
                            val scope = rememberCoroutineScope()
                            Text(
                                text = "■",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.error.copy(alpha = if (clickCount > 0) 1.0f else 0.8f),
                                modifier = Modifier
                                    .clickable(role = Role.Button, onClick = {
                                        clickCount++
                                        if (clickCount == 1) {
                                            scope.launch {
                                                delay(2000)
                                                if (clickCount == 1) clickCount = 0
                                            }
                                        } else if (clickCount >= 2) {
                                            onAbort()
                                            clickCount = 0
                                        }
                                    })
                                    .padding(horizontal = 2.dp)
                            )
                        }

                        if (todoCount > 0) {
                            Row(
                                modifier = Modifier
                                    .clickable(role = Role.Button, onClick = onTodos)
                                    .padding(horizontal = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "[",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = theme.textMuted.copy(alpha = 0.6f)
                                )
                                if (inProgressCount > 0) {
                                    TodoLiveDot(activeCount = inProgressCount)
                                } else {
                                    Text(
                                        text = "$todoCount",
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = theme.accent.copy(alpha = 0.8f)
                                    )
                                }
                                Text(
                                    text = "]",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = theme.textMuted.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }

                    // ┼ connector
                    Text(
                        text = "┼",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.accent.copy(alpha = 0.7f)
                    )

                // Overflow menu [⋮]
            Box(
                modifier = Modifier.offset(x = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(role = Role.Button, onClick = { showOverflow = true })
                ) {
                    Text(
                        text = "[",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = theme.textMuted.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "⋮",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = theme.accent.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.dp)
                    )
                    Text(
                        text = "]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = theme.textMuted.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Bold
                    )
                }

                TuiTerminalMenu(
                    expanded = showOverflow,
                    onDismissRequest = { showOverflow = false },
                    modifier = Modifier.align(Alignment.TopEnd),
                    offset = DpOffset(8.dp, 22.dp)
                ) {
                    TuiTerminalMenuItem(
                        text = "Changes",
                        symbol = "±",
                        onClick = { showOverflow = false; onViewChanges() }
                    )
                    TuiTerminalMenuItem(
                        text = "Commands",
                        symbol = "/",
                        onClick = { showOverflow = false; onCommands() }
                    )
                    TuiTerminalMenuItem(
                        text = "Terminal",
                        symbol = ">_",
                        onClick = { showOverflow = false; onTerminal() }
                    )
                    TuiTerminalMenuItem(
                        text = "Files",
                        symbol = "#",
                        onClick = { showOverflow = false; onFiles() }
                    )
                    TuiTerminalMenuItem(
                        text = "Skills",
                        symbol = ">",
                        onClick = { showOverflow = false; onSkills() }
                    )
                }
            }
        }
    }
}

private fun formatTokenCount(n: Int): String = when {
    n < 1000 -> "$n"
    n < 1_000_000 -> "${n / 1000}K"
    else -> "${n / 1_000_000}M"
}

@Composable
private fun ConnectionDot(state: ConnectionState) {
    val theme = LocalOpenCodeTheme.current
    val color = when (state) {
        ConnectionState.Connected -> theme.success
        ConnectionState.Connecting -> theme.warning
        ConnectionState.Disconnected -> theme.textMuted
        is ConnectionState.Error -> theme.error
    }
    Text(
        text = "●",
        color = color,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelSmall
    )
}

@Composable
private fun ConnectionIndicator(state: ConnectionState) {
    val theme = LocalOpenCodeTheme.current
    var showDetail by remember { mutableStateOf(false) }

    val (color, description) = when (state) {
        ConnectionState.Connected -> theme.success to stringResource(R.string.connection_status_connected)
        ConnectionState.Connecting -> theme.warning to stringResource(R.string.connection_status_connecting)
        ConnectionState.Disconnected -> theme.textMuted to stringResource(R.string.connection_status_disconnected)
        is ConnectionState.Error -> theme.error to sanitizeErrorMessage(state.message)
    }

    Box(
        modifier = Modifier.size(Sizing.iconButtonMd),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Circle,
            contentDescription = description,
            tint = color,
            modifier = Modifier
                .size(Sizing.iconXxs)
                .clickable(role = Role.Button) { showDetail = !showDetail }
        )
        TuiTerminalMenu(
            expanded = showDetail,
            onDismissRequest = { showDetail = false }
        ) {
            TuiTerminalMenuItem(
                text = description,
                symbol = "●",
                onClick = { showDetail = false }
            )
        }
    }
}

@Composable
private fun sanitizeErrorMessage(raw: String?): String = when {
    raw == null -> stringResource(R.string.connection_error_generic)
    raw.contains("stream closed", ignoreCase = true) -> stringResource(R.string.connection_error_stream_closed)
    raw.contains("timeout", ignoreCase = true) -> stringResource(R.string.connection_error_timeout)
    raw.contains("refused", ignoreCase = true) -> stringResource(R.string.connection_error_refused)
    raw.contains("reset", ignoreCase = true) -> stringResource(R.string.connection_error_reset)
    raw.contains("unreachable", ignoreCase = true) -> stringResource(R.string.connection_error_refused)
    else -> stringResource(R.string.connection_error_generic)
}

@Composable
private fun EmptyChatView(modifier: Modifier = Modifier) {
    val theme = LocalOpenCodeTheme.current
    Column(
        modifier = modifier.padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Box(
            modifier = androidx.compose.ui.Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(theme.backgroundElement),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "◇",
                style = MaterialTheme.typography.headlineMedium,
                color = theme.textMuted
            )
        }
        Text(
            text = stringResource(R.string.chat_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = theme.text
        )
        Text(
            text = stringResource(R.string.chat_empty_description),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = theme.textMuted
        )
        // Tip chips row
        val theme2 = theme
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("/compact", "/clear", "/help").forEach { cmd ->
                Box(
                    modifier = androidx.compose.ui.Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(theme2.backgroundElement)
                        .border(1.dp, theme2.border, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = cmd,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = theme2.accent
                    )
                }
            }
        }
    }
}

// MessageBlock, groupMessagesIntoBlocks, and MessageBlockView are now in MessageBlockUtils.kt

/**
 * Isolated message list composable.
 *
 * Receives only stable, pre-computed values — no StateFlow reads, no ViewModel access.
 * This means it ONLY recomposes when flatItems or the other explicit parameters change,
 * completely decoupled from uiState, connectionState, inputText, etc.
 */
@Composable
private fun ChatMessageList(
    listState: LazyListState,
    flingBehavior: androidx.compose.foundation.gestures.FlingBehavior,
    flatItems: List<FlatChatItem>,
    pendingQuestion: dev.blazelight.p4oc.domain.model.QuestionRequest?,
    abortSummary: dev.blazelight.p4oc.ui.components.chat.AbortSummary?,
    hasMoreMessages: Boolean,
    visibleMessageCount: Int,
    totalMessageCount: Int,
    onLoadMore: () -> Unit,
    onDismissQuestion: () -> Unit,
    onRespondQuestion: (String, List<List<String>>) -> Unit,
    onToolApprove: (String) -> Unit,
    onToolDeny: (String) -> Unit,
    onToolAlways: (String) -> Unit,
    onOpenSubSession: ((String) -> Unit)?,
    defaultToolWidgetState: ToolWidgetState,
    pendingPermissionsByCallId: Map<String, dev.blazelight.p4oc.domain.model.Permission>,
    onRevert: (String) -> Unit,
    onFork: (String) -> Unit,
    thinkingMessageIds: Set<String> = emptySet(),
    showEmpty: Boolean,
    modifier: Modifier = Modifier,
) {
    if (showEmpty) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            EmptyChatView()
        }
        return
    }
    val itemKey: (Int, FlatChatItem) -> Any = remember { { _, item -> item.key } }
    val itemContentType: (Int, FlatChatItem) -> Any = remember { { _, item -> item.contentType } }
    val accentBarColor = LocalOpenCodeTheme.current.accent.copy(alpha = 0.85f)
    LazyColumn(
        state = listState,
        modifier = modifier
            .testTag("message_list"),
        reverseLayout = true,
        flingBehavior = flingBehavior,
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        pendingQuestion?.let { q ->
            item(key = "q_${q.id}", contentType = "question") {
                dev.blazelight.p4oc.ui.components.question.InlineQuestionCard(
                    questionData = dev.blazelight.p4oc.domain.model.QuestionData(q.questions),
                    onDismiss = onDismissQuestion,
                    onSubmit = { onRespondQuestion(q.id, it) },
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
        abortSummary?.let { s ->
            item(key = "abort_${s.abortedAt}", contentType = "abort") {
                dev.blazelight.p4oc.ui.components.chat.AbortSummaryCard(
                    summary = s,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
        itemsIndexed(
            items = flatItems,
            key = itemKey,
            contentType = itemContentType
        ) { _, item ->
            val itemIsThinking = when (item) {
                is FlatChatItem.TextPart -> item.msgId in thinkingMessageIds
                else -> false
            }
            val hasAccent = item.contentType in ASSISTANT_TYPES
            val isStreaming = when (item) {
                is FlatChatItem.TextPart -> item.part.isStreaming
                is FlatChatItem.ReasoningPart -> item.part.time?.end == null
                else -> false
            }
            val itemModifier = remember(accentBarColor, hasAccent) {
                if (hasAccent) Modifier.drawBehind {
                    drawRect(
                        color = accentBarColor,
                        topLeft = Offset.Zero,
                        size = Size(3.dp.toPx(), size.height)
                    )
                } else Modifier
            }
            CompositionLocalProvider(LocalIsThinkingPhase provides itemIsThinking) {
                Box(modifier = itemModifier) {
                    FlatChatItemView(
                        item = item,
                        onToolApprove = onToolApprove,
                        onToolDeny = onToolDeny,
                        onToolAlways = onToolAlways,
                        onOpenSubSession = onOpenSubSession,
                        defaultToolWidgetState = defaultToolWidgetState,
                        pendingPermissionsByCallId = pendingPermissionsByCallId,
                        onRevert = onRevert,
                        onFork = onFork
                    )
                }
            }
        }
        if (hasMoreMessages && visibleMessageCount < totalMessageCount) {
            item(key = "load_more_messages", contentType = "load_more") {
                val theme = LocalOpenCodeTheme.current
                val scope = rememberCoroutineScope()
                var isLoadingMore by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .clickable(role = Role.Button) {
                            if (!isLoadingMore) {
                                isLoadingMore = true
                                scope.launch {
                                    try {
                                        onLoadMore()
                                    } finally {
                                        isLoadingMore = false
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isLoadingMore) "..." else "↺ more ($visibleMessageCount/$totalMessageCount)",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = theme.textMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun RevertActiveBanner(
    onUnrevert: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
            .background(theme.warning.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = "↺", color = theme.warning, fontFamily = FontFamily.Monospace)
            Text(
                text = stringResource(R.string.revert_active_banner),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = theme.warning
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(theme.warning.copy(alpha = 0.15f))
                .clickable(role = Role.Button) { onUnrevert() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.unrevert_all),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = theme.warning
            )
        }
    }
}

@Composable
private fun HoneycombAnimation(
    isBusy: Boolean,
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    val transition = rememberInfiniteTransition(label = "hexWave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "hexPhase"
    )

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.align(Alignment.Center)
        ) {
            for (i in 0..2) {
                if (isBusy) {
                    val t = ((phase + i.toFloat() / 3f) % 1f)
                    val glow = (sin(t * 2f * PI.toFloat()) + 1f) / 2f
                    HexIcon(
                        glow = glow,
                        accent = theme.accent,
                        size = 10.dp
                    )
                } else {
                    HexIcon(
                        glow = 0f,
                        accent = theme.textMuted,
                        size = 10.dp
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !isBusy,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Text(
                text = "IDLE",
                fontFamily = FontFamily.Monospace,
                fontSize = 7.sp,
                color = theme.textMuted.copy(alpha = 0.25f)
            )
        }
    }
}

@Composable
private fun HexIcon(glow: Float, accent: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val r = size.toPx() / 2f
        val path = Path().apply {
            var first = true
            for (j in 0..5) {
                val angle = j * 60.0 - 30.0
                val rad = angle * PI / 180.0
                val px = r + r * cos(rad).toFloat()
                val py = r + r * sin(rad).toFloat()
                if (first) { moveTo(px, py); first = false } else lineTo(px, py)
            }
            close()
        }
        if (glow > 0f) {
            drawPath(path, color = accent.copy(alpha = glow * 0.25f), style = Fill)
            drawPath(path, color = accent.copy(alpha = 0.15f + glow * 0.6f), style = Stroke(width = 1.5f))
        } else {
            drawPath(path, color = accent.copy(alpha = 0.2f), style = Stroke(width = 1f))
        }
    }
}
