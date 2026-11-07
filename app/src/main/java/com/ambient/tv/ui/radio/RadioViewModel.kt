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

    /** A new browse UI session must never inherit a previous Activity's radio tile. */
    @Synchronized fun clear() = selections.clear()

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

/** Only complete, non-paged station datasets can expose a trustworthy A–Z jump index.
 * Countries, genres, languages and searches fetch their results page by page; keep the
 * normal scrollbar for their full lifetime, even after their last page arrives. */
internal fun radioStationAlphabetAllowed(
    section: RadioSection,
    selectedFacet: RadioFacet?,
    submittedQuery: String,
): Boolean = selectedFacet == null && submittedQuery.isBlank() &&
    section in setOf(RadioSection.POPULAR, RadioSection.FAVORITES, RadioSection.RECENT)

/** Facet overviews are API-limited; reaching that cap is not proof that the catalog ends. */
internal fun radioFacetAlphabetAllowed(facetCount: Int, cap: Int = RADIO_FACET_LIMIT): Boolean =
    facetCount > 5 && facetCount < cap

internal const val RADIO_FACET_LIMIT = 150

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
    private val stationIndex = RadioStationIndex()
    internal fun lastFocusedContent(scope: RadioFocusScope): String? = RadioFocusMemory.last(scope)

    /** Synchronize pages loaded by the Player and prefer its last station on returning. */
    fun consumePlayerReturn(): Boolean {
        val scope = _state.value.focusScope()
        val returned = playbackQueue.consumeReturn(scope) ?: return false
        val currentId = returned.currentId ?: return false
        RadioFocusMemory.save(scope, currentId)
        val before = _state.value
        val merged = radioAppendPagedStations(before.stations, returned.stations)
        val stations = if (radioStationAlphabetAllowed(before.section, before.selectedFacet, before.submittedQuery)) {
            radioAlphabeticalStations(merged, before.sortDescending)
        } else merged
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
            else refreshOnEntry()
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

    fun refresh() = refreshInternal(forceRefresh = true)

    private fun refreshOnEntry() = refreshInternal(forceRefresh = false)

    private fun refreshInternal(forceRefresh: Boolean) {
        resetFocusPaging()
        refreshLocalState()
        val current = _state.value
        // A remote page which has not answered yet must not briefly show an empty-state message.
        val remote = current.submittedQuery.isNotBlank() || current.selectedFacet != null ||
            current.section == RadioSection.POPULAR || current.section.isFacetSection
        if (remote && (if (current.isFacetOverview()) current.facets.isEmpty() else current.stations.isEmpty())) {
            _state.value = current.copy(loading = true, contentReady = false, error = false)
        }
        when {
            current.submittedQuery.isNotBlank() || current.selectedFacet != null ->
                loadStations(reset = true, forceRefresh = forceRefresh)
            current.section == RadioSection.POPULAR ->
                loadStations(reset = true, forceRefresh = forceRefresh)
            current.section.isFacetSection -> loadFacets(current.section, forceRefresh)
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
        // Claim the pending page synchronously so two scroll/focus callbacks cannot
        // enqueue duplicate requests for the same Radio Browser offset.
        _state.value = current.copy(loadingMore = true)
        loadStations(reset = false)
    }

    /** Also handles wheel, touch, scrollbars and remote paging without a new focused station. */
    fun onViewportNearEnd(expectedScope: RadioFocusScope, lastVisibleIndex: Int) {
        val current = _state.value
        if (current.focusScope() != expectedScope ||
            !radioShouldLoadOnFocus(lastVisibleIndex, current.stations.size,
                current.hasMore, current.loading, current.loadingMore)) return
        loadMore()
    }

    fun onStationFocused(stationId: String) {
        RadioFocusMemory.save(_state.value.focusScope(), stationId)
        focusedStationId = stationId
        val current = _state.value
        val index = if (current.hasMore && !current.loading && !current.loadingMore)
            stationIndex.indexOf(current.stations, stationId) else -1
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
            val latestIndex = stationIndex.indexOf(latest.stations, latestFocusedId)
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

    private fun loadFacets(section: RadioSection, forceRefresh: Boolean = false) {
        if (!section.isFacetSection) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val previousFacets = _state.value.facets
            _state.value = _state.value.copy(
                loading = true, loadingMore = false, stations = emptyList(),
                facets = previousFacets, selectedFacet = null, hasMore = false,
                error = false, contentReady = previousFacets.isNotEmpty(),
            )
            // Read an entire cached index first: a cold start can paint categories offline.
            val cached = suspendResult {
                when (section) {
                    RadioSection.GENRES -> repository.cachedTags(RADIO_FACET_LIMIT)
                    RadioSection.COUNTRIES -> repository.cachedCountries(RADIO_FACET_LIMIT)
                    RadioSection.LANGUAGES -> repository.cachedLanguages(RADIO_FACET_LIMIT)
                    else -> null
                }
            }.getOrNull()
            if (cached != null && (cached.value.isNotEmpty() || cached.fresh) &&
                _state.value.section == section && _state.value.selectedFacet == null
            ) {
                _state.value = _state.value.copy(
                    loading = false, facets = cached.value, error = false, contentReady = true,
                )
            }
            if (cached?.fresh == true && !forceRefresh) return@launch
            suspendResult {
                when (section) {
                    RadioSection.GENRES -> repository.tags(RADIO_FACET_LIMIT, forceRefresh)
                    RadioSection.COUNTRIES -> repository.countries(RADIO_FACET_LIMIT, forceRefresh)
                    RadioSection.LANGUAGES -> repository.languages(RADIO_FACET_LIMIT, forceRefresh)
                    else -> emptyList()
                }
            }.onSuccess { facets ->
                if (_state.value.section == section && _state.value.selectedFacet == null) {
                    _state.value = _state.value.copy(
                        loading = false, facets = facets, error = false, contentReady = true,
                    )
                }
            }.onFailure { error ->
                timber.log.Timber.tag("RadioBrowser").w(error, "Failed to load %s categories", section.key)
                if (_state.value.section == section && _state.value.selectedFacet == null) {
                    _state.value = _state.value.copy(
                        loading = false, facets = _state.value.facets.ifEmpty { previousFacets },
                        error = true, contentReady = true,
                    )
                }
            }
        }
    }

    private fun loadStations(reset: Boolean, forceRefresh: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val before = _state.value
            val offset = if (reset) 0 else before.nextOffset
            val facet = before.selectedFacet
            val section = before.section
            val query = before.submittedQuery.takeIf(String::isNotBlank)
            val descending = before.sortDescending
            val popular = section == RadioSection.POPULAR && facet == null && query == null
            val pageLimit = if (popular) POPULAR_LIMIT else PAGE_SIZE
            _state.value = before.copy(
                loading = reset && before.stations.isEmpty(),
                loadingMore = !reset || (reset && before.stations.isNotEmpty()),
                stations = before.stations, error = false,
                contentReady = before.stations.isNotEmpty() || before.contentReady,
            )

            fun publish(page: RadioSearchPage, revalidating: Boolean = false) {
                // Keep already loaded pages on return when page zero still has exactly the same
                // UUID ordering. Do not discard scroll position or the currently focused card.
                val retainTail = reset && before.stations.size > page.items.size &&
                    page.items.size == PAGE_SIZE &&
                    before.stations.take(PAGE_SIZE).map(RadioStation::id) == page.items.map(RadioStation::id)
                val merged = when {
                    retainTail -> page.items + before.stations.drop(PAGE_SIZE)
                    reset -> page.items
                    else -> radioAppendPagedStations(before.stations, page.items)
                }
                _state.value = _state.value.copy(
                    loading = false, loadingMore = revalidating,
                    stations = if (popular) radioAlphabeticalStations(merged, descending) else merged,
                    hasMore = if (retainTail) before.hasMore
                        else radioHasMoreStations(page.hasMore, page.items.size,
                            page.offset + page.limit, facet?.stationCount),
                    nextOffset = if (retainTail) before.nextOffset else page.offset + page.limit,
                    error = false, contentReady = true,
                )
            }

            val cached = suspendResult {
                if (popular) repository.cachedPopular(POPULAR_LIMIT)?.let {
                    RadioSearchPage(it.value, 0, POPULAR_LIMIT, false) to it.fresh
                } else repository.cachedSearch(
                    query = query,
                    countryCode = facet?.id.takeIf { section == RadioSection.COUNTRIES },
                    language = facet?.id.takeIf { section == RadioSection.LANGUAGES },
                    tag = facet?.id.takeIf { section == RadioSection.GENRES },
                    offset = offset, limit = pageLimit, order = "name", reverse = descending,
                )?.let { it.value to it.fresh }
            }.getOrNull()
            if (cached != null && (cached.first.items.isNotEmpty() || cached.second)) {
                // A reused ViewModel's populated page is already better than resetting it
                // to the cached first page. Keep its complete cursor until refresh finishes.
                if (reset && before.stations.isNotEmpty()) {
                    _state.value = _state.value.copy(
                        loading = false, loadingMore = !cached.second || forceRefresh,
                        contentReady = true,
                    )
                } else publish(cached.first, revalidating = !cached.second || forceRefresh)
            }
            if (cached?.second == true && !forceRefresh) {
                if (reset && before.stations.isNotEmpty()) {
                    _state.value = _state.value.copy(loading = false, loadingMore = false)
                }
                return@launch
            }

            suspendResult {
                if (popular) {
                    val stations = repository.popular(POPULAR_LIMIT, forceRefresh)
                    RadioSearchPage(stations, offset = 0, limit = POPULAR_LIMIT, hasMore = false)
                } else repository.search(
                    query = query,
                    countryCode = facet?.id.takeIf { section == RadioSection.COUNTRIES },
                    language = facet?.id.takeIf { section == RadioSection.LANGUAGES },
                    tag = facet?.id.takeIf { section == RadioSection.GENRES },
                    offset = offset, limit = pageLimit, order = "name", reverse = descending,
                    forceRefresh = forceRefresh,
                )
            }.onSuccess { page ->
                publish(page)
            }.onFailure { error ->
                timber.log.Timber.tag("RadioBrowser").w(error, "Failed to load stations for %s", section.key)
                _state.value = _state.value.copy(
                    loading = false, loadingMore = false,
                    // Never retry the same failed page on every focus movement.
                    hasMore = if (_state.value.stations.isNotEmpty()) _state.value.hasMore else false,
                    error = true, contentReady = true,
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
        private const val RADIO_VIEW_STATE_KEY = "radio"
    }
}

/** A mirror can return an underfilled filtered page despite a larger facet count.
 * Probe the next offset once instead of incorrectly declaring the entire country/genre done. */
internal fun radioHasMoreStations(
    apiHasMore: Boolean,
    mappedCount: Int,
    nextOffset: Int,
    facetStationCount: Int?,
): Boolean = apiHasMore || (mappedCount > 0 && facetStationCount != null &&
    nextOffset < facetStationCount)

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

