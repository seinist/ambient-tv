package com.ambient.tv.ui.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.radio.RadioBrowserRepository
import com.ambient.tv.data.radio.RadioStation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RadioSection { POPULAR, FAVORITES, RECENT }

data class RadioUiState(
    val section: RadioSection = RadioSection.POPULAR,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val query: String = "",
    val submittedQuery: String = "",
    val stations: List<RadioStation> = emptyList(),
    val hasMore: Boolean = false,
    val error: Boolean = false,
    val startingStationId: String? = null,
    val favoriteIds: Set<String> = emptySet(),
)

data class RadioPlaybackRequest(
    val station: RadioStation,
    val streamUrl: String,
    val requestId: Long,
)

@HiltViewModel
class RadioViewModel @Inject constructor(
    private val repository: RadioBrowserRepository,
    private val preferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(RadioUiState())
    val state: StateFlow<RadioUiState> = _state.asStateFlow()

    private val _playbackRequest = MutableStateFlow<RadioPlaybackRequest?>(null)
    val playbackRequest: StateFlow<RadioPlaybackRequest?> = _playbackRequest.asStateFlow()

    private var loadJob: Job? = null
    private var playJob: Job? = null
    private var entered = false
    private var playbackRequestId = 0L

    fun enter() {
        if (entered) return
        entered = true
        refreshLocalState()
        load(reset = true)
    }

    fun refresh() {
        refreshLocalState()
        if (_state.value.section == RadioSection.POPULAR || _state.value.submittedQuery.isNotBlank()) load(reset = true)
        else showLocalSection(_state.value.section)
    }

    fun selectSection(section: RadioSection) {
        if (_state.value.section == section && _state.value.submittedQuery.isBlank()) return
        _state.value = _state.value.copy(section = section, query = "", submittedQuery = "", error = false)
        if (section == RadioSection.POPULAR) load(reset = true) else showLocalSection(section)
    }

    fun onQueryChanged(value: String) {
        _state.value = _state.value.copy(query = value.take(120))
    }

    fun submitSearch() {
        val query = _state.value.query.trim()
        if (query.isNotEmpty() && query.length < 2) return
        _state.value = _state.value.copy(submittedQuery = query)
        load(reset = true)
    }

    fun clearSearch() {
        _state.value = _state.value.copy(query = "", submittedQuery = "")
        if (_state.value.section == RadioSection.POPULAR) load(reset = true)
        else showLocalSection(_state.value.section)
    }

    fun toggleFavorite(station: RadioStation) {
        val favorite = station.id !in _state.value.favoriteIds
        preferences.setRadioFavorite(station, favorite)
        refreshLocalState()
        if (_state.value.section == RadioSection.FAVORITES && _state.value.submittedQuery.isBlank()) {
            showLocalSection(RadioSection.FAVORITES)
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.loadingMore || !current.hasMore) return
        load(reset = false)
    }

    private fun refreshLocalState() {
        _state.value = _state.value.copy(
            favoriteIds = preferences.radioFavorites.mapTo(linkedSetOf(), RadioStation::id),
        )
    }

    private fun showLocalSection(section: RadioSection) {
        val stations = when (section) {
            RadioSection.FAVORITES -> preferences.radioFavorites
            RadioSection.RECENT -> preferences.recentRadioStations
            RadioSection.POPULAR -> return
        }
        _state.value = _state.value.copy(
            loading = false,
            loadingMore = false,
            stations = stations,
            hasMore = false,
            error = false,
        )
    }

    private fun load(reset: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val before = _state.value
            val offset = if (reset) 0 else before.stations.size
            _state.value = before.copy(
                loading = reset,
                loadingMore = !reset,
                stations = if (reset) emptyList() else before.stations,
                error = false,
            )
            runCatching {
                repository.search(
                    query = _state.value.submittedQuery.takeIf(String::isNotBlank),
                    offset = offset,
                    limit = PAGE_SIZE,
                )
            }.onSuccess { page ->
                val existing = if (reset) emptyList() else _state.value.stations
                _state.value = _state.value.copy(
                    loading = false,
                    loadingMore = false,
                    stations = (existing + page.items).distinctBy(RadioStation::id),
                    hasMore = page.hasMore,
                    error = false,
                )
            }.onFailure {
                _state.value = _state.value.copy(
                    loading = false,
                    loadingMore = false,
                    error = true,
                )
            }
        }
    }

    fun play(station: RadioStation) {
        playJob?.cancel()
        playJob = viewModelScope.launch {
            _state.value = _state.value.copy(startingStationId = station.id)
            val streamUrl = repository.playbackUrl(station)
            preferences.recordRadioStation(station)
            refreshLocalState()
            _playbackRequest.value = RadioPlaybackRequest(
                station = station,
                streamUrl = streamUrl,
                requestId = ++playbackRequestId,
            )
        }
    }

    fun playbackStarted(requestId: Long) {
        if (_playbackRequest.value?.requestId == requestId) {
            _state.value = _state.value.copy(startingStationId = null)
            _playbackRequest.value = null
        }
    }

    fun playbackFailed(requestId: Long) {
        if (_playbackRequest.value?.requestId == requestId) {
            _state.value = _state.value.copy(startingStationId = null, error = true)
            _playbackRequest.value = null
        }
    }

    companion object {
        private const val PAGE_SIZE = 48
    }
}
