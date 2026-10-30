package com.ambient.tv.ui.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.radio.RadioBrowserRepository
import com.ambient.tv.data.radio.RadioFacet
import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.data.browse.BrowseWorkBudget
import com.ambient.tv.util.suspendResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RadioViewMode { GRID, LIST }

/** Selection is scoped to the exact Radio Browser page, not shared across facets or searches. */
data class RadioFocusScope(val section: RadioSection, val facetId: String?, val query: String)

/** Session-only memory: parameterized sidebar routes replace their ViewModels on tab changes. */
internal object RadioFocusMemory {
    private const val MAX_ENTRIES = 64
    private val selections = linkedMapOf<RadioFocusScope, String>()

    @Synchronized fun last(scope: RadioFocusScope): String? = selections[scope]

    @Synchronized fun save(scope: RadioFocusScope, itemId: String) {
        selections.remove(scope)
        selections[scope] = itemId
        if (selections.size > MAX_ENTRIES) selections.remove(selections.keys.first())
    }
}

internal fun RadioUiState.focusScope(): RadioFocusScope = RadioFocusScope(section, selectedFacet?.id, submittedQuery)

/** Last browsed item, then the playing station if present, otherwise the first visible card. */
internal fun radioInitialFocusId(savedId: String?, playingId: String?, ids: List<String>): String? =
    savedId?.takeIf { it in ids } ?: playingId?.takeIf { it in ids } ?: ids.firstOrNull()

enum class RadioSection(val key: String) {
    POPULAR("popular"),
    FAVORITES("favorites"),
    RECENT("recent"),
    GENRES("genres"),
    COUNTRIES("countries"),
    LANGUAGES("languages");

    companion object {
        fun fromKey(key: String?): RadioSection? = entries.firstOrNull { it.key == key?.lowercase() }
    }
}

internal val RadioSection.isFacetSection: Boolean
    get() = this == RadioSection.GENRES || this == RadioSection.COUNTRIES || this == RadioSection.LANGUAGES

/** Category overviews are always text lists; the saved user choice still controls station pages. */
internal fun RadioUiState.isFacetOverview(): Boolean =
    section.isFacetSection && selectedFacet == null && submittedQuery.isBlank()

internal fun RadioUiState.effectiveViewMode(): RadioViewMode =
    if (isFacetOverview()) RadioViewMode.LIST else viewMode

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
    /** Raw API cursor, never the number of mapped/deduplicated stations. */
    val nextOffset: Int = 0,
    val returnFocusRevision: Int = 0,
    val error: Boolean = false,
    val startingStationId: String? = null,
    val favoriteIds: Set<String> = emptySet(),
    val viewMode: RadioViewMode = RadioViewMode.GRID,
    val gridColumns: Int = 8,
    val listRows: Int = 7,
    /** Prevent the sidebar from focusing header actions before the asynchronous results arrive. */
    val contentReady: Boolean = false,
)

