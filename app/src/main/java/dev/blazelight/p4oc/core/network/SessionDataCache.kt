package dev.blazelight.p4oc.core.network

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

private const val TAG = "SessionDataCache"

/**
 * Branch-prediction cache for sessions data.
 *
 * When a connection succeeds (ServerViewModel), this singleton pre-fetches
 * the full sessions + projects payload in the background — before the user
 * even taps a session or the SessionListScreen is composed.
 *
 * SessionListViewModel.init consumes the cached result instantly (0ms wait),
 * then refreshes in the background to pick up any delta since the prefetch.
 *
 * This is the data-layer equivalent of CPU branch prediction:
 * "After connecting, the user will almost certainly open SessionListScreen next."
 */
class SessionDataCache(private val connectionManager: ConnectionManager) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var cachedResult: CachedSessions? = null

    data class CachedSessions(
        val sessions: List<SessionWithProject>,
        val projects: List<ProjectInfo>,
        val fetchedAtMs: Long = System.currentTimeMillis(),
        val serverBaseUrl: String = ""
    )

    /** True if cache is fresh (< 30 s old) AND belongs to the currently-connected server. */
    val hasFreshData: Boolean
        get() {
            val cached = cachedResult ?: return false
            val currentUrl = connectionManager.currentBaseUrl ?: return false
            return cached.serverBaseUrl == currentUrl &&
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
                AppLog.d(TAG, "prewarm: cached ${result.sessions.size} sessions, ${result.projects.size} projects")
            } catch (e: Exception) {
                AppLog.w(TAG, "prewarm: prefetch failed — will load on demand", e)
            }
        }
    }

    /** Invalidate on disconnect so stale data is never shown after reconnect. */
    fun invalidate() {
        cachedResult = null
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

        val globalSessions = globalDeferred.await()
        val projectSessions = projectDeferreds.map { it.await() }.flatten()
        val projectIds = projectSessions.map { it.session.id }.toSet()
        val uniqueGlobal = globalSessions.filter { it.session.id !in projectIds }

        CachedSessions(
            sessions = (uniqueGlobal + projectSessions).sortedByDescending { it.session.updatedAt },
            projects = projects.sortedByDescending { it.worktree },
            serverBaseUrl = serverBaseUrl
        )
    }
}
