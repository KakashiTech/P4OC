package dev.blazelight.p4oc.ui.screens.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.blazelight.p4oc.core.network.ApiResult
import dev.blazelight.p4oc.core.network.ConnectionManager
import dev.blazelight.p4oc.core.network.safeApiCall
import dev.blazelight.p4oc.data.remote.mapper.SymbolMapper
import dev.blazelight.p4oc.domain.model.FileNode
import dev.blazelight.p4oc.domain.model.Symbol
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


class FilesViewModel constructor(
    private val connectionManager: ConnectionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    private val _symbolResults = MutableStateFlow<List<Symbol>>(emptyList())
    val symbolResults: StateFlow<List<Symbol>> = _symbolResults.asStateFlow()

    private val pathStack = mutableListOf<String>()
    private var loadFilesJob: Job? = null
    private var loadContentJob: Job? = null

    init {
        loadFiles(".")
    }

    fun refresh() {
        loadFiles(_uiState.value.currentPath.ifBlank { "." })
    }

    fun navigateTo(path: String) {
        pathStack.add(_uiState.value.currentPath)
        loadFiles(path)
    }

    fun navigateUp() {
        val previousPath = pathStack.removeLastOrNull() ?: "."
        loadFiles(previousPath.ifBlank { "." })
    }

    private fun loadFiles(path: String) {
        loadFilesJob?.cancel()
        loadFilesJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val api = connectionManager.getApi() ?: run {
                _uiState.update { it.copy(isLoading = false, error = "Not connected") }
                return@launch
            }
            
            val filesResult = safeApiCall { api.listFiles(path) }
            val statusResult = safeApiCall { api.getFileStatus() }
            
            val statusMap = when (statusResult) {
                is ApiResult.Success -> statusResult.data.associateBy { it.path }
                is ApiResult.Error -> emptyMap()
            }

            when (filesResult) {
                is ApiResult.Success -> {
                    val files = filesResult.data.map { dto ->
                        FileNode(
                            name = dto.name,
                            path = dto.path,
                            absolute = dto.absolute,
                            type = dto.type,
                            ignored = dto.ignored,
                            gitStatus = statusMap[dto.path]?.status
                        )
                    }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            files = files,
                            currentPath = if (path == ".") "" else path
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = filesResult.message)
                    }
                }
            }
        }
    }

    fun searchSymbols(query: String) {
        viewModelScope.launch {
            _symbolResults.value = emptyList()
            if (query.isBlank()) return@launch
            val api = connectionManager.getApi() ?: return@launch
            val result = safeApiCall { api.searchSymbols(query) }
            when (result) {
                is ApiResult.Success -> {
                    _symbolResults.value = result.data.map { SymbolMapper.mapToDomain(it) }
                }
                is ApiResult.Error -> { /* Silently fail for search */ }
            }
        }
    }

    fun loadFileContent(path: String) {
        loadContentJob?.cancel()
        loadContentJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, fileContent = null, error = null) }

            val api = connectionManager.getApi() ?: run {
                _uiState.update { it.copy(isLoading = false, error = "Not connected") }
                return@launch
            }
            val result = safeApiCall { api.readFile(path) }

            when (result) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(isLoading = false, fileContent = result.data.content)
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
            }
        }
    }
}

data class FilesUiState(
    val isLoading: Boolean = false,
    val files: List<FileNode> = emptyList(),
    val currentPath: String = "",
    val fileContent: String? = null,
    val error: String? = null
)