@HiltViewModel
class RadioViewModel @Inject constructor(
    private val repository: RadioBrowserRepository,
    private val preferences: AppPreferences,
    private val playbackLauncher: RadioPlaybackLauncher,
    private val playbackQueue: RadioPlaybackQueue,
) : ViewModel() {
    private val _state = MutableStateFlow(RadioUiState())
    val state: StateFlow<RadioUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var playJob: Job? = null
    private var focusLoadJob: Job? = null
    private var focusedStationId: String? = null
    internal fun lastFocusedContent(scope: RadioFocusScope): String? = RadioFocusMemory.last(scope)

    /** Synchronize pages loaded by the Player and prefer its last station on returning. */
    fun consumePlayerReturn(): Boolean {
        val scope = _state.value.focusScope()
        val returned = playbackQueue.consumeReturn(scope) ?: return false
        val currentId = returned.currentId ?: return false
        RadioFocusMemory.save(scope, currentId)
        val before = _state.value
        val known = before.stations.mapTo(HashSet()) { it.id }
        val stations = before.stations + returned.stations.filter { known.add(it.id) }
        _state.value = before.copy(
            stations = stations,
            nextOffset = returned.source?.nextOffset ?: before.nextOffset,
            hasMore = returned.source?.hasMore ?: before.hasMore,
            returnFocusRevision = before.returnFocusRevision + 1,
        )
        return true
    }

    fun onFacetFocused(facetId: String) {
        RadioFocusMemory.save(_state.value.focusScope(), facetId)
    }
    private var focusLoadStationId: String? = null
    private var entered = false

    init {
        viewModelScope.launch {
            var favoriteIds = preferences.radioFavorites.mapTo(linkedSetOf(), RadioStation::id)
            preferences.changes().collect {
                val nextFavoriteIds = preferences.radioFavorites.mapTo(linkedSetOf(), RadioStation::id)
                val favoritesChanged = nextFavoriteIds != favoriteIds
                favoriteIds = nextFavoriteIds
                if (favoritesChanged) {
                    _state.value = _state.value.copy(favoriteIds = nextFavoriteIds)
                    if (_state.value.section == RadioSection.FAVORITES &&
                        _state.value.submittedQuery.isBlank()
                    ) {
                        showLocalSection(RadioSection.FAVORITES)
                    }
                }
                if (_state.value.section == RadioSection.RECENT &&
                    _state.value.submittedQuery.isBlank()
                ) {
                    showLocalSection(RadioSection.RECENT)
                }
            }
        }
    }

    fun enter(initialSection: RadioSection? = null) {
        if (entered) {
            initialSection?.let(::selectSection)
            return
        }
        entered = true
        val section = initialSection ?: _state.value.section
        _state.value = _state.value.copy(
            section = section,
            viewMode = runCatching {
                RadioViewMode.valueOf(preferences.sectionViewMode(RADIO_VIEW_STATE_KEY) ?: RadioViewMode.GRID.name)
            }.getOrDefault(RadioViewMode.GRID),
            gridColumns = preferences.libraryGridColumns,
            listRows = preferences.libraryListRows,
        )
        refreshLocalState()
        when {
            section == RadioSection.POPULAR -> loadStations(reset = true)
            section.isFacetSection -> loadFacets(section)
            else -> showLocalSection(section)
        }
    }

    fun refresh() {
        resetFocusPaging()
        refreshLocalState()
        val current = _state.value
        when {
            current.submittedQuery.isNotBlank() || current.selectedFacet != null -> loadStations(reset = true)
            current.section == RadioSection.POPULAR -> loadStations(reset = true)
            current.section.isFacetSection -> loadFacets(current.section)
            else -> showLocalSection(current.section)
        }
    }

    fun selectSection(section: RadioSection) {
        val current = _state.value
        if (current.section == section &&
            current.submittedQuery.isBlank() &&
            current.selectedFacet == null
        ) return

        loadJob?.cancel()
        resetFocusPaging()
        _state.value = current.copy(
            section = section,
            query = "",
            submittedQuery = "",
            stations = emptyList(),
            facets = emptyList(),
            selectedFacet = null,
            hasMore = false,
            nextOffset = 0,
            error = false,
            contentReady = false,
        )
        when {
            section == RadioSection.POPULAR -> loadStations(reset = true)
            section.isFacetSection -> loadFacets(section)
            else -> showLocalSection(section)
        }
    }

    fun selectFacet(facet: RadioFacet) {
        if (!_state.value.section.isFacetSection) return
        resetFocusPaging()
        _state.value = _state.value.copy(
            selectedFacet = facet,
            query = "",
            submittedQuery = "",
            stations = emptyList(),
            hasMore = false,
            nextOffset = 0,
            error = false,
            contentReady = false,
        )
        loadStations(reset = true)
    }

    fun clearFacet() {
        val section = _state.value.section
        if (!section.isFacetSection || _state.value.selectedFacet == null) return
        loadJob?.cancel()
        resetFocusPaging()
        _state.value = _state.value.copy(
            query = "",
            submittedQuery = "",
            stations = emptyList(),
            selectedFacet = null,
            hasMore = false,
            error = false,
            contentReady = false,
        )
        loadFacets(section)
    }

    fun onQueryChanged(value: String) {
        _state.value = _state.value.copy(query = value.take(120))
    }

    fun submitSearch() {
        val query = _state.value.query.trim()
        if (query.isNotEmpty() && query.length < 2) return
        if (query.isBlank()) {
            clearSearch()
            return
        }
        resetFocusPaging()
        _state.value = _state.value.copy(submittedQuery = query, contentReady = false)
        loadStations(reset = true)
    }

    fun clearSearch() {
        resetFocusPaging()
        val current = _state.value
        _state.value = current.copy(query = "", submittedQuery = "", contentReady = false)
        when {
            current.selectedFacet != null -> loadStations(reset = true)
            current.section == RadioSection.POPULAR -> loadStations(reset = true)
            current.section.isFacetSection -> loadFacets(current.section)
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

    fun selectViewMode(mode: RadioViewMode) {
        preferences.setSectionViewMode(RADIO_VIEW_STATE_KEY, mode.name)
        _state.value = _state.value.copy(viewMode = mode)
    }

    fun setGridColumns(columns: Int) {
        preferences.libraryGridColumns = columns
        _state.value = _state.value.copy(gridColumns = preferences.libraryGridColumns)
    }

    fun setListRows(rows: Int) {
        preferences.libraryListRows = rows
        _state.value = _state.value.copy(listRows = preferences.libraryListRows)
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.loadingMore || !current.hasMore) return
        loadStations(reset = false)
    }

    fun onStationFocused(stationId: String) {
        RadioFocusMemory.save(_state.value.focusScope(), stationId)
        focusedStationId = stationId
        val current = _state.value
        val index = current.stations.indexOfFirst { it.id == stationId }
        if (!radioShouldLoadOnFocus(index, current.stations.size, current.hasMore, current.loading, current.loadingMore)) {
            focusLoadJob?.cancel()
            focusLoadJob = null
            focusLoadStationId = null
            return
        }
        // Like SmartTube: moving inside the pending end region keeps the same settle timer.
        if (focusLoadJob?.isActive == true) return
        focusLoadStationId = stationId
        focusLoadJob = viewModelScope.launch {
            delay(RADIO_FOCUS_SETTLE_MS)
            while (BrowseWorkBudget.scrolling.get() > 0) delay(50)
            val latest = _state.value
            val latestFocusedId = focusedStationId ?: return@launch
            val latestIndex = latest.stations.indexOfFirst { it.id == latestFocusedId }
            if (!radioShouldLoadOnFocus(
                    latestIndex,
                    latest.stations.size,
                    latest.hasMore,
                    latest.loading,
                    latest.loadingMore,
                )
            ) return@launch
            loadMore()
        }
    }

    private fun resetFocusPaging() {
        focusLoadJob?.cancel()
        focusLoadJob = null
        focusedStationId = null
        focusLoadStationId = null
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
            hasMore = false,
            error = false,
            contentReady = true,
        )
    }

    private fun loadFacets(section: RadioSection) {
        if (!section.isFacetSection) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                loading = true,
                loadingMore = false,
                stations = emptyList(),
                facets = emptyList(),
                selectedFacet = null,
                hasMore = false,
                error = false,
                contentReady = false,
            )
            suspendResult {
                when (section) {
                    RadioSection.GENRES -> repository.tags(FACET_LIMIT)
                    RadioSection.COUNTRIES -> repository.countries(FACET_LIMIT)
                    RadioSection.LANGUAGES -> repository.languages(FACET_LIMIT)
                    else -> emptyList()
                }
            }.onSuccess { facets ->
                if (_state.value.section == section && _state.value.selectedFacet == null) {
                    _state.value = _state.value.copy(
                        loading = false,
                        facets = facets,
                        error = false,
                        contentReady = true,
                    )
                }
            }.onFailure {
                if (_state.value.section == section && _state.value.selectedFacet == null) {
                    _state.value = _state.value.copy(
                        loading = false,
                        facets = emptyList(),
                        error = true,
                        contentReady = true,
                    )
                }
            }
        }
    }

    private fun loadStations(reset: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val before = _state.value
            val offset = if (reset) 0 else before.nextOffset
            _state.value = before.copy(
                loading = reset,
                loadingMore = !reset,
                stations = if (reset) emptyList() else before.stations,
                nextOffset = if (reset) 0 else before.nextOffset,
                error = false,
                contentReady = if (reset) false else before.contentReady,
            )
            val facet = _state.value.selectedFacet
            val section = _state.value.section
            suspendResult {
                repository.search(
                    query = _state.value.submittedQuery.takeIf(String::isNotBlank),
                    countryCode = facet?.id.takeIf { section == RadioSection.COUNTRIES },
                    language = facet?.id.takeIf { section == RadioSection.LANGUAGES },
                    tag = facet?.id.takeIf { section == RadioSection.GENRES },
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
                    nextOffset = page.offset + page.limit,
                    error = false,
                    contentReady = true,
                )
            }.onFailure {
                _state.value = _state.value.copy(
                    loading = false,
                    loadingMore = false,
                    error = true,
                    contentReady = true,
                )
            }
        }
    }

    fun play(station: RadioStation, onStarted: () -> Unit = {}) {
        playJob?.cancel()
        playJob = viewModelScope.launch {
            _state.value = _state.value.copy(startingStationId = station.id, error = false)
            val current = _state.value
            val visibleStations = current.stations
            val facet = current.selectedFacet
            val source = RadioBrowseQueueSource(
                focusScope = current.focusScope(),
                query = current.submittedQuery.takeIf(String::isNotBlank),
                countryCode = facet?.id.takeIf { current.section == RadioSection.COUNTRIES },
                language = facet?.id.takeIf { current.section == RadioSection.LANGUAGES },
                tag = facet?.id.takeIf { current.section == RadioSection.GENRES },
                nextOffset = current.nextOffset,
                hasMore = current.hasMore,
            )
            playbackLauncher.play(station, visibleStations, source)
                .onSuccess {
                    refreshLocalState()
                    _state.value = _state.value.copy(startingStationId = null)
                    onStarted()
                }
                .onFailure {
                    _state.value = _state.value.copy(startingStationId = null, error = true)
                }
        }
    }

    companion object {
        private const val PAGE_SIZE = 48
        private const val FACET_LIMIT = 150
        private const val RADIO_VIEW_STATE_KEY = "radio"
    }
}

internal fun radioShouldLoadOnFocus(
    index: Int,
    size: Int,
    hasMore: Boolean,
    loading: Boolean,
    loadingMore: Boolean,
    threshold: Int = RADIO_GRID_CONTINUE_THRESHOLD,
): Boolean = index >= 0 &&
    size > 0 &&
    hasMore &&
    !loading &&
    !loadingMore &&
    index >= size - threshold.coerceAtLeast(1)

internal const val RADIO_GRID_CONTINUE_THRESHOLD = 10
internal const val RADIO_FOCUS_SETTLE_MS = 350L

