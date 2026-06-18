package dev.blazelight.p4oc.ui.screens.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.core.network.ApiResult
import dev.blazelight.p4oc.core.network.ConnectionManager
import dev.blazelight.p4oc.core.network.DirectoryManager
import dev.blazelight.p4oc.core.network.SessionDataCache
import dev.blazelight.p4oc.core.network.safeApiCall
import dev.blazelight.p4oc.data.remote.dto.CreateSessionRequest
import dev.blazelight.p4oc.data.remote.dto.UpdateSessionRequest
import dev.blazelight.p4oc.data.remote.mapper.SessionMapper
import dev.blazelight.p4oc.domain.model.Session
import dev.blazelight.p4oc.domain.model.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.Serializable
import androidx.compose.runtime.Stable


class SessionListViewModel constructor(
    private val connectionManager: ConnectionManager,
    private val directoryManager: DirectoryManager,
    private val sessionDataCache: SessionDataCache
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionListUiState())
    val uiState: StateFlow<SessionListUiState> = _uiState.asStateFlow()

    // True after the first full load completes — used by the UI to skip
    // re-loading during the pop-back transition (avoids recomposition jank).
    private var initialLoadDone = false

    init {
        val cached = sessionDataCache.peek()
        if (cached != null && sessionDataCache.hasFreshData) {
            // Branch-prediction hit: cache was pre-warmed by ServerViewModel after connect.
            // Paint the screen immediately with cached data, then refresh delta in background.
            AppLog.d("SessionListVM", "Cache hit: ${cached.sessions.size} sessions — instant paint")
            _uiState.update { it.copy(sessions = cached.sessions, projects = cached.projects, workspaces = cached.sessions.computeWorkspaces()) }
            initialLoadDone = true
            viewModelScope.launch {
                // Refresh the cache in background so it's never stale
                loadSessionsAsync()
                loadSessionStatuses()
            }
        } else {
            viewModelScope.launch {
                loadSessionsAsync()
                loadSessionStatuses()
            }
        }
    }

    private fun List<SessionWithProject>.computeWorkspaces(): List<String> {
        return listOf("Work", "Personal", "Other")
    }

    fun selectWorkspace(workspace: String?) {
        _uiState.update { it.copy(selectedWorkspace = workspace) }
    }

    fun overrideWorkspace(sessionId: String, workspace: String) {
        _uiState.update { state ->
            state.copy(
                sessions = state.sessions.map { swp ->
                    if (swp.session.id == sessionId) swp.copy(workspace = workspace) else swp
                }
            )
        }
        _uiState.update { it.copy(workspaces = it.sessions.computeWorkspaces()) }
    }

    // ── Selection mode ──

    fun enterSelectionMode() {
        _uiState.update { it.copy(isSelectionMode = true, selectedSessionIds = emptySet()) }
    }

    fun exitSelectionMode() {
        _uiState.update { it.copy(isSelectionMode = false, selectedSessionIds = emptySet()) }
    }

    fun toggleSessionSelection(sessionId: String) {
        _uiState.update { state ->
            val updated = if (sessionId in state.selectedSessionIds) {
                state.selectedSessionIds - sessionId
            } else {
                state.selectedSessionIds + sessionId
            }
            state.copy(selectedSessionIds = updated)
        }
    }

    fun selectAllSessions() {
        _uiState.update { state ->
            val activeIds = state.sessions.filter { !it.archived }.map { it.session.id }.toSet()
            state.copy(selectedSessionIds = activeIds)
        }
    }

    // ── Archive / Unarchive ──

    fun archiveSession(sessionId: String, directory: String? = null) {
        viewModelScope.launch {
            val api = connectionManager.getApi() ?: return@launch
            safeApiCall { api.updateSession(sessionId, UpdateSessionRequest(archived = true), directory ?: directoryManager.getDirectory()) }
            _uiState.update { state ->
                val updated = state.sessions.map { swp ->
                    if (swp.session.id == sessionId) swp.copy(archived = true) else swp
                }
                state.copy(sessions = updated, workspaces = updated.computeWorkspaces())
            }
        }
    }

    fun archiveSelectedSessions() {
        val selected = _uiState.value.selectedSessionIds.toList()
        val sessions = _uiState.value.sessions
        viewModelScope.launch {
            val api = connectionManager.getApi() ?: return@launch
            selected.forEach { id ->
                val swp = sessions.find { it.session.id == id } ?: return@forEach
                safeApiCall { api.updateSession(id, UpdateSessionRequest(archived = true), swp.session.directory.takeIf { it.isNotBlank() }) }
            }
            _uiState.update { state ->
                val updated = state.sessions.map { swp ->
                    if (swp.session.id in selected) swp.copy(archived = true) else swp
                }
                state.copy(sessions = updated, selectedSessionIds = emptySet(), workspaces = updated.computeWorkspaces())
            }
        }
    }

    fun unarchiveSession(sessionId: String, directory: String? = null) {
        viewModelScope.launch {
            val api = connectionManager.getApi() ?: return@launch
            safeApiCall { api.updateSession(sessionId, UpdateSessionRequest(archived = false), directory ?: directoryManager.getDirectory()) }
            _uiState.update { state ->
                val updated = state.sessions.map { swp ->
                    if (swp.session.id == sessionId) swp.copy(archived = false) else swp
                }
                state.copy(sessions = updated, workspaces = updated.computeWorkspaces())
            }
        }
    }

    fun deleteSelectedSessions() {
        val selected = _uiState.value.selectedSessionIds.toList()
        val sessions = _uiState.value.sessions
        viewModelScope.launch {
            val api = connectionManager.getApi() ?: return@launch
            selected.forEach { id ->
                val swp = sessions.find { it.session.id == id } ?: return@forEach
                val result = safeApiCall { api.deleteSession(id, swp.session.directory.takeIf { it.isNotBlank() }) }
                if (result is ApiResult.Success || (result is ApiResult.Error && result.message.contains("404"))) {
                    sessionDataCache.removeSession(id)
                }
            }
            _uiState.update { state ->
                val remaining = state.sessions.filter { it.session.id !in selected }
                state.copy(sessions = remaining, selectedSessionIds = emptySet(), isSelectionMode = false, workspaces = remaining.computeWorkspaces())
            }
        }
    }

    fun toggleShowArchived() {
        _uiState.update { it.copy(showArchived = !it.showArchived, isSelectionMode = false, selectedSessionIds = emptySet()) }
    }

    private var refreshJob: kotlinx.coroutines.Job? = null
    fun refresh() {
        // Debounce multiple rapid refresh() calls into one
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            kotlinx.coroutines.delay(120)
            loadSessionsAsync()
            loadSessionStatuses()
        }
    }

    // Called when navigating back to Sessions — only fetches the fast status
    // endpoint, not the full session list. Keeps the UI stable during animation.
    fun refreshOnResume() {
        if (!initialLoadDone) return
        loadSessionStatuses()
    }

    private fun loadSessionStatuses() {
        viewModelScope.launch {
            val api = connectionManager.getApi() ?: return@launch
            val projects = _uiState.value.projects

            val netSemaphore = Semaphore(3)
            val allStatuses = mutableMapOf<String, SessionStatus>()

            coroutineScope {
                val globalDeferred = async {
                    netSemaphore.acquire()
                    try {
                        safeApiCall { api.getSessionStatuses(directory = null) }
                    } finally {
                        netSemaphore.release()
                    }
                }

                val projectDeferreds = projects.map { project ->
                    async {
                        netSemaphore.acquire()
                        try {
                            safeApiCall { api.getSessionStatuses(directory = project.worktree) }
                        } finally {
                            netSemaphore.release()
                        }
                    }
                }

                val globalResult = globalDeferred.await()
                if (globalResult is ApiResult.Success) {
                    globalResult.data.forEach { (sessionId, dto) ->
                        allStatuses[sessionId] = mapStatusDto(dto)
                    }
                }

                projectDeferreds.awaitAll().forEach { result ->
                    if (result is ApiResult.Success) {
                        result.data.forEach { (sessionId, dto) ->
                            allStatuses[sessionId] = mapStatusDto(dto)
                        }
                    }
                }

                // Fetch statuses for custom directory sessions (outside any known project).
                // The global query only returns statuses for directory=null sessions, and
                // per-project queries only cover known project worktrees — custom sessions
                // would otherwise never show their busy/idle indicator.
                val knownDirs = (setOf(null) + projects.map { it.worktree }).toSet()
                val customDirs = _uiState.value.sessions
                    .map { it.session.directory }
                    .filter { it.isNotBlank() && it !in knownDirs }
                    .distinct()
                customDirs.forEach { dir ->
                    val result = safeApiCall { api.getSessionStatuses(directory = dir) }
                    if (result is ApiResult.Success) {
                        result.data.forEach { (sessionId, dto) ->
                            allStatuses[sessionId] = mapStatusDto(dto)
                        }
                    }
                }
            }

            _uiState.update { it.copy(sessionStatuses = allStatuses) }
        }
    }

    private fun mapStatusDto(dto: dev.blazelight.p4oc.data.remote.dto.SessionStatusDto): SessionStatus {
        return when (dto.type) {
            "busy" -> SessionStatus.Busy
            "working" -> SessionStatus.Busy
            "idle" -> SessionStatus.Idle
            "retry" -> SessionStatus.Retry(
                attempt = dto.attempt ?: 0,
                message = dto.message ?: "",
                next = dto.next ?: 0L
            )
            else -> SessionStatus.Idle
        }
    }

    private suspend fun loadSessionsAsync() {
        _uiState.update { it.copy(isLoading = true, error = null) }

        val api = connectionManager.getApi() ?: run {
            _uiState.update { it.copy(isLoading = false) }
            return
        }

            try {
                // First, fetch projects to know what to aggregate
                val projectsResult = safeApiCall { api.listProjects() }
                val projects = when (projectsResult) {
                    is ApiResult.Success -> projectsResult.data.map { dto ->
                        ProjectInfo(
                            id = dto.id,
                            worktree = dto.worktree,
                            name = dto.worktree.substringAfterLast("/")
                        )
                    }
                    is ApiResult.Error -> emptyList()
                }
                
                // Update projects in state
                _uiState.update { it.copy(projects = projects.sortedByDescending { p -> p.worktree }) }

                // Fetch all sessions in parallel: global + each project + known extra dirs
                // Use semaphore to limit concurrent requests — prevents network stack
                // contention on low-end devices with many projects
                val netSemaphore = Semaphore(3)
                val allSessionsWithProjects = coroutineScope {
                    // Global sessions (no directory filter)
                    val globalDeferred = async {
                        netSemaphore.acquire()
                        try {
                            val result = safeApiCall { api.listSessions(directory = null, roots = true, limit = 100) }
                            when (result) {
                                is ApiResult.Success -> result.data.map { dto ->
                                    SessionWithProject(
                                        session = SessionMapper.mapToDomain(dto),
                                        projectId = null,
                                        projectName = null
                                    )
                                }
                                is ApiResult.Error -> {
                                    AppLog.e("SessionListVM", "Failed to load global sessions: ${result.message}")
                                    emptyList()
                                }
                            }
                        } finally {
                            netSemaphore.release()
                        }
                    }

                    val projectWorktrees = projects.map { it.worktree }.toSet()

                    // Sessions for each project
                    val projectDeferreds = projects.map { project ->
                        async {
                            netSemaphore.acquire()
                            try {
                                val result = safeApiCall { api.listSessions(directory = project.worktree, roots = true, limit = 100) }
                                when (result) {
                                    is ApiResult.Success -> result.data.map { dto ->
                                        SessionWithProject(
                                            session = SessionMapper.mapToDomain(dto),
                                            projectId = project.id,
                                            projectName = project.name
                                        )
                                    }
                                    is ApiResult.Error -> {
                                        AppLog.e("SessionListVM", "Failed to load sessions for ${project.name}: ${result.message}")
                                        emptyList()
                                    }
                                }
                            } finally {
                                netSemaphore.release()
                            }
                        }
                    }

                    // Also query known directories from cache that aren't project worktrees.
                    // Finds sessions in directories discovered during previous connections.
                    val cached = sessionDataCache.peek()
                    val extraDirs = (cached?.knownDirectories ?: emptySet()) - projectWorktrees - setOf("")
                    val extraDeferreds = extraDirs.map { dir ->
                        async {
                            netSemaphore.acquire()
                            try {
                                val result = safeApiCall { api.listSessions(directory = dir, roots = true, limit = 100) }
                                when (result) {
                                    is ApiResult.Success -> result.data.map { dto ->
                                        SessionWithProject(
                                            session = SessionMapper.mapToDomain(dto),
                                            projectId = null,
                                            projectName = null
                                        )
                                    }
                                    is ApiResult.Error -> {
                                        AppLog.w("SessionListVM", "Failed to load sessions for extra dir $dir: ${result.message}")
                                        emptyList()
                                    }
                                }
                            } finally {
                                netSemaphore.release()
                            }
                        }
                    }

                    // Await all and merge
                    val globalSessions = globalDeferred.await()
                    val projectSessions = projectDeferreds.awaitAll().flatten()
                        .distinctBy { it.session.id }
                    val extraSessions = extraDeferreds.awaitAll().flatten()

                    // Deduplicate: project sessions take priority over global/extra
                    val projectSessionIds = projectSessions.map { it.session.id }.toSet()
                    val extraSessionIds = extraSessions.map { it.session.id }.toSet()
                    val uniqueGlobalSessions = globalSessions.filter { it.session.id !in projectSessionIds && it.session.id !in extraSessionIds }
                    val uniqueExtraSessions = extraSessions.filter { it.session.id !in projectSessionIds }

                    // Project sessions first so distinctBy keeps the project-enriched version
                    projectSessions + uniqueExtraSessions + uniqueGlobalSessions
                }

                AppLog.d("SessionListVM", "loadSessions: aggregated ${allSessionsWithProjects.size} total sessions")

                // Preserve sessions created locally that the API didn't return.
                // This covers two race-prone scenarios:
                //   1. Custom-directory sessions the server doesn't expose in any list query.
                //   2. A new session created *after* our API calls returned but before we
                //      write the state — without this the optimistic add would be overwritten.
                val currentState = _uiState.value
                val currentSessionById = currentState.sessions.associateBy { it.session.id }
                val apiSessionIds = allSessionsWithProjects.map { it.session.id }.toSet()
                val localOnly = currentSessionById.filterKeys { it !in apiSessionIds }.values
                // Also check the cache in case the ViewModel was re-created
                val cached = sessionDataCache.peek()
                val cachedSessions = cached?.sessions ?: emptyList()
                val extraCached = cachedSessions.filter { it.session.id !in apiSessionIds && it.session.id !in currentSessionById }
                AppLog.d("SessionListVM", "loadSessions: currentState has ${currentState.sessions.size} sessions, localOnly=${localOnly.size}, extraCached=${extraCached.size}")

                // Refresh cached-only sessions from the server to pick up title changes
                // (e.g. sessions renamed before upsertSession was called on rename).
                val refreshedExtra = extraCached.mapNotNull { swp ->
                    val dir = swp.session.directory.takeIf { it.isNotBlank() }
                    val result = safeApiCall { api.getSession(swp.session.id, dir) }
                    when (result) {
                        is ApiResult.Success -> {
                            val fresh = SessionMapper.mapToDomain(result.data)
                            swp.copy(session = fresh)
                        }
                        is ApiResult.Error -> {
                            AppLog.w("SessionListVM", "failed to refresh session ${swp.session.id}: ${result.message}")
                            swp // fall back to cached data
                        }
                    }
                }

                val merged = (localOnly + refreshedExtra + allSessionsWithProjects)
                    .distinctBy { it.session.id }
                    .sortedByDescending { s -> s.session.updatedAt }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        sessions = merged,
                        workspaces = merged.computeWorkspaces()
                    )
                }
                initialLoadDone = true

                // After successful load, update the cache's knownDirectories with
                // every session directory + project worktree seen, so future loads
                // will discover sessions in these directories even if the project
                // list changes or doesn't include them.
                val allDirs = (projects.map { it.worktree } + allSessionsWithProjects.map { it.session.directory })
                    .filter { it.isNotBlank() }
                    .distinct()
                val mergedDirs = (cached?.knownDirectories ?: emptySet()) + allDirs.toSet()
                val currentCache = sessionDataCache.peek()
                if (currentCache != null && mergedDirs != currentCache.knownDirectories) {
                    sessionDataCache.updateKnownDirectories(mergedDirs)
                }
            } catch (e: Exception) {
                AppLog.e("SessionListVM", "loadSessions error", e)
                _uiState.update {
                    it.copy(isLoading = false, error = "Failed to load sessions: ${e.message}")
                }
            }
    }

    fun createSession(title: String?, directory: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            
            AppLog.d("SessionListVM", "createSession called with title=$title, directory=$directory")

            val api = connectionManager.getApi() ?: run {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            val request = CreateSessionRequest(title = title)
            AppLog.d("SessionListVM", "Calling API createSession: title=${request.title}, parentID=${request.parentID}, directory=$directory")
            val result = safeApiCall { 
                api.createSession(
                    directory = directory,
                    request = request
                )
            }

            when (result) {
                is ApiResult.Success -> {
                    val session = SessionMapper.mapToDomain(result.data)
                    
                    // Find project info if directory matches a project
                    val project = _uiState.value.projects.find { it.worktree == directory }
                    val sessionWithProject = SessionWithProject(
                        session = session,
                        projectId = project?.id,
                        projectName = project?.name
                    )
                    
                    // Keep the cache in sync so ViewModel re-creation doesn't lose it
                    sessionDataCache.upsertSession(sessionWithProject)
                    val updatedSessions = listOf(sessionWithProject) + _uiState.value.sessions
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            sessions = updatedSessions,
                            workspaces = updatedSessions.computeWorkspaces(),
                            newSessionId = session.id,
                            newSessionDirectory = session.directory
                        )
                    }
                }
                is ApiResult.Error -> {
                    AppLog.e("SessionListVM", "createSession FAILED for directory=$directory: ${result.message}")
                    _uiState.update { 
                        it.copy(isLoading = false, error = "Failed to create session: ${result.message}") 
                    }
                }
            }
        }
    }

    /**
     * Discover existing sessions in a directory that may not be registered as a project.
     * Fetches sessions from the server for this directory and registers them in the UI
     * + cache, so they appear in the session list and are auto-discovered on future loads.
     */
    fun discoverSessions(directory: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val api = connectionManager.getApi() ?: run {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }

            AppLog.d("SessionListVM", "discoverSessions: scanning directory=$directory")

            // Fetch projects to match project info
            val projectsResult = safeApiCall { api.listProjects() }
            val projects = when (projectsResult) {
                is ApiResult.Success -> projectsResult.data.map { dto ->
                    ProjectInfo(
                        id = dto.id,
                        worktree = dto.worktree,
                        name = dto.worktree.substringAfterLast("/")
                    )
                }
                is ApiResult.Error -> emptyList()
            }

            // Fetch sessions from the target directory
            val result = safeApiCall { api.listSessions(directory = directory, roots = true, limit = 100) }
            when (result) {
                is ApiResult.Success -> {
                    val discovered = result.data.map { dto ->
                        val session = SessionMapper.mapToDomain(dto)
                        val project = projects.find { it.worktree == session.directory }
                        SessionWithProject(
                            session = session,
                            projectId = project?.id,
                            projectName = project?.name
                        )
                    }

                    if (discovered.isEmpty()) {
                        _uiState.update { it.copy(isLoading = false, error = "No sessions found in $directory") }
                        AppLog.d("SessionListVM", "discoverSessions: no sessions found in $directory")
                        return@launch
                    }

                    AppLog.d("SessionListVM", "discoverSessions: found ${discovered.size} sessions in $directory")

                    // Register directory in knownDirectories so future loads auto-discover
                    val cached = sessionDataCache.peek()
                    val mergedDirs = (cached?.knownDirectories ?: emptySet()) + directory
                    sessionDataCache.updateKnownDirectories(mergedDirs)

                    // Upsert each discovered session
                    discovered.forEach { sessionDataCache.upsertSession(it) }

                    // Merge into UI state (avoid duplicates by ID)
                    _uiState.update { state ->
                        val existingIds = state.sessions.map { it.session.id }.toSet()
                        val newOnes = discovered.filter { it.session.id !in existingIds }
                        val merged = (newOnes + state.sessions)
                            .sortedByDescending { s -> s.session.updatedAt }
                        state.copy(
                            isLoading = false,
                            sessions = merged,
                            workspaces = merged.computeWorkspaces(),
                            error = null
                        )
                    }
                }
                is ApiResult.Error -> {
                    AppLog.e("SessionListVM", "discoverSessions FAILED for directory=$directory: ${result.message}")
                    _uiState.update {
                        it.copy(isLoading = false, error = "Failed to scan directory: ${result.message}")
                    }
                }
            }
        }
    }

    fun deleteSession(sessionId: String, directory: String? = null) {
        viewModelScope.launch {
            val api = connectionManager.getApi() ?: return@launch
            val result = safeApiCall { api.deleteSession(sessionId, directory ?: directoryManager.getDirectory()) }

            when (result) {
                is ApiResult.Success -> {
                    sessionDataCache.removeSession(sessionId)
                    _uiState.update { state ->
                        val filtered = state.sessions.filter { it.session.id != sessionId }
                        state.copy(sessions = filtered, workspaces = filtered.computeWorkspaces())
                    }
                }
                is ApiResult.Error -> {
                    // Even if the server returns 404 (already deleted), remove locally
                    if (result.message.contains("404") || result.message.contains("not found")) {
                        sessionDataCache.removeSession(sessionId)
                        _uiState.update { state ->
                            val filtered = state.sessions.filter { it.session.id != sessionId }
                            state.copy(sessions = filtered, workspaces = filtered.computeWorkspaces())
                        }
                    } else {
                        _uiState.update {
                            it.copy(error = "Failed to delete session: ${result.message}")
                        }
                    }
                }
            }
        }
    }

    fun clearNewSession() {
        _uiState.update { it.copy(newSessionId = null, newSessionDirectory = null) }
    }

    fun renameSession(sessionId: String, newTitle: String, directory: String? = null) {
        viewModelScope.launch {
            val api = connectionManager.getApi() ?: return@launch
            val result = safeApiCall {
                api.updateSession(sessionId, UpdateSessionRequest(title = newTitle), directory ?: directoryManager.getDirectory())
            }
            when (result) {
                is ApiResult.Success -> {
                    val updated = SessionMapper.mapToDomain(result.data)
                    var updatedSwp: SessionWithProject? = null
                    _uiState.update { state ->
                        val newSessions = state.sessions.map { swp ->
                            if (swp.session.id == sessionId) {
                                val uswp = swp.copy(session = updated)
                                updatedSwp = uswp
                                uswp
                            } else swp
                        }
                        state.copy(sessions = newSessions, workspaces = newSessions.computeWorkspaces())
                    }
                    updatedSwp?.let { sessionDataCache.upsertSession(it) }
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(error = "Failed to rename: ${result.message}") }
                }
            }
        }
    }

    fun shareSession(sessionId: String, directory: String? = null) {
        viewModelScope.launch {
            val api = connectionManager.getApi() ?: return@launch
            val result = safeApiCall { api.shareSession(sessionId, directory ?: directoryManager.getDirectory()) }
            when (result) {
                is ApiResult.Success -> {
                    val updated = SessionMapper.mapToDomain(result.data)
                    _uiState.update { state ->
                        state.copy(
                            sessions = state.sessions.map { swp ->
                                if (swp.session.id == sessionId) swp.copy(session = updated) else swp
                            },
                            shareUrl = updated.shareUrl
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(error = "Failed to share session: ${result.message}") }
                }
            }
        }
    }

    fun unshareSession(sessionId: String, directory: String? = null) {
        viewModelScope.launch {
            val api = connectionManager.getApi() ?: return@launch
            val result = safeApiCall { api.unshareSession(sessionId, directory ?: directoryManager.getDirectory()) }
            when (result) {
                is ApiResult.Success -> {
                    val updated = SessionMapper.mapToDomain(result.data)
                    _uiState.update { state ->
                        state.copy(sessions = state.sessions.map { swp ->
                            if (swp.session.id == sessionId) swp.copy(session = updated) else swp
                        })
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(error = "Failed to unshare: ${result.message}") }
                }
            }
        }
    }

    fun clearShareUrl() {
        _uiState.update { it.copy(shareUrl = null) }
    }

    fun summarizeSession(sessionId: String, directory: String? = null) {
        viewModelScope.launch {
            val api = connectionManager.getApi() ?: return@launch

            // Body is optional per SDK — let server use its own default provider/model
            val result = safeApiCall {
                api.summarizeSession(sessionId, directory ?: directoryManager.getDirectory())
            }
            when (result) {
                is ApiResult.Success -> refresh()
                is ApiResult.Error -> {
                    _uiState.update { it.copy(error = "Failed to summarize: ${result.message}") }
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class SessionListUiState(
    val isLoading: Boolean = false,
    val sessions: List<SessionWithProject> = emptyList(),
    val sessionStatuses: Map<String, SessionStatus> = emptyMap(),
    val projects: List<ProjectInfo> = emptyList(),
    val newSessionId: String? = null,
    val newSessionDirectory: String? = null,
    val shareUrl: String? = null,
    val error: String? = null,
    val workspaces: List<String> = emptyList(),
    val selectedWorkspace: String? = null,
    val selectedSessionIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val showArchived: Boolean = false
)

@Serializable
data class ProjectInfo(
    val id: String,
    val worktree: String,
    val name: String
)

/**
 * Session with optional project metadata for unified sessions view.
 */
@Stable
@Serializable
data class SessionWithProject(
    val session: Session,
    val projectId: String? = null,
    val projectName: String? = null,
    val workspace: String = SessionWithProject.inferWorkspace(projectName, session.directory),
    val archived: Boolean = false
) {
    companion object {
        private val WORK_KEYWORDS = setOf("work", "job", "client", "company", "corp", "office", "lab")
        private val PERSONAL_KEYWORDS = setOf("personal", "home", "private", "hobby", "play", "fun")

        fun inferWorkspace(projectName: String?, directory: String): String {
            val path = listOfNotNull(projectName, directory)
                .flatMap { it.split("/", "\\", "-", "_", ".") }
                .map { it.lowercase().trim() }
            for (p in path) {
                if (p in WORK_KEYWORDS) return "Work"
                if (p in PERSONAL_KEYWORDS) return "Personal"
            }
            return projectName?.takeIf { it.isNotBlank() } ?: "Other"
        }
    }
}
