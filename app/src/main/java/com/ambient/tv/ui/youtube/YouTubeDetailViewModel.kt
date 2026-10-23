package com.ambient.tv.ui.youtube

import com.ambient.tv.util.suspendResult
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.youtube.SmartTubeEngine
import com.ambient.tv.youtube.YouTubeMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class YouTubeDetailState(val loading: Boolean = true, val metadata: YouTubeMetadata? = null, val error: String? = null, val offline: Boolean = false)

@HiltViewModel
class YouTubeDetailViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val engine: SmartTubeEngine,
    private val cache: com.ambient.tv.youtube.YouTubeContentCache,
    val availability: com.ambient.tv.data.browse.SourceAvailability,
) : ViewModel() {
    private val videoId = requireNotNull(savedStateHandle.get<String>("videoId"))
    private val _uiState = MutableStateFlow(YouTubeDetailState())
    val uiState: StateFlow<YouTubeDetailState> = _uiState.asStateFlow()
    private var loadJob: kotlinx.coroutines.Job? = null
    init {
        load()
        viewModelScope.launch {
            availability.changes.collect {
                val offline = !availability.hasNetwork()
                val previous = _uiState.value
                _uiState.value = previous.copy(offline = offline)
                if (previous.offline && !offline) load()
            }
        }
    }
    fun retryOffline() { if (_uiState.value.offline && availability.hasNetwork() && loadJob?.isActive != true) load() }
    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            cache.restore()
            val revision = cache.revision()
            cache.metadata(videoId)?.let { _uiState.value = YouTubeDetailState(false, it, offline = !availability.hasNetwork()) }
            suspendResult { engine.metadata(videoId) }.onSuccess {
                if (cache.revision() == revision) {
                    cache.putMetadata(it, revision)
                    _uiState.value = YouTubeDetailState(false, it)
                }
            }.onFailure {
                _uiState.value = _uiState.value.copy(loading = false, offline = true, error = it.message)
            }
        }
    }
}
