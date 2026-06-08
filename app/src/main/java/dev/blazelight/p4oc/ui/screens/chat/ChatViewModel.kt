package dev.blazelight.p4oc.ui.screens.chat

import dev.blazelight.p4oc.core.log.AppLog
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.blazelight.p4oc.core.network.ApiResult
import dev.blazelight.p4oc.core.network.ConnectionManager
import dev.blazelight.p4oc.core.network.ConnectionState
import dev.blazelight.p4oc.core.network.DirectoryManager
import dev.blazelight.p4oc.core.network.safeApiCall
import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.data.remote.dto.ExecuteCommandRequest
import dev.blazelight.p4oc.data.remote.dto.ForkSessionRequest
import dev.blazelight.p4oc.data.remote.dto.InitSessionRequest
import dev.blazelight.p4oc.data.remote.dto.PartInputDto
import dev.blazelight.p4oc.data.remote.dto.PermissionResponseRequest
import dev.blazelight.p4oc.data.remote.dto.QuestionReplyRequest
import dev.blazelight.p4oc.data.remote.dto.ModelInput
import dev.blazelight.p4oc.data.remote.dto.SendMessageRequest
import dev.blazelight.p4oc.data.remote.mapper.CommandMapper
import dev.blazelight.p4oc.data.remote.mapper.MessageMapper
import dev.blazelight.p4oc.data.remote.mapper.SessionMapper
import dev.blazelight.p4oc.data.remote.mapper.TodoMapper
import dev.blazelight.p4oc.domain.model.*
import dev.blazelight.p4oc.domain.model.SessionConnectionState as TabConnectionState
import dev.blazelight.p4oc.ui.components.chat.AbortSummary
import dev.blazelight.p4oc.ui.components.chat.InterruptedTool
import dev.blazelight.p4oc.ui.components.chat.SelectedFile
import dev.blazelight.p4oc.ui.components.ContextUsage
import dev.blazelight.p4oc.ui.navigation.Screen
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Slim coordinator — delegates to sub-managers for message state,
 * dialogs, model/agent selection, and file picking. Retains session
 * lifecycle, message sending, command execution, and SSE event routing.
 */
