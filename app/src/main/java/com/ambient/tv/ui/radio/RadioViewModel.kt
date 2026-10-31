package com.ambient.tv.ui.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.radio.RadioBrowserRepository
import com.ambient.tv.data.radio.RadioSearchPage
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

/** Exactly the categories enabled for the menu, in the same canonical order on TV and handheld. */
internal fun radioVisibleSections(enabledKeys: List<String>): List<RadioSection> =
    RadioSection.entries.filter { it.key in enabledKeys }.ifEmpty { listOf(RadioSection.POPULAR) }

internal val RadioSection.isFacetSection: Boolean
    get() = this == RadioSection.GENRES || this == RadioSection.COUNTRIES || this == RadioSection.LANGUAGES

/** Category overviews are always text lists; the saved user choice still controls station pages. */
internal fun RadioUiState.isFacetOverview(): Boolean =
    section.isFacetSection && selectedFacet == null && submittedQuery.isBlank()

internal fun RadioUiState.effectiveViewMode(): RadioViewMode =
    if (isFacetOverview()) RadioViewMode.LIST else viewMode

/** An empty response is not proven until the first request for this exact page settles. */
internal fun RadioUiState.awaitingInitialContent(): Boolean {
    // A selected country/genre/language keeps the parent facets for Back. Those
    // cached facets do NOT constitute a loaded station result for this child page.
    val requestedItemsEmpty = if (isFacetOverview()) facets.isEmpty() else stations.isEmpty()
    return requestedItemsEmpty && (loading || !contentReady)
}

/** A destination's old empty/error state is never the new route's first result. */
internal fun RadioUiState.awaitingRequestedPage(
    requestedSection: RadioSection,
    entryPending: Boolean,
): Boolean = entryPending || section != requestedSection || awaitingInitialContent()

/** Use category-list skeletons for a requested facet overview on the very first frame. */
internal fun RadioUiState.requestedLoadingLayout(requestedSection: RadioSection): RadioLoadingLayout =
    if (section == requestedSection) loadingLayout()
    else copy(section = requestedSection, selectedFacet = null, submittedQuery = "").loadingLayout()

/** Preserve live content on back; only a no-result remote page needs first-frame shielding. */
internal fun RadioUiState.needsRemoteRefreshOnReturn(): Boolean =
    (if (isFacetOverview()) facets.isEmpty() else stations.isEmpty()) &&
        (submittedQuery.isNotBlank() || selectedFacet != null ||
            section == RadioSection.POPULAR || section.isFacetSection)

/** The message belongs to the *requested* result, not the previous page or a loading snapshot. */
internal fun RadioUiState.emptyMessageRes(): Int = when {
    submittedQuery.isNotBlank() -> com.ambient.tv.R.string.radio_no_search_results
    isFacetOverview() -> when (section) {
        RadioSection.GENRES -> com.ambient.tv.R.string.radio_no_genres
        RadioSection.COUNTRIES -> com.ambient.tv.R.string.radio_no_countries
        RadioSection.LANGUAGES -> com.ambient.tv.R.string.radio_no_languages
        else -> com.ambient.tv.R.string.radio_no_stations
    }
    section == RadioSection.FAVORITES -> com.ambient.tv.R.string.radio_no_favorites
    section == RadioSection.RECENT -> com.ambient.tv.R.string.radio_no_recent
    else -> com.ambient.tv.R.string.radio_no_stations
}

