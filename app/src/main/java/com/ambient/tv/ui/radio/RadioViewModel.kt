package com.ambient.tv.ui.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.radio.RadioBrowserRepository
import com.ambient.tv.data.radio.RadioFacet
import com.ambient.tv.data.radio.RadioStation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RadioSection { POPULAR, GENRES, COUNTRIES, LANGUAGES, FAVORITES, RECENT }

data class RadioUiState(
    val section: RadioSection = RadioSection.POPULAR,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val query: String = "",
    val submittedQuery: String = "",
    val stations: List<RadioStation> = emptyList(),
    val facets: List<RadioFacet> = emptyList(),
    val selectedFacet: RadioFacet? = null,
    val hasMore: Boolean = false,
    val error: Boolean = false,
    val startingStationId: String? = null,
    val favoriteIds: Set<String> = emptySet(),
)

@HiltViewModel
class RadioViewModel @Inject constructor(
    private val repository: RadioBrowserRepository,
    private val preferences: AppPreferences,
    private val playbackLauncher: RadioPlaybackLauncher,
) : ViewModel() {
    private val _state = MutableStateFlow(RadioUiState())
    val state: StateFlow<RadioUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var playJob: Job? = null
    private var entered = false

    fun enter() {
        if (entered) return
        entered = true
        refreshLocalState()
        load(reset = true)
    }

    fun refresh() {
        refreshLocalState()
        val current = _state.value
        when {
            current.submittedQuery.isNotBlank() || current.selectedFacet != null || current.section == RadioSection.POPULAR -> load(reset = true)
            current.section.isFacetSection() -> loadFacets(current.section)
            else -> showLocalSection(current.section)
        }
    }

    fun selectSection(section: RadioSection) {
        val current = _state.value
        if (current.section == section && current.submittedQuery.isBlank() && current.selectedFacet == null) return
        _state.value = current.copy(
            section = section,
            query = "",
            submittedQuery = "",
            stations = emptyList(),
            facets = emptyList(),
            selectedFacet = null,
            hasMore = false,
            error = false,
        )
        when {
            section == RadioSection.POPULAR -> load(reset = true)
            section.isFacetSection() -> loadFacets(section)
            else -> showLocalSection(section)
        }
    }

    fun selectFacet(facet: RadioFacet) {
        if (!_state.value.section.isFacetSection()) return
        _state.value = _state.value.copy(
            selectedFacet = facet,
            query = "",
            submittedQuery = "",
            stations = emptyList(),
            hasMore = false,
            error = false,
        )
        load(reset = true)
    }

    fun clearFacet() {
        val section = _state.value.section
        if (!section.isFacetSection()) return
        _state.value = _state.value.copy(
            selectedFacet = null,
            query = "",
            submittedQuery = "",
            stations = emptyList(),
            hasMore = false,
            error = false,
        )
        loadFacets(section)
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
        val current = _state.value
        when {
            current.selectedFacet != null || current.section == RadioSection.POPULAR -> load(reset = true)
            current.section.isFacetSection() -> loadFacets(current.section)
            else -> showLocalSection(current.section)
        }
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
            else -> return
        }
        _state.value = _state.value.copy(
            loading = false,
            loadingMore = false,
            stations = stations,
            facets = emptyList(),
            selectedFacet = null,
            hasMore = false,
            error = false,
        )
    }

    private fun loadFacets(section: RadioSection) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                loading = true,
                loadingMore = false,
                stations = emptyList(),
                facets = emptyList(),
                hasMore = false,
                error = false,
            )
            runCatching {
                when (section) {
                    RadioSection.GENRES -> repository.genres()
                    RadioSection.COUNTRIES -> repository.countries()
                    RadioSection.LANGUAGES -> repository.languages()
                    else -> emptyList()
                }
            }.onSuccess { facets ->
                _state.value = _state.value.copy(
                    loading = false,
                    facets = facets,
                    error = false,
                )
            }.onFailure {
                _state.value = _state.value.copy(
                    loading = false,
                    error = true,
                )
            }
        }
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
                val current = _state.value
                val facet = current.selectedFacet
                repository.search(
                    query = current.submittedQuery.takeIf(String::isNotBlank),
                    countryCode = facet?.value.takeIf { current.section == RadioSection.COUNTRIES },
                    language = facet?.value.takeIf { current.section == RadioSection.LANGUAGES },
                    tag = facet?.value.takeIf { current.section == RadioSection.GENRES },
                    exactFilters = facet != null,
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
            _state.value = _state.value.copy(startingStationId = station.id, error = false)
            val visibleStations = _state.value.stations
            playbackLauncher.play(station, visibleStations)
                .onSuccess {
                    refreshLocalState()
                    _state.value = _state.value.copy(startingStationId = null)
                }
                .onFailure {
                    _state.value = _state.value.copy(startingStationId = null, error = true)
                }
        }
    }

    private fun RadioSection.isFacetSection(): Boolean =
        this == RadioSection.GENRES || this == RadioSection.COUNTRIES || this == RadioSection.LANGUAGES

    companion object {
        private const val PAGE_SIZE = 48
    }
}
