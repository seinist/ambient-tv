package com.ambient.tv.ui.section

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaTag
import com.ambient.tv.domain.repository.MediaRepository
import com.ambient.tv.ui.common.PlexRefreshBus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.ambient.tv.ui.common.BrowseFocusBuffer
import com.ambient.tv.ui.common.BrowseFocusSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SectionPage { RECOMMENDATIONS, LIBRARY, COLLECTIONS }
enum class SectionViewMode { POSTER, LANDSCAPE, LIST, INFO_LIST, WALL, CINEMATIC, ROWS, GRID, POSTER_LIST, POSTER_INFO_LIST, POSTER_WALL, POSTER_CINEMATIC }

data class RecommendationRow(val id: String, val title: String, val items: List<MediaItem>)
data class CollectionEntry(val id: String, val title: String, val itemCount: Int, val representative: MediaItem, val newestAddedAt: Long?, val lastViewedAt: Long? = null, val plays: Int = 0)

sealed interface SectionUiState {
    data object Loading : SectionUiState
    data class Loaded(
        val title: String,
        val page: SectionPage,
        val items: List<MediaItem>,
        val recommendationRows: List<RecommendationRow>,
        val collections: List<CollectionEntry>,
        val sort: SectionSort,
        val sortOrder: SectionSortOrder,
        val filter: SectionFilter,
        val qualityFilter: SectionQualityFilter,
        val genreFilter: String? = null,
        val decadeFilter: Int? = null,
        val contentRatingFilter: String? = null,
        val filterFacets: LibraryFilterFacets = LibraryFilterFacets(),
        val viewMode: SectionViewMode,
        val focusedRatingKey: String? = null,
        val focusedRecommendationRowId: String? = null,
        val focusedCollectionId: String? = null,
        val collectionsLoading: Boolean = false,
        val availableLetters: List<Char> = emptyList(),
        val selectedLetter: Char? = null,
        val gridColumns: Int = 8,
        val listRows: Int = 7,
    ) : SectionUiState {
        val libraryFilters: LibraryFilters
            get() = LibraryFilters(filter, qualityFilter, genreFilter, decadeFilter, contentRatingFilter)
    }
    data class Error(val message: String) : SectionUiState
}

