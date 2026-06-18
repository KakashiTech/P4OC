package dev.blazelight.p4oc.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.blazelight.p4oc.core.datastore.ConnectionSettings
import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.core.network.ConnectionManager
import dev.blazelight.p4oc.core.update.UpdateManager
import dev.blazelight.p4oc.core.update.UpdateCheckResult
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch


class SettingsViewModel constructor(
    private val settingsDataStore: SettingsDataStore,
    private val connectionManager: ConnectionManager,
    private val updateManager: UpdateManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val connectionSettings: StateFlow<ConnectionSettings> =
        settingsDataStore.connectionSettings
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionSettings())

    /** Whether the app is currently connected to an OpenCode server. */
    val isConnected: StateFlow<Boolean> =
        connectionManager.connectionState
            .map { it.isConnected }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoUpdateEnabled: StateFlow<Boolean> =
        settingsDataStore.autoUpdateEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _updateResult = MutableStateFlow<UpdateCheckResult?>(null)
    val updateResult: StateFlow<UpdateCheckResult?> = _updateResult.asStateFlow()

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                settingsDataStore.serverUrl,
                settingsDataStore.isLocalServer,
                settingsDataStore.themeMode
            ) { url, isLocal, theme ->
                SettingsUiState(
                    serverUrl = url,
                    isLocal = isLocal,
                    themeMode = theme
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch {
            settingsDataStore.setThemeMode(mode)
        }
    }

    fun toggleAutoReconnect() {
        viewModelScope.launch {
            val current = connectionSettings.value
            settingsDataStore.updateConnectionSettings(
                current.copy(autoReconnect = !current.autoReconnect)
            )
        }
    }

    fun updateReconnectTimeout(seconds: Int) {
        viewModelScope.launch {
            val current = connectionSettings.value
            settingsDataStore.updateConnectionSettings(
                current.copy(reconnectTimeoutSeconds = seconds.coerceIn(15, 120))
            )
        }
    }

    fun toggleAutoUpdate() {
        viewModelScope.launch {
            settingsDataStore.setAutoUpdateEnabled(!autoUpdateEnabled.value)
        }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            _isCheckingUpdate.value = true
            _updateResult.value = updateManager.checkForUpdate()
            _isCheckingUpdate.value = false
            if (_updateResult.value is UpdateCheckResult.UpToDate) {
                settingsDataStore.setLastUpdateCheck(System.currentTimeMillis())
            }
        }
    }

    fun clearUpdateResult() {
        _updateResult.value = null
    }

    fun downloadAndInstall() {
        val result = _updateResult.value
        if (result !is UpdateCheckResult.Available) return
        viewModelScope.launch {
            _isDownloading.value = true
            _downloadProgress.value = 0f
            val uriResult = updateManager.downloadApk(result.info) { progress ->
                _downloadProgress.value = progress
            }
            uriResult.onSuccess { uri ->
                updateManager.installApk(uri)
                _isDownloading.value = false
            }.onFailure { error ->
                _updateResult.value = UpdateCheckResult.Error("Download failed: ${error.message}")
                _isDownloading.value = false
            }
        }
    }

    suspend fun checkForUpdatesOnStartup() {
        val enabled = settingsDataStore.autoUpdateEnabled.first()
        val lastCheck = settingsDataStore.lastUpdateCheck.first()
        val oneDayMs = 24 * 60 * 60 * 1000L
        if (enabled && (System.currentTimeMillis() - lastCheck) > oneDayMs) {
            val result = updateManager.checkForUpdate()
            if (result is UpdateCheckResult.Available) {
                updateManager.showUpdateNotification(result.info)
            }
            if (result is UpdateCheckResult.UpToDate || result is UpdateCheckResult.Available) {
                settingsDataStore.setLastUpdateCheck(System.currentTimeMillis())
            }
        }
    }

    suspend fun disconnect() {
        connectionManager.disconnect()
        settingsDataStore.clearLastConnection()
    }
}

data class SettingsUiState(
    val serverUrl: String = "",
    val isLocal: Boolean = true,
    val themeMode: String = "system"
)