class ChatViewModel constructor(
    private val savedStateHandle: SavedStateHandle,
    private val connectionManager: ConnectionManager,
    private val directoryManager: DirectoryManager,
    private val messageMapper: MessageMapper,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val sessionId: String = savedStateHandle.get<String>(Screen.Chat.ARG_SESSION_ID)
        ?: throw IllegalArgumentException("sessionId is required for ChatViewModel")
    private val sessionDirectory: String? = savedStateHandle.get<String>(Screen.Chat.ARG_DIRECTORY)

    // Child session IDs (subagent sessions whose parentID == this sessionId)
    private val childSessionIds = CopyOnWriteArraySet<String>()

    // Saved scroll position — persists across ChatScreen dispose/recreate
    // (ViewModel survives NavHost backstack navigation to Settings and back)
    var savedScrollIndex: Int = 0
    var savedScrollOffset: Int = 0

    private fun isOwnedSession(eventSessionId: String): Boolean =
        eventSessionId == sessionId || eventSessionId in childSessionIds

    // JSON serializer for SavedStateHandle persistence
    private val json = Json { ignoreUnknownKeys = true }

    // --- Sub-managers ---
    val messageStore = MessageStore(sessionId, viewModelScope)
    val dialogManager = DialogQueueManager(savedStateHandle, json)
    val modelAgentManager = ModelAgentManager(connectionManager, settingsDataStore, viewModelScope)
    val filePickerManager = FilePickerManager(connectionManager, viewModelScope)

    // --- Core state ---
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** Convenience alias — ChatScreen reads this directly. */
    val messages: StateFlow<List<MessageWithParts>> = messageStore.messages

    /** Monotonic version — use as remember() key instead of messages list reference. */
    val messagesVersion: StateFlow<Long> = messageStore.messagesVersion

    /** IDs mutated in the last SSE flush — used for incremental flatItems patching. */
    val lastChangedIds: StateFlow<Set<String>> = messageStore.lastChangedIds

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState

    // Granular derived flows — each emits only when its specific field changes.
    // ChatScreen reads these instead of the monolithic uiState to avoid
    // recomposing the entire screen when unrelated fields (e.g. inputText) change.
    val isBusy: StateFlow<Boolean> = _uiState.map { it.isBusy }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val isLoading: StateFlow<Boolean> = _uiState.map { it.isLoading }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val isSending: StateFlow<Boolean> = _uiState.map { it.isSending }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val abortSummary: StateFlow<AbortSummary?> = _uiState.map { it.abortSummary }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val errorMsg: StateFlow<String?> = _uiState.map { it.error }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val session: StateFlow<Session?> = _uiState.map { it.session }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val todos: StateFlow<List<Todo>> = _uiState.map { it.todos }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val commands: StateFlow<List<Command>> = _uiState.map { it.commands }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val inputText: StateFlow<String> = _uiState.map { it.inputText }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val queuedMessage: StateFlow<QueuedMessage?> = _uiState.map { it.queuedMessage }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val isLoadingCommands: StateFlow<Boolean> = _uiState.map { it.isLoadingCommands }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val isLoadingTodos: StateFlow<Boolean> = _uiState.map { it.isLoadingTodos }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val contextUsage: StateFlow<ContextUsage?> = combine(
        messageStore.messages,
        modelAgentManager.selectedModel,
        modelAgentManager.availableModels
    ) { messages, selected, available ->
        if (selected == null) return@combine null
        val pair = available.firstOrNull { (provId, model) ->
            provId == selected.providerID && model.id == selected.modelID
        } ?: return@combine null
        val maxTokens = pair.second.limit?.context ?: pair.second.contextLength ?: return@combine null
        var totalInput = 0; var totalOutput = 0; var totalCached = 0
        for (mwp in messages) {
            val t = (mwp.message as? Message.Assistant)?.tokens ?: continue
            totalInput += t.input
            totalOutput += t.output
            totalCached += t.cacheRead
        }
        ContextUsage(
            usedTokens = totalInput + totalOutput,
            maxTokens = maxTokens,
            inputTokens = totalInput,
            outputTokens = totalOutput,
            cachedTokens = totalCached
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _branchName = MutableStateFlow<String?>(null)
    val branchName: StateFlow<String?> = _branchName.asStateFlow()

    // Track whether this tab has unread responses (LLM finished but user hasn't viewed)
    private val _hasUnreadResponse = MutableStateFlow(false)
    val hasUnreadResponse: StateFlow<Boolean> = _hasUnreadResponse.asStateFlow()

    /**
     * Session connection state for tab indicator display.
     * - BUSY: LLM is processing, streaming, or tools are running
     * - AWAITING_INPUT: LLM finished but user hasn't viewed (tab not active)
     * - IDLE: User has viewed the response
     *
     * NOTE: does NOT combine with `messages` flow — scanning all parts on every message
     * update is O(N×M) and fires on every streaming token. isBusy from SessionStatusChanged
     * is sufficient; the server already tracks running tools and streaming state.
     */
    val sessionConnectionState: StateFlow<TabConnectionState> = combine(
        _uiState.map { it.isBusy }.distinctUntilChanged(),
        _hasUnreadResponse
    ) { isBusy, hasUnread ->
        when {
            isBusy -> TabConnectionState.BUSY
            hasUnread -> TabConnectionState.AWAITING_INPUT
            else -> TabConnectionState.IDLE
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TabConnectionState.IDLE)

    val visualSettings = settingsDataStore.visualSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, dev.blazelight.p4oc.core.datastore.VisualSettings())

    val reasoningEffort = settingsDataStore.reasoningEffort
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

    fun updateReasoningEffort(effort: String) {
        viewModelScope.launch { settingsDataStore.updateReasoningEffort(effort) }
    }

    private companion object {
        const val TAG = "ChatViewModel"

        // ── Tool call timeout constants ────────────────────────────────────
        /** How long a normal tool call may stay in Running state without SSE updates before being marked stale. */
        private const val TOOL_CALL_TIMEOUT_MS = 120_000L // 2 minutes
        /** How long a task tool call (sub-agent) may stay Running — longer because sub-agents take more time. */
        private const val TASK_TOOL_TIMEOUT_MS = 300_000L // 5 minutes
        /** Error message used when a tool call times out (watchdog). */
        private const val STALE_TOOL_MESSAGE = "Tool call timed out (stale) — connection was interrupted"
        /** Error message used when tools are found stale after SSE reconnection. */
        private const val RECONNECT_STALE_MESSAGE = "Tool state lost (stale) — connection was interrupted during execution"
        /** How often the watchdog checks for stale tool calls. */
        private const val WATCHDOG_POLL_INTERVAL_MS = 10_000L // every 10 seconds

        /**
         * Built-in OpenCode commands that aren't returned by the /command API endpoint.
         * These are hardcoded based on OpenCode documentation.
         */
        private val BUILTIN_COMMANDS = listOf(
            Command(name = "compact", description = "Compact the conversation to reduce context size"),
            Command(name = "clear", description = "Clear the conversation history"),
            Command(name = "new", description = "Start a new conversation"),
            Command(name = "undo", description = "Undo the last change"),
            Command(name = "redo", description = "Redo the last undone change"),
            Command(name = "share", description = "Share the current conversation"),
            Command(name = "init", description = "Initialize OpenCode for this project"),
            Command(name = "help", description = "Show help information"),
            Command(name = "connect", description = "Connect to a provider"),
            Command(name = "bug", description = "Report a bug"),
        )
    }

    init {
        loadSession()
        loadMessages()
        modelAgentManager.loadAgents()
        modelAgentManager.loadModels()
        observeEvents()
        loadVcsInfo()
    }

    override fun onCleared() {
        super.onCleared()
        watchdogJob?.cancel()
        watchdogJob = null
        runningToolCallTimestamps.clear()
        dialogManager.cleanup()
    }

    // --- Public API (delegating) ---

    fun markAsRead() {
        _hasUnreadResponse.value = false
    }

    fun updateInput(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun injectSkill(skillName: String) {
        _uiState.update {
            val current = it.inputText
            val prefix = if (current.isBlank()) "" else "$current "
            it.copy(inputText = "${prefix}@$skillName ")
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // --- Session lifecycle ---

    private fun getDirectory(): String? =
        sessionDirectory ?: _uiState.value.session?.directory ?: directoryManager.getDirectory()

    /** Returns the file browser root for this session — falls back to `"."` when
     *  the session directory is outside any known project (server can't list files there). */
    fun getFilePickerStartDirectory(): String {
        val dir = getDirectory()
        if (dir == null || dir.isBlank()) return "."
        val defaultDir = directoryManager.getDirectory()
        return if (dir == defaultDir) dir else "."
    }

    private fun loadSession() {
        viewModelScope.launch {
            val api = connectionManager.getApi() ?: run {
                _uiState.update { it.copy(error = "Not connected") }
                return@launch
            }
            val result = safeApiCall { api.getSession(sessionId, sessionDirectory ?: directoryManager.getDirectory()) }
            when (result) {
                is ApiResult.Success -> {
                    val session = SessionMapper.mapToDomain(result.data)
                    _uiState.update { it.copy(session = session) }
                    if (sessionDirectory == null && session.directory.isNotBlank()) {
                        directoryManager.setDirectory(session.directory)
                    }
                    // Reload VCS now that we have the canonical session directory
                    loadVcsInfo()
                    loadTodos()
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(error = "Failed to load session") }
                }
            }
        }
    }

    private fun loadMessages() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            AppLog.d(TAG, "loadMessages() called for session: $sessionId")

            val api = connectionManager.getApi() ?: run {
                _uiState.update { it.copy(isLoading = false, error = "Not connected") }
                return@launch
            }

            val directory = getDirectory()
            val result = safeApiCall { api.getMessages(sessionId, limit = 25, directory = directory) }

            when (result) {
                is ApiResult.Success -> {
                    AppLog.d(TAG, "Loaded ${result.data.size} messages (initial batch)")
                    val mapped = withContext(Dispatchers.Default) {
                        result.data.map { dto -> messageMapper.mapWrapperToDomain(dto) }
                    }
                    messageStore.loadInitial(mapped)
                    _uiState.update { it.copy(isLoading = false) }

                    // Track any tool calls already in Running state from loaded messages
                    viewModelScope.launch {
                        val runningTools = messageStore.getRunningToolCalls()
                        runningTools.forEach { (tool, _) ->
                            trackRunningToolCall(tool.callID)
                        }
                        if (runningTools.isNotEmpty()) {
                            AppLog.d(TAG, "Tracking ${runningTools.size} running tool calls from initial load")
                        }
                    }

                    // Background: fetch remaining messages for pagination
                    if (result.data.size == 25) {
                        launch {
                            val fullResult = safeApiCall { api.getMessages(sessionId, limit = null, directory = directory) }
                            if (fullResult is ApiResult.Success && fullResult.data.size > 25) {
                                val fullMapped = withContext(Dispatchers.Default) {
                                    fullResult.data.map { dto -> messageMapper.mapWrapperToDomain(dto) }
                                }
                                messageStore.loadRemaining(fullMapped)
                                AppLog.d(TAG, "Background: loaded ${fullResult.data.size} total messages for pagination")
                            }
                        }
                    }
                }
                is ApiResult.Error -> {
                    AppLog.e(TAG, "Failed to load messages: ${result.message}", result.throwable)
                    _uiState.update {
                        it.copy(isLoading = false, error = "Failed to load messages")
                    }
                }
            }
        }
    }

    private fun loadVcsInfo() {
        viewModelScope.launch {
            val api = connectionManager.getApi() ?: return@launch
            val directory = getDirectory()
            when (val result = safeApiCall { api.getVcsInfo(directory) }) {
                is ApiResult.Success -> _branchName.value = result.data.branch
                is ApiResult.Error -> AppLog.w(TAG, "Failed to load VCS info: ${result.message}")
            }
        }
    }

    /**
     * Initializes the session on the server via POST /session/{id}/init.
     * Without this call the server queues but never processes messages.
     */
    /**
     * Tracks whether initSession has been called for this session.
     * Reset on disconnect/error to allow re-init after server restart.
     */
    private var sessionInitialized = false

    /**
     * Initializes the session on the server via POST /session/{id}/init.
     * The server will not process messages on uninitialized sessions.
     * Returns true if init succeeded or was already done, false if it failed.
     * When false the caller should still try sendMessageAsync — the server
     * may have a default model and the retry will happen on the next call.
     */
    /** When set, message and part events for this ID are suppressed (init welcome message). */
    private var suppressedInitMsgId: String? = null
    /** Watch for the first assistant message emitted after initSession. */
    private var watchForInitMsg = false

    /**
     * Ensures the session is ready to accept a new message.
     *
     * Phase 1 — Clean stale tools: if the local store has tool calls stuck in
     *   Running state (e.g. from a previous failed send), abort them on the
     *   server first so they don't block the new message.
     *
     * Phase 2 — Initialize: call POST /session/{id}/init.  If this blocks or
     *   returns an error because the server itself has tool calls stuck in
     *   "running" (the bug described in the report), we catch the timeout,
     *   call abort, and retry once.
     *
     * @return true if the session is ready; false if unrecoverable
     *   (caller should show an error and NOT attempt sendMessageAsync).
     */
    private suspend fun ensureSessionReady(api: dev.blazelight.p4oc.core.network.OpenCodeApi): Boolean {
        // ── Phase 1: Clean local stale tools ──────────────────────────────
        val runningTools = messageStore.getRunningToolCalls()
        if (runningTools.isNotEmpty()) {
            AppLog.w(TAG, "ensureSessionReady: ${runningTools.size} running tools — aborting first")
            safeApiCall { api.abortSession(sessionId, getDirectory()) }
            runningToolCallTimestamps.clear()
            sessionInitialized = false
            messageStore.markAllRunningToolsStale("Aborted — new message pending")
        }

        // ── Phase 2: Initialize (with timeout + abort-retry) ──────────────
        var inited = safeInitSession()

        if (!inited) {
            AppLog.w(TAG, "ensureSessionReady: session init failed — aborting and retrying")
            safeApiCall { api.abortSession(sessionId, getDirectory()) }
            runningToolCallTimestamps.clear()
            sessionInitialized = false
            inited = safeInitSession()
        }

        return inited
    }

    /**
     * Calls [initializeSession] with a short timeout so the caller doesn't
     * block forever when the server is stuck on old tool calls.
     */
    private suspend fun safeInitSession(): Boolean {
        return try {
            withTimeout(10_000L) { initializeSession() }
        } catch (_: TimeoutCancellationException) {
            AppLog.w(TAG, "safeInitSession: timed out after 10s")
            sessionInitialized = false
            false
        } catch (_: Exception) {
            AppLog.w(TAG, "safeInitSession: unexpected error")
            sessionInitialized = false
            false
        }
    }

    // ── Tool call watchdog ─────────────────────────────────────────────────
    /**
     * Tracks the last SSE update timestamp for each running tool call.
     * Key: tool callID, Value: System.currentTimeMillis() of last update.
     * Used by [startToolCallWatchdog] to detect stale tools.
     */
    private val runningToolCallTimestamps = ConcurrentHashMap<String, Long>()

    /** The polling coroutine that periodically checks for stale tool calls. */
    private var watchdogJob: Job? = null

    /**
     * Start or restart the tool call watchdog coroutine.
     * Polls every [WATCHDOG_POLL_INTERVAL_MS] and marks any tool that has been
     * in Running state longer than its timeout as stale — but ONLY when the
     * session is NOT busy.  If the session is actively processing (isBusy == true)
     * the tool may still be legitimately running; we never time out in that case.
     *
     * This avoids false positives for long-running tools (e.g. `bash` scripts
     * that produce no intermediate output, or `task` sub-agents that run for
     * several minutes).
     */
    private fun startToolCallWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                // Only check when the session is NOT busy — tools on a busy session
                // may still be legitimately running without SSE updates.
                val busy = _uiState.value.isBusy
                if (!busy) {
                    val now = System.currentTimeMillis()
                    val staleEntries = runningToolCallTimestamps.entries
                        .filter { (callID, lastUpdate) ->
                            now - lastUpdate > getToolTimeout(callID)
                        }
                    for ((callID, _) in staleEntries) {
                        runningToolCallTimestamps.remove(callID)
                        val snapshot = messageStore.snapshotMessages()
                        var found = false
                        for (msg in snapshot) {
                            val tool = msg.parts
                                .filterIsInstance<Part.Tool>()
                                .find { it.callID == callID && it.state is ToolState.Running }
                            if (tool != null) {
                                AppLog.w(TAG, "Watchdog: stale callID=$callID tool=${tool.toolName}")
                                messageStore.markToolCallStale(callID, msg.message.id, STALE_TOOL_MESSAGE)
                                found = true
                                break
                            }
                        }
                        if (!found) {
                            AppLog.d(TAG, "Watchdog: stale callID=$callID already transitioned — skipping")
                        }
                    }
                }
                delay(WATCHDOG_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Start tracking a tool call that just entered [ToolState.Running].
     * Starts the watchdog job if not already running.
     */
    private fun trackRunningToolCall(callID: String) {
        runningToolCallTimestamps[callID] = System.currentTimeMillis()
        if (watchdogJob?.isActive != true) {
            startToolCallWatchdog()
        }
    }

    /**
     * Stop tracking a tool call that transitioned away from [ToolState.Running]
     * (e.g., to Completed, Error, or cancelled).
     */
    private fun untrackRunningToolCall(callID: String) {
        runningToolCallTimestamps.remove(callID)
    }

    /**
     * Check if a tool call ID belongs to a "task" tool (sub-agent invocation),
     * which has a longer timeout. Checks the actual stored tool name for accuracy.
     */
    private fun isTaskToolByName(callID: String): Boolean {
        // Fast path: known naming convention from opencode
        if (callID.startsWith("call_task_") || callID.startsWith("call_subtask_")) return true
        // Slow path: look up the tool name from the message store
        return false
    }

    /**
     * Get the timeout for a tool call based on its callID.
     * Task/sub-agent tools get the extended timeout; all others get the standard timeout.
     */
    private fun getToolTimeout(callID: String): Long {
        return if (isTaskToolByName(callID)) TASK_TOOL_TIMEOUT_MS else TOOL_CALL_TIMEOUT_MS
    }

    private suspend fun initializeSession(): Boolean {
        if (sessionInitialized) return true
        val api = connectionManager.getApi() ?: return false

        val lastMsgId = messageStore.messages.value.lastOrNull()?.message?.id
        val request = InitSessionRequest(
            messageID = lastMsgId ?: ""
        )
        val directory = getDirectory()
        AppLog.w(TAG, "initSession: calling (msgId=${lastMsgId ?: "none"})")
        val result = safeApiCall { api.initSession(sessionId, request, directory) }
        return when (result) {
            is ApiResult.Success -> {
                AppLog.w(TAG, "initSession: ok")
                sessionInitialized = true
                watchForInitMsg = true
                true
            }
            is ApiResult.Error -> {
                AppLog.w(TAG, "initSession: failed: ${result.message}")
                false
            }
        }
    }

    // --- SSE event routing ---

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeEvents() {
        viewModelScope.launch {
            AppLog.d(TAG, "observeEvents: Starting to collect SSE events")
            // Use flatMapLatest on the connection flow so that if a full reconnect
            // creates a new Connection (with a new EventSource), we automatically
            // switch to the new event stream instead of staying on the stale one.
            connectionManager.connection
                .flatMapLatest { conn ->
                    conn?.eventSource?.events ?: emptyFlow()
                }
                .collect { event ->
                    AppLog.d(TAG, "observeEvents: Received ${event::class.simpleName}")
                    // Log key events at warning level for release builds
                    when (event) {
                        is OpenCodeEvent.Connected -> AppLog.w(TAG, "SSE: Connected")
                        is OpenCodeEvent.Disconnected -> AppLog.w(TAG, "SSE: Disconnected")
                        is OpenCodeEvent.MessageUpdated -> AppLog.w(TAG, "SSE: MessageUpdated id=${event.message.id} role=${event.message::class.simpleName}")
                        is OpenCodeEvent.SessionStatusChanged -> AppLog.w(TAG, "SSE: SessionStatusChanged busy=${event.status is SessionStatus.Busy}")
                        is OpenCodeEvent.SessionIdle -> AppLog.w(TAG, "SSE: SessionIdle")
                        is OpenCodeEvent.SessionError -> AppLog.w(TAG, "SSE: SessionError")
                        else -> {}
                    }
                    handleEvent(event)
                }
        }
    }

    private fun handleEvent(event: OpenCodeEvent) {
        when (event) {
            is OpenCodeEvent.MessageUpdated -> {
                if (event.message.sessionID == sessionId) {
                    if (watchForInitMsg && event.message is Message.Assistant) {
                        suppressedInitMsgId = event.message.id
                        watchForInitMsg = false
                        AppLog.w(TAG, "Suppressed init welcome message id=${event.message.id}")
                    } else if (suppressedInitMsgId != event.message.id) {
                        messageStore.upsertMessage(event.message)
                        // If the assistant message has an error (e.g., usage limit),
                        // the tool parts may never receive completed/error events.
                        // Mark any tools in this message as stale immediately.
                        if (event.message is Message.Assistant && event.message.error != null) {
                            val errMsg = event.message.error
                            viewModelScope.launch {
                                val errorText = errMsg.message ?: errMsg.name
                                val staleCount = messageStore.markToolsInMessageStale(
                                    messageId = event.message.id,
                                    staleMessage = "Provider error: $errorText"
                                )
                                if (staleCount > 0) {
                                    AppLog.w(TAG, "Message error: marked $staleCount stale tools in msg ${event.message.id}")
                                }
                            }
                        }
                    }
                }
            }
            is OpenCodeEvent.MessagePartUpdated -> {
                if (suppressedInitMsgId == event.part.messageID) return@handleEvent
                if (event.part.sessionID == sessionId) {
                    messageStore.upsertPartBuffered(event.part, event.delta)

                    // Track tool call state transitions for stale detection
                    if (event.part is Part.Tool) {
                        val tool = event.part
                        when (tool.state) {
                            is ToolState.Running -> trackRunningToolCall(tool.callID)
                            is ToolState.Completed, is ToolState.Error -> untrackRunningToolCall(tool.callID)
                            else -> untrackRunningToolCall(tool.callID) // Pending is terminal for the watchdog
                        }
                    }
                }
            }
            is OpenCodeEvent.MessagePartDelta -> {
                if (suppressedInitMsgId == event.messageID) return@handleEvent
                if (event.sessionID == sessionId) {
                    when (event.field) {
                        "text" -> {
                            val part = Part.Text(
                                id = event.partID, sessionID = event.sessionID,
                                messageID = event.messageID, text = "", isStreaming = true
                            )
                            messageStore.upsertPartBuffered(part, event.delta)
                        }
                        "reasoning" -> {
                            val part = Part.Reasoning(
                                id = event.partID, sessionID = event.sessionID,
                                messageID = event.messageID, text = ""
                            )
                            messageStore.upsertPartBuffered(part, event.delta)
                        }
                    }
                }
            }
            is OpenCodeEvent.MessageRemoved -> {
                if (event.sessionID == sessionId) {
                    messageStore.removeMessage(event.messageID)
                }
            }
            is OpenCodeEvent.PartRemoved -> {
                if (event.sessionID == sessionId) {
                    messageStore.removePart(event.messageID, event.partID)
                }
            }
            is OpenCodeEvent.PermissionRequested -> {
                if (isOwnedSession(event.permission.sessionID)) {
                    dialogManager.enqueuePermission(event.permission)
                }
            }
            is OpenCodeEvent.QuestionAsked -> {
                if (isOwnedSession(event.request.sessionID)) {
                    dialogManager.enqueueQuestion(event.request)
                }
            }
            is OpenCodeEvent.SessionCreated -> {
                if (event.session.parentID == sessionId) {
                    childSessionIds.add(event.session.id)
                }
            }
            is OpenCodeEvent.SessionStatusChanged -> {
                if (event.sessionID == sessionId) {
                    val wasBusy = _uiState.value.isBusy
                    val isBusy = event.status is SessionStatus.Busy || event.status is SessionStatus.Retry
                    _uiState.update { it.copy(isBusy = isBusy, isSending = if (!isBusy) false else it.isSending) }

                    // Clear streaming flags when session becomes idle
                    if (wasBusy && !isBusy) {
                        viewModelScope.launch { messageStore.clearStreamingFlags() }
                        _hasUnreadResponse.value = true
                        runningToolCallTimestamps.clear()
                        // Session is idle on the server — any tools we still have in Running
                        // are stale (the server never sent their completed/error events).
                        // Mark them immediately so the user isn't stuck with infinite spinners.
                        viewModelScope.launch {
                            val staleCount = messageStore.markAllRunningToolsStale(
                                "Session ended — ${RECONNECT_STALE_MESSAGE}"
                            )
                            if (staleCount > 0) {
                                AppLog.w(TAG, "Session idle: marked $staleCount stale tools")
                            }
                        }
                    }

                    // Send queued message when session becomes idle
                    if (!isBusy) {
                        sendQueuedMessageIfAny()
                    }
                }
            }
            is OpenCodeEvent.SessionUpdated -> {
                if (event.session.id == sessionId) {
                    _uiState.update { it.copy(session = event.session) }
                }
            }
            is OpenCodeEvent.SessionError -> {
                if (event.sessionID == sessionId) {
                    AppLog.e(TAG, "Session error: ${event.error?.message}")
                    sessionInitialized = false
                    runningToolCallTimestamps.clear()
                    viewModelScope.launch {
                        messageStore.clearStreamingFlags()
                        val staleCount = messageStore.markAllRunningToolsStale(
                            "Session error: ${event.error?.message ?: "Connection interrupted"}"
                        )
                        if (staleCount > 0) {
                            AppLog.w(TAG, "Session error: marked $staleCount tools stale")
                        }
                    }
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isSending = false,
                            error = event.error?.message ?: "An error occurred"
                        )
                    }
                }
            }
            is OpenCodeEvent.SessionIdle -> {
                if (event.sessionID == sessionId) {
                    AppLog.d(TAG, "Session became idle")
                    viewModelScope.launch {
                        messageStore.clearStreamingFlags()
                        val staleCount = messageStore.markAllRunningToolsStale(
                            "Session ended — $RECONNECT_STALE_MESSAGE"
                        )
                        if (staleCount > 0) {
                            AppLog.w(TAG, "Session idle: marked $staleCount stale tools")
                        }
                    }
                    _uiState.update { it.copy(isBusy = false, isSending = false) }
                    runningToolCallTimestamps.clear()
                    sendQueuedMessageIfAny()
                }
            }
            is OpenCodeEvent.TodoUpdated -> {
                if (event.sessionID == sessionId) {
                    _uiState.update { it.copy(todos = event.todos) }
                }
            }
            is OpenCodeEvent.PermissionReplied -> {
                if (isOwnedSession(event.requestID)) {
                    dialogManager.clearPermissionByRequestId(event.requestID)
                }
            }
            is OpenCodeEvent.Connected -> {
                viewModelScope.launch {
                    val api = connectionManager.getApi() ?: return@launch
                    try {
                        val statuses = api.getSessionStatuses()
                        val myStatus = statuses[sessionId]
                        val statusLabel = myStatus?.type ?: "unknown"
                        AppLog.d(TAG, "SSE reconnected, session status: $statusLabel")
                        val busy = myStatus?.type == "busy" || myStatus?.type == "retry"
                        _uiState.update { it.copy(isBusy = busy, isSending = if (!busy) false else it.isSending) }
                        if (!busy) sendQueuedMessageIfAny()

                        // Re-discover pending questions that may have been missed during disconnect
                        val pendingQuestions = api.getPendingQuestions()
                        for (q in pendingQuestions) {
                            if (q.sessionID == sessionId) {
                                dialogManager.enqueueQuestion(
                                    QuestionRequest(
                                        id = q.id, sessionID = q.sessionID,
                                        questions = q.questions.map { qq ->
                                            Question(
                                                header = qq.header, question = qq.question,
                                                options = qq.options.map { o -> QuestionOption(label = o.label, description = o.description) },
                                                multiple = qq.multiple, custom = qq.custom
                                            )
                                        },
                                        tool = q.tool?.let { QuestionToolRef(messageID = it.messageID, callID = it.callID) }
                                    )
                                )
                            }
                        }

                        // Re-discover pending permissions that may have been missed
                        val pendingPermissions = api.getPendingPermissions()
                        for (p in pendingPermissions) {
                            if (p.sessionID == sessionId) {
                                dialogManager.enqueuePermission(
                                    Permission(
                                        id = p.id, type = p.permission,
                                        patterns = p.patterns, sessionID = p.sessionID,
                                        messageID = p.tool?.messageID ?: "",
                                        callID = p.tool?.callID, title = "",
                                        metadata = p.metadata, always = p.always
                                    )
                                )
                            }
                        }

                        // ── Tool state re-sync on reconnect ────────────────
                        // If the session is idle but we have tool calls stuck in Running,
                        // they are stale — the "completed" SSE events were lost during disconnect.
                        // Mark them as Error so the UI doesn't show infinite spinners.
                        if (!busy) {
                            val runningTools = messageStore.getRunningToolCalls()
                            if (runningTools.isNotEmpty()) {
                                AppLog.w(TAG, "Reconnect: session idle but ${runningTools.size} tools still Running — marking stale")
                                val staleCount = messageStore.markAllRunningToolsStale(RECONNECT_STALE_MESSAGE)
                                if (staleCount > 0) {
                                    AppLog.w(TAG, "Reconnect: marked $staleCount tools as stale")
                                    // Clear our watchdog tracking for these tools too
                                    runningTools.forEach { (tool, _) ->
                                        untrackRunningToolCall(tool.callID)
                                    }
                                }
                            }
                        } else {
                            // Session is busy — tools may still be legitimately running.
                            // Ensure the watchdog is tracking them.
                            val runningTools = messageStore.getRunningToolCalls()
                            runningTools.forEach { (tool, _) ->
                                trackRunningToolCall(tool.callID)
                            }
                        }
                    } catch (_: Exception) {
                        AppLog.w(TAG, "Failed to reload session after SSE reconnect")
                    }
                }
            }
            is OpenCodeEvent.Disconnected -> {
                _uiState.update { it.copy(isBusy = false, isSending = false) }
            }
            is OpenCodeEvent.SessionDeleted -> {
                if (event.session.id == sessionId) {
                    _uiState.update { it.copy(isBusy = false, isSending = false, error = "Session deleted") }
                }
            }
            is OpenCodeEvent.SessionDiff -> {
                if (event.sessionID == sessionId && event.diffs.isNotEmpty()) {
                    AppLog.d(TAG, "Session diff: ${event.diffs.size} files changed")
                }
            }
            is OpenCodeEvent.SessionCompacted -> {
                if (event.sessionID == sessionId) {
                    AppLog.d(TAG, "Session compacted")
                }
            }
            is OpenCodeEvent.CommandExecuted -> {
                if (event.sessionID == sessionId) {
                    AppLog.d(TAG, "Command executed: ${event.name}")
                }
            }
            is OpenCodeEvent.FileEdited -> {
                AppLog.d(TAG, "File edited: ${event.file}")
            }
            is OpenCodeEvent.InstallationUpdateAvailable -> {
                _uiState.update { it.copy(updateVersion = event.version) }
            }
            is OpenCodeEvent.Error -> {
                AppLog.e(TAG, "SSE error: ${event.throwable.message}")
            }
            else -> {}
        }
    }

    // --- Message sending ---

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        val attachedFiles = filePickerManager.attachedFiles.value
        if (text.isEmpty() && attachedFiles.isEmpty()) return

        val selectedAgent = modelAgentManager.selectedAgent.value
        val selectedModel = modelAgentManager.selectedModel.value
        _uiState.update { it.copy(inputText = "", isSending = true, abortSummary = null) }
        filePickerManager.clearAttachedFiles()

        viewModelScope.launch {
            val api = connectionManager.getApi() ?: run {
                _uiState.update { it.copy(isSending = false, inputText = text, error = "Not connected") }
                filePickerManager.restoreAttachedFiles(attachedFiles)
                return@launch
            }

            // Best-effort session readiness — auto-aborts stale tools, retries on timeout.
            // If this fails we STILL send the message (the server's prompt_async may
            // handle stuck tools implicitly, just like the opencode TUI does).
            val sessionReady = ensureSessionReady(api)
            if (!sessionReady) {
                AppLog.w(TAG, "sendMessage: session not ready — sending anyway (server may reject)")
            }

            val parts = buildPartInputs(text, attachedFiles)
            val reasoningEffort = settingsDataStore.reasoningEffort.first()
            val reasoning = dev.blazelight.p4oc.data.remote.dto.ReasoningConfigDto(effort = reasoningEffort)
            val request = SendMessageRequest(
                parts = parts,
                agent = selectedAgent,
                model = selectedModel,
                reasoning = reasoning
            )

            AppLog.w(TAG, "sendMessage: calling sendMessageAsync")
            val result = safeApiCall { api.sendMessageAsync(sessionId, request, getDirectory()) }
            when (result) {
                is ApiResult.Success -> {
                    AppLog.w(TAG, "sendMessage: ok, waiting for SSE")
                    _uiState.update { it.copy(isSending = false) }
                }
                is ApiResult.Error -> {
                    AppLog.w(TAG, "sendMessage: failed: ${result.message}")
                    sessionInitialized = false
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            inputText = text,
                            error = "Failed to send: ${result.message}"
                        )
                    }
                    filePickerManager.restoreAttachedFiles(attachedFiles)
                }
            }
        }
    }

    fun queueMessage() {
        val text = _uiState.value.inputText.trim()
        val attachedFiles = filePickerManager.attachedFiles.value
        if (text.isEmpty() && attachedFiles.isEmpty()) return

        val selectedAgent = modelAgentManager.selectedAgent.value
        val selectedModel = modelAgentManager.selectedModel.value

        _uiState.update {
            it.copy(
                inputText = "",
                queuedMessage = QueuedMessage(
                    text = text,
                    attachedFiles = attachedFiles,
                    agent = selectedAgent,
                    model = selectedModel
                )
            )
        }
        filePickerManager.clearAttachedFiles()
        AppLog.d(TAG, "queueMessage: Queued message with ${text.length} chars, ${attachedFiles.size} files")
    }

    private fun sendQueuedMessageIfAny() {
        val queued = _uiState.value.queuedMessage ?: return

        AppLog.d(TAG, "sendQueuedMessageIfAny: Sending queued message")
        _uiState.update { it.copy(queuedMessage = null, isSending = true) }

        viewModelScope.launch {
            val api = connectionManager.getApi() ?: run {
                _uiState.update {
                    it.copy(
                        isSending = false,
                        inputText = queued.text,
                        error = "Not connected"
                    )
                }
                filePickerManager.restoreAttachedFiles(queued.attachedFiles)
                return@launch
            }

            // Best-effort session readiness — auto-aborts stale tools, retries on timeout.
            // If this fails we STILL send the message (the server's prompt_async may
            // handle stuck tools implicitly, just like the opencode TUI does).
            val sessionReady = ensureSessionReady(api)
            if (!sessionReady) {
                AppLog.w(TAG, "sendQueuedMessageIfAny: session not ready — sending anyway")
            }

            val parts = buildPartInputs(queued.text, queued.attachedFiles)
            val reasoningEffort = settingsDataStore.reasoningEffort.first()
            val reasoning = dev.blazelight.p4oc.data.remote.dto.ReasoningConfigDto(effort = reasoningEffort)
            val request = SendMessageRequest(
                parts = parts,
                agent = queued.agent,
                model = queued.model,
                reasoning = reasoning
            )

            AppLog.w(TAG, "sendQueuedMessageIfAny: calling sendMessageAsync")
            val result = safeApiCall { api.sendMessageAsync(sessionId, request, getDirectory()) }
            when (result) {
                is ApiResult.Success -> {
                    AppLog.w(TAG, "sendQueuedMessageIfAny: ok, waiting for SSE")
                    _uiState.update { it.copy(isSending = false) }
                }
                is ApiResult.Error -> {
                    AppLog.w(TAG, "sendQueuedMessageIfAny: failed: ${result.message}")
                    sessionInitialized = false
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            inputText = queued.text,
                            error = "Failed to send queued message: ${result.message}"
                        )
                    }
                    filePickerManager.restoreAttachedFiles(queued.attachedFiles)
                }
            }
        }
    }

    private fun buildPartInputs(text: String, files: List<SelectedFile>): List<PartInputDto> {
        val parts = mutableListOf<PartInputDto>()
        if (text.isNotEmpty()) {
            parts.add(PartInputDto(type = "text", text = text))
        }
        files.forEach { file ->
            parts.add(PartInputDto(
                type = "file",
                filename = file.name,
                url = "file://${file.path}"
            ))
        }
        return parts
    }

    // --- Permission / question responses ---

    fun respondToPermission(permissionId: String, response: String) {
        viewModelScope.launch {
            val api = connectionManager.getApi() ?: return@launch
            val resolvedId = dialogManager.resolvePermissionId(permissionId)
            val request = PermissionResponseRequest(reply = response)
            safeApiCall { api.respondToPermission(resolvedId, request, getDirectory()) }
            dialogManager.clearPermission(resolvedId)
        }
    }

    fun respondToQuestion(requestId: String, answers: List<List<String>>) {
        viewModelScope.launch {
            val api = connectionManager.getApi() ?: return@launch
            val request = QuestionReplyRequest(answers = answers)
            safeApiCall { api.respondToQuestion(requestId, request, getDirectory()) }
            dialogManager.clearQuestion()
        }
    }

    fun dismissQuestion() {
        dialogManager.clearQuestion()
    }

    // --- Commands & Todos ---

    fun loadCommands() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingCommands = true) }
            val api = connectionManager.getApi() ?: run {
                _uiState.update { it.copy(isLoadingCommands = false, error = "Not connected") }
                return@launch
            }
            val result = safeApiCall { api.listCommands(getDirectory()) }
            when (result) {
                is ApiResult.Success -> {
                    AppLog.d(TAG, "loadCommands: Got ${result.data.size} commands from API")
                    val apiCommands = result.data.map { CommandMapper.mapToDomain(it) }
                    val allCommands = (BUILTIN_COMMANDS + apiCommands).distinctBy { it.name }
                    _uiState.update { it.copy(commands = allCommands, isLoadingCommands = false) }
                }
                is ApiResult.Error -> {
                    AppLog.e(TAG, "loadCommands failed: ${result.message}", result.throwable)
                    _uiState.update {
                        it.copy(
                            commands = BUILTIN_COMMANDS,
                            isLoadingCommands = false
                        )
                    }
                }
            }
        }
    }

    fun executeCommand(commandName: String, arguments: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true) }
            val api = connectionManager.getApi() ?: run {
                _uiState.update { it.copy(isSending = false, error = "Not connected") }
                return@launch
            }
            // Best-effort session readiness — try to send even if init fails
            val sessionReady = ensureSessionReady(api)
            if (!sessionReady) {
                AppLog.w(TAG, "executeCommand: session not ready — sending anyway")
            }
            val request = ExecuteCommandRequest(
                command = commandName,
                arguments = arguments
            )
            val result = safeApiCall { api.executeCommand(sessionId, request, getDirectory()) }
            when (result) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(isSending = false, isBusy = true) }
                }
                is ApiResult.Error -> {
                    sessionInitialized = false
                    _uiState.update {
                        it.copy(isSending = false, error = "Failed to execute command: ${result.message}")
                    }
                }
            }
        }
    }

    fun loadTodos() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingTodos = true) }
            val api = connectionManager.getApi() ?: run {
                _uiState.update { it.copy(isLoadingTodos = false) }
                return@launch
            }
            val result = safeApiCall { api.getSessionTodos(sessionId, getDirectory()) }
            when (result) {
                is ApiResult.Success -> {
                    val todos = result.data.map { TodoMapper.mapToDomain(it) }
                    _uiState.update { it.copy(todos = todos, isLoadingTodos = false) }
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(isLoadingTodos = false) }
                }
            }
        }
    }

    // --- Revert / Unrevert ---

    fun revertMessage(messageId: String) {
        viewModelScope.launch {
            val api = connectionManager.getApi() ?: return@launch

            // --- Restore message text to input bar ---
            val snapshot = messageStore.snapshotMessages()
            val targetMsg = snapshot.find { it.message.id == messageId }
            if (targetMsg != null) {
                val textPart = targetMsg.parts.filterIsInstance<Part.Text>().firstOrNull()
                val content = textPart?.text?.trim()
                if (!content.isNullOrBlank()) {
                    updateInput(content)
                }
            }

            // --- Find the assistant response that follows this user message ---
            val userIdx = snapshot.indexOfFirst { it.message.id == messageId }
            if (userIdx >= 0 && userIdx + 1 < snapshot.size) {
                val nextMsg = snapshot[userIdx + 1]
                if (nextMsg.message is Message.Assistant) {
                    messageStore.removeMessage(nextMsg.message.id)
                }
            }
            // Remove the user message itself
            messageStore.removeMessage(messageId)

            // --- Server-side file revert ---
            val request = dev.blazelight.p4oc.data.remote.dto.RevertSessionRequest(messageID = messageId)
            val result = safeApiCall { api.revertSession(sessionId, request, getDirectory()) }
            when (result) {
                is ApiResult.Success -> {
                    loadSession()  // Refresh to get updated revert state
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(error = "Failed to revert: ${result.message}") }
                }
            }
        }
    }

    fun unrevertSession() {
        viewModelScope.launch {
            val api = connectionManager.getApi() ?: return@launch
            val result = safeApiCall { api.unrevertSession(sessionId, getDirectory()) }
            when (result) {
                is ApiResult.Success -> {
                    loadSession()  // Refresh to clear revert state
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(error = "Failed to unrevert: ${result.message}") }
                }
            }
        }
    }

    // --- Fork Session ---

    fun forkSession(
        messageId: String,
        onForkCreated: (String) -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val api = connectionManager.getApi() ?: run {
                val errorMsg = "Not connected"
                _uiState.update { it.copy(isLoading = false, error = errorMsg) }
                onError?.invoke(errorMsg)
                return@launch
            }
            val request = ForkSessionRequest(messageID = messageId)
            val result = safeApiCall { api.forkSession(sessionId, request, getDirectory()) }
            when (result) {
                is ApiResult.Success -> {
                    val forkSessionId = result.data.id
                    AppLog.d(TAG, "forkSession: Created fork with ID $forkSessionId from message $messageId")
                    _uiState.update { it.copy(isLoading = false) }
                    onForkCreated(forkSessionId)
                }
                is ApiResult.Error -> {
                    val errorMsg = "Failed to fork session: ${result.message}"
                    AppLog.e(TAG, "forkSession failed: ${result.message}", result.throwable)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = errorMsg
                        )
                    }
                    onError?.invoke(errorMsg)
                }
            }
        }
    }

    // --- Abort ---

    /**
     * Load next page of older messages for pagination.
     * Returns true if more messages available.
     */
    fun loadOlderMessages(): Boolean {
        return messageStore.loadMore(25)
    }
    
    /**
     * Check if there are more messages to load.
     */
    fun hasMoreMessages(): Boolean {
        return messageStore.hasMoreMessages()
    }
    
    /**
     * Get total message count (including not yet visible).
     */
    fun getTotalMessageCount(): Int {
        return messageStore.getTotalMessageCount()
    }

    fun abortSession() {
        viewModelScope.launch {
            // Snapshot state BEFORE clearing flags
            val summary = buildAbortSummary()

            val api = connectionManager.getApi() ?: return@launch
            safeApiCall { api.abortSession(sessionId, getDirectory()) }
            messageStore.clearStreamingFlags()
            _uiState.update { it.copy(isBusy = false, isSending = false, abortSummary = summary) }
        }
    }

    private suspend fun buildAbortSummary(): AbortSummary {
        val snapshot = messageStore.snapshotMessages()

        val runningTools = snapshot
            .flatMap { it.parts }
            .filterIsInstance<Part.Tool>()
            .filter { it.state is ToolState.Running }
            .map { tool ->
                val running = tool.state as ToolState.Running
                InterruptedTool(
                    toolName = tool.toolName,
                    context = running.title?.take(40)
                )
            }

        val wasStreaming = snapshot
            .flatMap { it.parts }
            .any { it is Part.Text && it.isStreaming }

        val lastAssistant = snapshot
            .map { it.message }
            .filterIsInstance<Message.Assistant>()
            .lastOrNull()

        return AbortSummary(
            interruptedTools = runningTools,
            wasTextStreaming = wasStreaming,
            tokens = lastAssistant?.tokens,
            cost = lastAssistant?.cost
        )
    }
}

/**
 * Core UI state — only session lifecycle, sending state, commands, and todos.
 * Model/agent, file picker, and dialog state are exposed via sub-manager StateFlows.
 */
data class ChatUiState(
    val session: Session? = null,
    val inputText: String = "",
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val isBusy: Boolean = false,
    val error: String? = null,
    val commands: List<Command> = emptyList(),
    val isLoadingCommands: Boolean = false,
    val todos: List<Todo> = emptyList(),
    val isLoadingTodos: Boolean = false,
    val queuedMessage: QueuedMessage? = null,
    val abortSummary: AbortSummary? = null,
    val updateVersion: String? = null
)

data class QueuedMessage(
    val text: String,
    val attachedFiles: List<SelectedFile> = emptyList(),
    val agent: String? = null,
    val model: ModelInput? = null
)