data class RadioUiState(
    val section: RadioSection = RadioSection.POPULAR,
    val visibleSections: List<RadioSection> = RadioSection.entries,
    /** First composition is pending even before the enter() effect has launched the API request. */
    val loading: Boolean = true,
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
    val sortDescending: Boolean = false,
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
    private val _state = MutableStateFlow(RadioUiState(
        visibleSections = radioVisibleSections(preferences.radioSubmenuKeys),
        sortDescending = preferences.sectionSortOrder(RADIO_VIEW_STATE_KEY) == "DESCENDING",
    ))
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
        val stations = radioAlphabeticalStations(before.stations + returned.stations.filter { known.add(it.id) }, before.sortDescending)
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
                val visibleSections = radioVisibleSections(preferences.radioSubmenuKeys)
                if (_state.value.visibleSections != visibleSections) {
                    _state.value = _state.value.copy(visibleSections = visibleSections)
                }
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
            // Reopening an existing NavHost destination must revalidate its empty/error
            // result instead of returning without a request. Keep the active facet/search.
            if (initialSection != null && initialSection != _state.value.section) selectSection(initialSection)
            else refresh()
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
            sortDescending = preferences.sectionSortOrder(RADIO_VIEW_STATE_KEY) == "DESCENDING",
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
        // The request is scheduled in a coroutine. Mark a confirmed-empty remote page
        // pending synchronously so Reload cannot paint another stale empty frame.
        val remote = current.submittedQuery.isNotBlank() || current.selectedFacet != null ||
            current.section == RadioSection.POPULAR || current.section.isFacetSection
        if (remote && (if (current.isFacetOverview()) current.facets.isEmpty() else current.stations.isEmpty())) {
            _state.value = current.copy(loading = true, contentReady = false, error = false)
        }
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
            loading = section == RadioSection.POPULAR || section.isFacetSection,
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
            loading = true,
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
            loading = true,
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
        _state.value = _state.value.copy(
            submittedQuery = query,
            stations = emptyList(),
            facets = emptyList(),
            loading = true,
            contentReady = false,
        )
        loadStations(reset = true)
    }

    fun clearSearch() {
        resetFocusPaging()
        val current = _state.value
        _state.value = current.copy(
            query = "",
            submittedQuery = "",
            stations = emptyList(),
            facets = emptyList(),
            loading = true,
            contentReady = false,
        )
        when {
            current.selectedFacet != null -> loadStations(reset = true)
            current.section == RadioSection.POPULAR -> loadStations(reset = true)
            current.section.isFacetSection -> loadFacets(current.section)
            else -> showLocalSection(current.section)
        }
    }

    fun toggleFavorite(station: RadioStation) {
        val before = _state.value
        val favorite = station.id !in before.favoriteIds
        val removingFromFavorites = !favorite && before.section == RadioSection.FAVORITES &&
            before.submittedQuery.isBlank() && before.selectedFacet == null
        if (removingFromFavorites) {
            radioFocusAfterRemovingFavorite(before.stations, station.id)?.let { nextId ->
                RadioFocusMemory.save(before.focusScope(), nextId)
            }
        }
        preferences.setRadioFavorite(station, favorite)
        refreshLocalState()
        if (before.section == RadioSection.FAVORITES && before.submittedQuery.isBlank()) {
            showLocalSection(RadioSection.FAVORITES)
            if (removingFromFavorites) _state.value = _state.value.copy(returnFocusRevision = before.returnFocusRevision + 1)
        }
    }

    fun selectViewMode(mode: RadioViewMode) {
        preferences.setSectionViewMode(RADIO_VIEW_STATE_KEY, mode.name)
        _state.value = _state.value.copy(viewMode = mode)
    }

    /** Persist sort direction for all Radio Browser indexes and remote station pages. */
    fun setSortDescending(descending: Boolean) {
        val current = _state.value
        if (current.sortDescending == descending) return
        preferences.setSectionSortOrder(RADIO_VIEW_STATE_KEY, if (descending) "DESCENDING" else "ASCENDING")
        // Locally available sets (including the full Popular top-100) can reverse instantly.
        val local = current.isFacetOverview() ||
            (current.submittedQuery.isBlank() && current.selectedFacet == null &&
                (current.section == RadioSection.FAVORITES || current.section == RadioSection.RECENT ||
                    (current.section == RadioSection.POPULAR && current.contentReady && !current.loading)))
        if (local) {
            _state.value = current.copy(
                sortDescending = descending,
                stations = radioAlphabeticalStations(current.stations, descending),
            )
        } else {
            // Remote paging must restart at offset zero in the other direction. Mixing
            // previously loaded A–Z and fresh Z–A pages would invalidate every jump.
            loadJob?.cancel()
            resetFocusPaging()
            _state.value = current.copy(sortDescending = descending, stations = emptyList(),
                loading = true, contentReady = false, hasMore = false, nextOffset = 0, error = false)
            loadStations(reset = true)
        }
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
            stations = radioAlphabeticalStations(stations, _state.value.sortDescending),
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
            // Retain the last successful categories while refreshing so the list and focus
            // do not collapse into generic placeholders if the remote catalog is slow.
            val previousFacets = _state.value.facets
            _state.value = _state.value.copy(
                loading = true,
                loadingMore = false,
                stations = emptyList(),
                facets = previousFacets,
                selectedFacet = null,
                hasMore = false,
                error = false,
                contentReady = previousFacets.isNotEmpty(),
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
            }.onFailure { error ->
                timber.log.Timber.tag("RadioBrowser").w(error, "Failed to load %s categories", section.key)
                if (_state.value.section == section && _state.value.selectedFacet == null) {
                    _state.value = _state.value.copy(
                        loading = false,
                        facets = previousFacets,
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
                // A reload of an already visible page must not wipe its cards or focus.
                // New sections/searches clear the old page before calling this method.
                stations = before.stations,
                nextOffset = if (reset) 0 else before.nextOffset,
                error = false,
                contentReady = before.stations.isNotEmpty() || before.contentReady,
            )
            val facet = _state.value.selectedFacet
            val section = _state.value.section
            val query = _state.value.submittedQuery.takeIf(String::isNotBlank)
            val descending = _state.value.sortDescending
            suspendResult {
                if (section == RadioSection.POPULAR && facet == null && query == null) {
                    // 'Popular' remains the actual top-clicked catalog, not the entire
                    // station database. Fetch its complete bounded set before indexing.
                    val popular = repository.popular(POPULAR_LIMIT)
                    RadioSearchPage(popular, offset = 0, limit = POPULAR_LIMIT, hasMore = false)
                } else {
                    repository.search(
                        query = query,
                        countryCode = facet?.id.takeIf { section == RadioSection.COUNTRIES },
                        language = facet?.id.takeIf { section == RadioSection.LANGUAGES },
                        tag = facet?.id.takeIf { section == RadioSection.GENRES },
                        offset = offset,
                        limit = PAGE_SIZE,
                        order = "name",
                        reverse = descending,
                    )
                }
            }.onSuccess { page ->
                val existing = if (reset) emptyList() else _state.value.stations
                _state.value = _state.value.copy(
                    loading = false,
                    loadingMore = false,
                    stations = radioAlphabeticalStations((existing + page.items).distinctBy(RadioStation::id), descending),
                    hasMore = page.hasMore,
                    nextOffset = page.offset + page.limit,
                    error = false,
                    contentReady = true,
                )
            }.onFailure { error ->
                timber.log.Timber.tag("RadioBrowser").w(error, "Failed to load stations for %s", section.key)
                _state.value = _state.value.copy(
                    loading = false,
                    loadingMore = false,
                    // Never auto-retry the same failed page on every focus movement.
                    // Explicit refresh restores paging after the catalog returns.
                    hasMore = false,
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
                sortDescending = current.sortDescending,
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
        private const val POPULAR_LIMIT = 100
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

