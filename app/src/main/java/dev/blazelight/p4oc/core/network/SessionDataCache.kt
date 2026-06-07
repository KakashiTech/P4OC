package dev.blazelight.p4oc.core.network

import android.content.Context
import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.data.remote.mapper.SessionMapper
import dev.blazelight.p4oc.ui.screens.sessions.ProjectInfo
import dev.blazelight.p4oc.ui.screens.sessions.SessionWithProject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "SessionDataCache"
private const val CACHE_FILE_NAME = "session_cache.json"

/**
 * Branch-prediction cache for sessions data + disk persistence.
 *
 * When a connection succeeds (ServerViewModel), this singleton pre-fetches
 * the full sessions + projects payload in the background — before the user
 * even taps a session or the SessionListScreen is composed.
 *
 * SessionListViewModel.init consumes the cached result instantly (0ms wait),
 * then refreshes in the background to pick up any delta since the prefetch.
 *
 * Orphan sessions (custom-directory, outside any known project) are persisted
 * to disk so they survive app restart even though the server never returns
 * them in any list query.
 *
 * This is the data-layer equivalent of CPU branch prediction:
 * "After connecting, the user will almost certainly open SessionListScreen next."
 */
class SessionDataCache(
    private val connectionManager: ConnectionManager,
    context: Context
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
    private val persistMutex = Mutex()

    @Volatile
    private var cachedResult: CachedSessions? = null

    init {
        restoreFromDisk()
        // Auto-invalidate cache when connection drops,
        // so stale sessions from a previous server never leak.
        scope.launch {
            connectionManager.connectionState.collect { state ->
                if (state == ConnectionState.Disconnected) {
                    cachedResult = null
                }
            }
        }
    }

    @Serializable
    data class CachedSessions(
        val sessions: List<SessionWithProject>,
        val projects: List<ProjectInfo>,
        val knownDirectories: Set<String> = emptySet(),
        val fetchedAtMs: Long = System.currentTimeMillis(),
        val serverBaseUrl: String = ""
    )

    /** True if cache is fresh (< 30 s old) AND belongs to the currently-connected server. */
    val hasFreshData: Boolean
        get() {
            val cached = cachedResult ?: return false
            val currentUrl = connectionManager.currentBaseUrl ?: return false
            return cached.serverBaseUrl == currentUrl &&
                   cached.sessions.isNotEmpty() &&
                   cached.projects.isNotEmpty() &&
                   System.currentTimeMillis() - cached.fetchedAtMs < 30_000L
        }

    /**
     * Returns cached data immediately if it belongs to the current server, null otherwise.
     * Callers should always check server identity via this method rather than holding a
     * direct reference to [cachedResult].
     */
    fun peek(): CachedSessions? {
        val cached = cachedResult ?: return null
        val currentUrl = connectionManager.currentBaseUrl ?: return null
        return if (cached.serverBaseUrl == currentUrl) cached else null
    }

    /**
     * Pre-fetch sessions and projects in the background.
     * Called by ServerViewModel right after a successful connection —
     * before navigation happens, so the data is ready when the screen appears.
     */
    fun prewarm() {
        scope.launch {
            val targetUrl = connectionManager.currentBaseUrl ?: run {
                AppLog.w(TAG, "prewarm: no active connection – skipping")
                return@launch
            }
            // Invalidate stale cache from a previous server before fetching.
            if (cachedResult?.serverBaseUrl != targetUrl) {
                AppLog.d(TAG, "prewarm: server changed ($targetUrl) – invalidating old cache")
                cachedResult = null
            }
            AppLog.d(TAG, "prewarm: starting background session prefetch for $targetUrl")
            try {
                val result = fetchSessions(targetUrl)
                cachedResult = result
                persistToDisk(result)
                AppLog.d(TAG, "prewarm: cached ${result.sessions.size} sessions, ${result.projects.size} projects")
            } catch (e: Exception) {
                AppLog.w(TAG, "prewarm: prefetch failed — will load on demand", e)
            }
        }
    }

    /** Invalidate on disconnect so stale data is never shown after reconnect. */
    fun invalidate() {
        cachedResult = null
        scope.launch { persistToDisk(null) }
    }

    /**
     * Update the knownDirectories set in the cache without changing sessions/projects.
     * Called by SessionListViewModel after a successful load to register directories
     * that should be queried on future fetches.
     */
    fun updateKnownDirectories(dirs: Set<String>) {
        val current = cachedResult ?: return
        cachedResult = current.copy(knownDirectories = dirs)
        scope.launch { persistToDisk(cachedResult) }
    }

    /**
     * Insert or update a session in the cache so a newly-created session survives
     * ViewModel re-creation (e.g. tab restore) before the next full fetch.
     *
     * When initialising from scratch (no prior cache), uses `fetchedAtMs = 0L`
     * so [hasFreshData] returns false — preventing the ViewModel from skipping
     * the full fetch and showing only this single session.
     */
    fun upsertSession(session: SessionWithProject) {
        val current = cachedResult
        val updatedSessions = if (current != null) {
            val existing = current.sessions.indexOfFirst { it.session.id == session.session.id }
            if (existing >= 0) {
                current.sessions.toMutableList().apply { set(existing, session) }
            } else {
                listOf(session) + current.sessions
            }
        } else {
            listOf(session)
        }
        cachedResult = CachedSessions(
            sessions = updatedSessions,
            projects = current?.projects ?: emptyList(),
            fetchedAtMs = current?.fetchedAtMs ?: 0L,
            serverBaseUrl = connectionManager.currentBaseUrl ?: ""
        )
        AppLog.d(TAG, "upsertSession: id=${session.session.id}, title=${session.session.title}, " +
            "cache was ${if (current != null) "present (${current.sessions.size} sessions)" else "null"}")
        scope.launch { persistToDisk(cachedResult) }
    }

    /** Remove a session from the cache so deleted sessions don't reappear via staleFallback. */
    fun removeSession(sessionId: String) {
        val current = cachedResult ?: return
        val filtered = current.sessions.filter { it.session.id != sessionId }
        if (filtered.size == current.sessions.size) return
        cachedResult = CachedSessions(
            sessions = filtered,
            projects = current.projects,
            fetchedAtMs = current.fetchedAtMs,
            serverBaseUrl = current.serverBaseUrl
        )
        AppLog.d(TAG, "removeSession: id=$sessionId, remaining=${filtered.size}")
        scope.launch { persistToDisk(cachedResult) }
    }

    // -----------------------------------------------------------------------
    // Disk persistence
    // -----------------------------------------------------------------------

    private fun restoreFromDisk() {
        if (!cacheFile.exists()) return
        try {
            val text = cacheFile.readText()
            val restored = json.decodeFromString<CachedSessions>(text)
            cachedResult = restored
            AppLog.d(TAG, "restored ${restored.sessions.size} sessions, ${restored.projects.size} projects from disk")
        } catch (e: Exception) {
            AppLog.w(TAG, "failed to restore cache from disk", e)
            cacheFile.delete()
        }
    }

    private suspend fun persistToDisk(data: CachedSessions?) = persistMutex.withLock {
        try {
            if (data == null) {
                if (cacheFile.exists()) cacheFile.delete()
                AppLog.d(TAG, "persist: cleared disk cache")
            } else {
                val text = json.encodeToString(data)
                cacheFile.writeText(text)
                AppLog.d(TAG, "persist: wrote ${data.sessions.size} sessions to disk")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "failed to persist cache to disk", e)
        }
    }

    private suspend fun fetchSessions(serverBaseUrl: String): CachedSessions = coroutineScope {
        val api = connectionManager.getApi() ?: return@coroutineScope CachedSessions(emptyList(), emptyList())

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

        val globalDeferred = async {
            val result = safeApiCall { api.listSessions(directory = null, roots = true, limit = 100) }
            when (result) {
                is ApiResult.Success -> result.data.map { dto ->
                    SessionWithProject(session = SessionMapper.mapToDomain(dto))
                }
                is ApiResult.Error -> emptyList()
            }
        }

        val projectWorktrees = projects.map { it.worktree }.toSet()
        val projectDeferreds = projects.map { project ->
            async {
                val result = safeApiCall { api.listSessions(directory = project.worktree, roots = true, limit = 100) }
                when (result) {
                    is ApiResult.Success -> result.data.map { dto ->
                        SessionWithProject(
                            session = SessionMapper.mapToDomain(dto),
                            projectId = project.id,
                            projectName = project.name
                        )
                    }
                    is ApiResult.Error -> emptyList()
                }
            }
        }

        // Also query known directories that are NOT already project worktrees
        // This discovers sessions in project directories the server doesn't list,
        // e.g. sibling projects, custom workspaces, or unnamed project folders.
        val previousKnownDirs = cachedResult?.knownDirectories ?: emptySet()
        val extraDirs = previousKnownDirs - projectWorktrees - setOf("")
        val extraDeferreds = extraDirs.map { dir ->
            async {
                val result = safeApiCall { api.listSessions(directory = dir, roots = true, limit = 100) }
                when (result) {
                    is ApiResult.Success -> result.data.map { dto ->
                        SessionWithProject(session = SessionMapper.mapToDomain(dto))
                    }
                    is ApiResult.Error -> emptyList()
                }
            }
        }

        val globalSessions = globalDeferred.await()
        val projectSessions = projectDeferreds.map { it.await() }.flatten()
        val extraSessions = extraDeferreds.map { it.await() }.flatten()
        val projectIds = projectSessions.map { it.session.id }.toSet()
        val extraIds = extraSessions.map { it.session.id }.toSet()
        val uniqueGlobal = globalSessions.filter { it.session.id !in projectIds && it.session.id !in extraIds }
        val uniqueExtra = extraSessions.filter { it.session.id !in projectIds }

        // Collect directories for the registry — EVERY session's directory + every project worktree
        val allSeenDirs = (projects.map { it.worktree } + (uniqueGlobal + uniqueExtra + projectSessions).map { it.session.directory })
            .filter { it.isNotBlank() }
            .distinct()
            .toSet()
        val mergedDirs = previousKnownDirs + allSeenDirs

        // Keep custom-directory sessions (projectId==null) that the server never
        // returns in list queries — they're outside any known project worktree.
        val previousIds = (uniqueGlobal + uniqueExtra + projectSessions).map { it.session.id }.toSet()
        val staleFallback = cachedResult?.sessions?.filter { swp ->
            swp.projectId == null && swp.session.id !in previousIds
        } ?: emptyList()

        CachedSessions(
            sessions = (uniqueGlobal + uniqueExtra + staleFallback + projectSessions).sortedByDescending { it.session.updatedAt },
            projects = projects.sortedByDescending { it.worktree },
            knownDirectories = mergedDirs,
            serverBaseUrl = serverBaseUrl
        )
    }
}