@HiltViewModel
class SectionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val mediaRepository: MediaRepository,
    val metadataPrefetcher: com.ambient.tv.data.browse.MetadataPrefetcher,
    val availability: com.ambient.tv.data.browse.SourceAvailability,
    private val preferences: AppPreferences,
    private val plexRefreshBus: PlexRefreshBus,
) : ViewModel() {

    val sectionKey: String = checkNotNull(savedStateHandle["sectionKey"])
    val autoRefreshIntervalSeconds: Int get() = preferences.plexAutoRefreshIntervalSeconds
    val refreshGeneration = plexRefreshBus.generation
    val loadingViewMode: SectionViewMode get() = viewMode()
    val loadingPage: SectionPage get() = page
    val loadingGridColumns: Int get() = preferences.libraryGridColumns
    val loadingListRows: Int get() = preferences.libraryListRows
    private var sourceItems: List<MediaItem> = mediaRepository.cachedSectionItems(sectionKey).orEmpty()
    private var onDeck: List<MediaItem> = mediaRepository.cachedProviderOnDeck(sectionKey).orEmpty()
    private var page = preferences.sectionPage(sectionKey)?.let { runCatching { SectionPage.valueOf(it) }.getOrNull() } ?: SectionPage.RECOMMENDATIONS
    private var selectedLetter: Char? = null
    // A title may occur in several recommendation rows. The item key alone cannot identify
    // which of those rows owns the current D-pad focus.
    private var focusedRecommendationRowId: String? = null

    private val _uiState = MutableStateFlow<SectionUiState>(SectionUiState.Loading)
    val uiState: StateFlow<SectionUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var rebuildJob: Job? = null
    private val focusBuffer = BrowseFocusBuffer()
    private var focusPersistenceJob: Job? = null
    private var collectionJob: Job? = null
    private var collectionsLoading = false
    /** Reloaded items whose shown collection tags are carried over until one enrichment pass. */
    private var staleCollectionSource: List<MediaItem>? = null
    private var rebuildVersion = 0

    fun retryOffline() { if (availability.hasNetwork() && availability.state(com.ambient.tv.data.browse.mediaProvider(sectionKey)) == com.ambient.tv.data.browse.SourceHealth.OFFLINE && loadJob?.isActive != true) load() }

    init {
        if (sourceItems.isNotEmpty()) rebuild()
    }

    /** Entry already expired this source under the shared mutation guard. */
    fun refreshAfterEntry() = load(force = true, invalidateCache = false)

    fun load(force: Boolean = false, invalidateCache: Boolean = force) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mediaRepository.restoreCache()
            if (force) {
                collectionJob?.cancel()
                collectionJob = null
                collectionsLoading = false
            }
            // Invalidate before starting sibling reads; doing it afterwards cancels fresh work.
            if (invalidateCache) mediaRepository.invalidateSectionCache(sectionKey)
            mediaRepository.cachedProviderOnDeck(sectionKey)?.let { onDeck = it }
            // Recovered libraries may have been renamed; do not race the sidebar's independent load.
            launch {
                mediaRepository.getProviderLibrarySections(sectionKey).onSuccess {
                    if (sourceItems.isNotEmpty() || _uiState.value is SectionUiState.Loaded) rebuild()
                }
            }
            // Independent of library pagination: a slow feed must not hold back the library.
            launch {
                mediaRepository.getProviderOnDeck(sectionKey).onSuccess {
                    onDeck = it
                    if (sourceItems.isNotEmpty()) rebuild()
                }
                // Failures retain the cached/visible feed; successful empty answers clear it.
            }
            if (sourceItems.isEmpty()) {
                sourceItems = mediaRepository.cachedSectionItems(sectionKey).orEmpty()
                if (sourceItems.isNotEmpty()) rebuild()
            }
            if (_uiState.value !is SectionUiState.Loaded) _uiState.value = SectionUiState.Loading
            val showPagesWhileLoading = sourceItems.isEmpty()
            val progressiveItems = mutableListOf<MediaItem>()
            var publishedItemCount = 0
            mediaRepository.getSectionItems(sectionKey) { pageItems ->
                if (showPagesWhileLoading) {
                    progressiveItems += pageItems
                    // Publish the first page immediately, then update in coarse batches so a
                    // very large library does not trigger a complete sort on every 100 items.
                    if (publishedItemCount == 0 || progressiveItems.size - publishedItemCount >= PROGRESSIVE_BATCH_SIZE) {
                        publishedItemCount = progressiveItems.size
                        val snapshot = progressiveItems.toList()
                        withContext(Dispatchers.Main.immediate) {
                            sourceItems = snapshot
                            rebuild()
                        }
                    }
                }
            }.fold(
                onSuccess = { items ->
                    // A reload returns Jellyfin/Emby items without collection tags. Keep the
                    // known ones so the collections page is updated in place, not blanked.
                    val carryOver = items.none { it.collections.isNotEmpty() } && sourceItems.any { it.collections.isNotEmpty() }
                    staleCollectionSource = items.takeIf { carryOver }
                    val previousItems = sourceItems
                    sourceItems = if (carryOver) {
                        val known = sourceItems.associate { it.ratingKey to it.collections }
                        items.map { item -> known[item.ratingKey]?.takeIf { it.isNotEmpty() }?.let { item.copy(collections = it) } ?: item }
                    } else items
                    sourceItems = com.ambient.tv.data.browse.reconcileComplete(previousItems, sourceItems) { it.ratingKey }
                    val focused = focusBuffer.get(stateKey()) ?: preferences.sectionFocus(stateKey())?.let { com.ambient.tv.ui.common.BrowseFocusSnapshot(it, preferences.sectionFocusRow(stateKey())) }
                    val continueItems = sectionContinueWatching(onDeck, sourceItems)
                    if (focused != null && (sourceItems + continueItems).none { focused.itemKey in it.allRatingKeys }) {
                        val next = com.ambient.tv.data.browse.survivingFocus(previousItems.map { it.ratingKey }, sourceItems.map { it.ratingKey }, focused.itemKey)
                        if (next != null) focusBuffer.update(stateKey(), next, focused.rowId)
                    }
                    rebuild()
                    if (page == SectionPage.COLLECTIONS) ensureCollectionsLoaded()
                },
                onFailure = { error ->
                    if (_uiState.value !is SectionUiState.Loaded) {
                        _uiState.value = SectionUiState.Error(error.message ?: "")
                    }
                },
            )
        }
    }

    private fun ensureCollectionsLoaded() {
        val stale = staleCollectionSource
        if (sourceItems.isEmpty() || (stale == null && sourceItems.any { it.collections.isNotEmpty() }) || collectionJob?.isActive == true) return
        collectionJob = viewModelScope.launch {
            collectionsLoading = stale == null
            if (collectionsLoading) rebuild()
            mediaRepository.enrichCollections(stale ?: sourceItems).onSuccess { enriched ->
                staleCollectionSource = null
                if (enriched !== sourceItems) {
                    sourceItems = enriched
                }
            }
            collectionsLoading = false
            rebuild()
        }
    }

    fun selectPage(newPage: SectionPage) {
        captureFocusForReturn()
        page = newPage
        selectedLetter = null
        preferences.setSectionPage(sectionKey, page.name)
        rebuild()
        if (newPage == SectionPage.COLLECTIONS) ensureCollectionsLoaded()
    }

    private fun stateKey(): String = "$sectionKey:${page.name.lowercase()}"

    private fun sort(): SectionSort = preferences.sectionSort(stateKey())?.let { runCatching { SectionSort.valueOf(it) }.getOrNull() } ?: SectionSort.TITLE
    private fun sortOrder(): SectionSortOrder = preferences.sectionSortOrder(stateKey())
        ?.let { runCatching { SectionSortOrder.valueOf(it) }.getOrNull() }
        ?: defaultSortOrder(sort())
    private fun filter(): SectionFilter = preferences.sectionFilter(stateKey())?.let { runCatching { SectionFilter.valueOf(it) }.getOrNull() } ?: SectionFilter.ALL
    private fun qualityFilter(): SectionQualityFilter = preferences.sectionQualityFilter(stateKey())
        ?.let { runCatching { SectionQualityFilter.valueOf(it) }.getOrNull() }
        ?: SectionQualityFilter.ALL
    private fun genreFilter(): String? = preferences.sectionGenreFilter(stateKey())?.takeIf { it.isNotBlank() }
    private fun decadeFilter(): Int? = preferences.sectionDecadeFilter(stateKey())?.toIntOrNull()
    private fun contentRatingFilter(): String? = preferences.sectionContentRatingFilter(stateKey())?.takeIf { it.isNotBlank() }
    private fun libraryFilters() = LibraryFilters(filter(), qualityFilter(), genreFilter(), decadeFilter(), contentRatingFilter())
    private fun viewMode(): SectionViewMode {
        val stored = preferences.sectionViewMode(stateKey())?.let { runCatching { SectionViewMode.valueOf(it) }.getOrNull() }
        if (page == SectionPage.RECOMMENDATIONS) return recommendationViewMode(stored)
        return when (stored) {
            SectionViewMode.ROWS -> SectionViewMode.LANDSCAPE
            SectionViewMode.GRID -> SectionViewMode.POSTER
            null -> SectionViewMode.POSTER
            else -> stored
        }
    }


    fun setSort(value: SectionSort) {
        selectedLetter = null
        preferences.setSectionSort(stateKey(), value.name)
        preferences.setSectionSortOrder(stateKey(), defaultSortOrder(value).name)
        rebuild()
    }

    fun setSortOrder(value: SectionSortOrder) {
        preferences.setSectionSortOrder(stateKey(), value.name)
        rebuild()
    }

    fun setFilter(value: SectionFilter) {
        preferences.setSectionFilter(stateKey(), value.name)
        rebuild()
    }

    fun setFilterGroup(groupId: String, key: String) {
        val all = key.equals("ALL", ignoreCase = true) || key.isBlank()
        when (groupId) {
            FILTER_WATCH -> preferences.setSectionFilter(stateKey(), runCatching { SectionFilter.valueOf(key) }.getOrDefault(SectionFilter.ALL).name)
            FILTER_QUALITY -> preferences.setSectionQualityFilter(stateKey(), runCatching { SectionQualityFilter.valueOf(key) }.getOrDefault(SectionQualityFilter.ALL).name)
            FILTER_GENRE -> preferences.setSectionGenreFilter(stateKey(), key.takeUnless { all })
            FILTER_DECADE -> preferences.setSectionDecadeFilter(stateKey(), key.takeUnless { all })
            FILTER_CONTENT_RATING -> preferences.setSectionContentRatingFilter(stateKey(), key.takeUnless { all })
        }
        rebuild()
    }

    fun resetFilters() {
        val key = stateKey()
        preferences.setSectionFilter(key, SectionFilter.ALL.name)
        preferences.setSectionQualityFilter(key, SectionQualityFilter.ALL.name)
        preferences.setSectionGenreFilter(key, null)
        preferences.setSectionDecadeFilter(key, null)
        preferences.setSectionContentRatingFilter(key, null)
        rebuild()
    }

    fun setGridColumns(value: Int) {
        preferences.libraryGridColumns = value
        rebuild()
    }

    fun setListRows(value: Int) {
        preferences.libraryListRows = value
        rebuild()
    }

    fun setViewMode(value: SectionViewMode) {
        val normalized = if (page == SectionPage.RECOMMENDATIONS) recommendationViewMode(value) else when (value) {
            SectionViewMode.ROWS -> SectionViewMode.LANDSCAPE
            SectionViewMode.GRID -> SectionViewMode.POSTER
            else -> value
        }
        preferences.setSectionViewMode(stateKey(), normalized.name)
        rebuild()
    }

    fun cycleSort() {
        val values = SectionSort.entries
        val next = values[(values.indexOf(sort()).coerceAtLeast(0) + 1) % values.size]
        setSort(next)
    }

    fun toggleViewMode() {
        val modes = SELECTABLE_VIEW_MODES
        val current = viewMode()
        val next = modes[(modes.indexOf(current).coerceAtLeast(0) + 1) % modes.size]
        preferences.setSectionViewMode(stateKey(), next.name)
        rebuild()
    }

    fun selectLetter(letter: Char?) {
        selectedLetter = letter
        rebuild()
    }

    fun onItemFocused(item: MediaItem) {
        metadataPrefetcher.detail(item.ratingKey)
        bufferFocus(item.ratingKey, null)
        focusedRecommendationRowId = null
        val current = _uiState.value as? SectionUiState.Loaded ?: return
        if (current.focusedRatingKey != item.ratingKey) _uiState.value = current.copy(focusedRatingKey = item.ratingKey)
    }

    fun onRecommendationItemFocused(rowId: String, item: MediaItem) {
        metadataPrefetcher.detail(item.ratingKey)
        focusedRecommendationRowId = rowId
        // Only the buffer: recommendation pages read the focused key once as a restore target, and
        // publishing it to uiState recomposed the whole screen on every D-pad step (~50 ms on SHIELD).
        // rebuild() picks the buffered focus up again.
        bufferFocus(item.ratingKey, rowId)
    }

    fun onCollectionFocused(id: String) {
        bufferFocus("collection:$id", null)
        focusedRecommendationRowId = null
        val current = _uiState.value as? SectionUiState.Loaded ?: return
        if (current.focusedCollectionId != id) _uiState.value = current.copy(focusedCollectionId = id)
    }

    private fun bufferFocus(itemKey: String, rowId: String?) {
        focusBuffer.update(stateKey(), itemKey, rowId)
        focusPersistenceJob?.cancel()
        focusPersistenceJob = viewModelScope.launch {
            delay(600)
            focusPersistenceJob = null
            flushFocusWrites()
        }
    }

    /** Navigation/disposal flushes the newest focus even before the debounce expires. */
    fun captureFocusForReturn() {
        focusPersistenceJob?.cancel()
        focusPersistenceJob = null
        flushFocusWrites()
    }

    private fun flushFocusWrites() {
        focusBuffer.drain().forEach { (key, snapshot) ->
            preferences.setSectionFocusSnapshot(key, snapshot.itemKey, snapshot.rowId)
        }
    }

    override fun onCleared() {
        captureFocusForReturn()
        super.onCleared()
    }

    private fun buildLoaded(): SectionUiState.Loaded {
        val title = mediaRepository.cachedLibrarySection(sectionKey)?.title ?: context.getString(R.string.library_default_title)
        val sort = sort()
        val order = sortOrder()
        val filters = libraryFilters()
        val facets = libraryFilterFacets(sourceItems)
        val view = viewMode()
        val bufferedFocus = focusBuffer.get(stateKey())
        val focused = bufferedFocus?.itemKey ?: preferences.sectionFocus(stateKey())
        val genre = filters.genre?.takeIf { name -> facets.genres.any { it.equals(name, ignoreCase = true) } }
        val decade = filters.decade?.takeIf { it in facets.decades }
        val contentRating = filters.contentRating?.takeIf { name -> facets.contentRatings.any { it.equals(name, ignoreCase = true) } }
        val quality = filters.quality.takeIf { it == SectionQualityFilter.ALL || it in facets.qualities } ?: SectionQualityFilter.ALL
        val applied = filters.copy(quality = quality, genre = genre, decade = decade, contentRating = contentRating)

        val sorted = applyLibraryViewOptions(sourceItems, sort, order, applied)
        val letters = sorted.mapNotNull { libraryIndexLetter(it.title) }.distinct().sorted()
        val rows = recommendationRows(sourceItems)
        val focusedRecommendationRow = (if (bufferedFocus != null) bufferedFocus.rowId else focusedRecommendationRowId ?: preferences.sectionFocusRow(stateKey()))
            ?.takeIf { rowId -> focused != null && rows.any { row -> row.id == rowId && row.items.any { focused in it.allRatingKeys } } }
        val collections = collectionEntries(sourceItems, sort, order)
        return SectionUiState.Loaded(
            title = title,
            page = page,
            items = sorted,
            recommendationRows = rows,
            collections = collections,
            sort = sort,
            sortOrder = order,
            filter = applied.watch,
            qualityFilter = applied.quality,
            genreFilter = applied.genre,
            decadeFilter = applied.decade,
            contentRatingFilter = applied.contentRating,
            filterFacets = facets,
            viewMode = view,
            focusedRatingKey = focused?.takeUnless { it.startsWith("collection:") },
            focusedRecommendationRowId = focusedRecommendationRow,
            focusedCollectionId = focused?.removePrefix("collection:")?.takeIf { focused.startsWith("collection:") },
            collectionsLoading = collectionsLoading,
            availableLetters = letters,
            selectedLetter = selectedLetter,
            gridColumns = preferences.libraryGridColumns,
            listRows = preferences.libraryListRows,
        )
    }

    /** Sorting and collection grouping can touch tens of thousands of entries; keep it off Compose's main thread. */
    private fun rebuild() {
        val version = ++rebuildVersion
        rebuildJob?.cancel()
        rebuildJob = viewModelScope.launch(Dispatchers.Default) {
            val loaded = buildLoaded()
            withContext(Dispatchers.Main.immediate) {
                if (version == rebuildVersion) {
                    // Sorting may finish after another key press. Publish the latest target,
                    // including a not-yet-persisted one, instead of an older worker snapshot.
                    _uiState.value = loaded.withLatestBrowseFocus(focusBuffer.get(stateKey()))
                }
            }
        }
    }



    private fun recommendationRows(items: List<MediaItem>): List<RecommendationRow> {
        val inProgress = sectionContinueWatching(onDeck, items).take(24)
        val recentlyAdded = items.sortedByDescending { it.addedAt ?: 0L }.take(24)
        val recentlyReleased = items.filter { !it.originallyAvailableAt.isNullOrBlank() }
            .sortedByDescending { it.originallyAvailableAt }.take(24)
        val unwatched = items.filterNot { it.isWatched }.sortedByDescending { it.rating ?: it.audienceRating ?: it.criticRating?.div(10.0) ?: 0.0 }.take(24)
        return buildList {
            if (inProgress.isNotEmpty()) add(RecommendationRow("continue", context.getString(R.string.home_continue_watching), inProgress))
            if (recentlyAdded.isNotEmpty()) add(RecommendationRow("recent-added", context.getString(R.string.home_recently_added), recentlyAdded))
            if (recentlyReleased.isNotEmpty()) add(RecommendationRow("recent-released", context.getString(R.string.home_recently_released), recentlyReleased))
            if (unwatched.isNotEmpty()) add(RecommendationRow("unwatched", context.getString(R.string.library_recommend_unwatched), unwatched))
        }
    }

    private fun collectionEntries(items: List<MediaItem>, sort: SectionSort, order: SectionSortOrder): List<CollectionEntry> {
        val groups = linkedMapOf<String, Pair<MediaTag, MutableList<MediaItem>>>()
        items.forEach { item -> item.collections.forEach { tag ->
            val id = tag.id ?: return@forEach
            groups.getOrPut(id) { tag to mutableListOf() }.second += item
        } }
        val entries = groups.map { (id, pair) ->
            val members = pair.second
            CollectionEntry(
                id = id,
                title = pair.first.name,
                itemCount = members.size,
                representative = members.maxByOrNull { it.addedAt ?: 0L } ?: members.first(),
                newestAddedAt = members.maxOfOrNull { it.addedAt ?: 0L },
                lastViewedAt = members.maxOfOrNull { it.lastViewedAt ?: 0L }?.takeIf { it > 0L },
                plays = members.sumOf { it.viewCount ?: 0 },
            )
        }
        val comparator = when (sort) {
            SectionSort.TITLE -> compareBy<CollectionEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
            SectionSort.YEAR, SectionSort.RELEASE_DATE -> compareBy<CollectionEntry> { it.representative.year ?: Int.MIN_VALUE }
            SectionSort.RECENTLY_ADDED -> compareBy<CollectionEntry> { it.newestAddedAt ?: 0L }
            SectionSort.RECENTLY_VIEWED -> compareBy<CollectionEntry> { it.lastViewedAt ?: 0L }
            SectionSort.RATING -> compareBy<CollectionEntry> { it.representative.librarySortRating() }
            SectionSort.CRITIC_RATING -> compareBy<CollectionEntry> { it.representative.librarySortCritic() }
            SectionSort.AUDIENCE_RATING -> compareBy<CollectionEntry> { it.representative.librarySortAudience() }
            SectionSort.DURATION -> compareBy<CollectionEntry> { it.representative.durationMs ?: 0L }
            SectionSort.PLAYS -> compareBy<CollectionEntry> { it.plays }
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        return entries.sortedWith(if (order == SectionSortOrder.ASCENDING) comparator else comparator.reversed())
    }

    companion object {
        const val FILTER_WATCH = "watch"
        const val FILTER_QUALITY = "quality"
        const val FILTER_GENRE = "genre"
        const val FILTER_DECADE = "decade"
        const val FILTER_CONTENT_RATING = "contentRating"
        private const val PROGRESSIVE_BATCH_SIZE = 500
    }
}

internal fun SectionUiState.Loaded.withLatestBrowseFocus(snapshot: BrowseFocusSnapshot?): SectionUiState.Loaded {
    snapshot ?: return this
    val collection = snapshot.itemKey.startsWith("collection:")
    val row = snapshot.rowId?.takeIf { id -> recommendationRows.any { candidate ->
        candidate.id == id && candidate.items.any { snapshot.itemKey in it.allRatingKeys }
    } }
    return copy(
        focusedRatingKey = snapshot.itemKey.takeUnless { collection },
        focusedRecommendationRowId = row,
        focusedCollectionId = snapshot.itemKey.removePrefix("collection:").takeIf { collection },
    )
}
