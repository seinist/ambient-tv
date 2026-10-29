package com.ambient.tv.trailers

import com.ambient.tv.ui.common.leftKeyAction
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.repository.MediaRepository
import com.ambient.tv.ui.common.*
import com.ambient.tv.ui.mobile.deviceGridCells
import com.ambient.tv.ui.mobile.deviceDensityGridCells
import com.ambient.tv.ui.mobile.deviceVideoGridCells
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TrailerDependencies {
    fun trailers(): TmdbTrailerRepository
    fun media(): MediaRepository
    fun preferences(): com.ambient.tv.data.local.AppPreferences
}

/** Resolves the title once before exposing a trailer action on a server detail page. */
@Composable
fun rememberTrailerAvailable(item: MediaItem, enabled: Boolean): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dependencies = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, TrailerDependencies::class.java)
    }
    val repository = dependencies.trailers()
    val preferences = dependencies.preferences()
    val credentialVersion by repository.configuration.collectAsState()
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val language = when (preferences.trailerLanguage) {
        "de" -> "de-DE"
        "en" -> "en-US"
        else -> locale.toLanguageTag()
    }
    var available by remember(item, credentialVersion, language, enabled) { mutableStateOf(false) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    com.ambient.tv.ui.common.RefreshOnReconnect(setOf("trailers", com.ambient.tv.data.browse.mediaProvider(item.ratingKey))) {
        if (enabled) refreshVersion++
    }
    LaunchedEffect(item, credentialVersion, language, enabled, refreshVersion) {
        if (!enabled || item.type !in setOf("movie", "show")) return@LaunchedEffect
        // Trailers stored on the media server need no TMDb key and are checked first.
        val server = dependencies.media().getServerTrailers(item.ratingKey)
        if (server.getOrNull()?.isNotEmpty() == true) available = true
        else if (repository.configured) repository.forItem(item, language).onSuccess { available = it.isNotEmpty() }
        else if (server.isSuccess) available = false
    }
    return available
}

internal data class TrailerFeedUi(
    val loadKey: String = "",
    val entries: List<TrailerEntry> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 1,
    val loadingInitial: Boolean = true,
    val loadingMore: Boolean = false,
    val error: Throwable? = null,
    val nextError: Throwable? = null,
    val partialFailure: Boolean = false,
) { val hasNextPage: Boolean get() = page < totalPages }

@Composable
fun TrailerScreen(
    onBack: () -> Unit,
    onPlay: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTitle: (String, Int) -> Unit,
    initialFeedKey: String? = null,
    onSelectFeed: ((TrailerFeed) -> Unit)? = null,
    ratingKey: String? = null,
    tmdbType: String? = null,
    tmdbId: Int? = null,
    onPlayServerTrailer: (String) -> Unit = {},
) {
    if (ratingKey != null || tmdbType != null && tmdbId != null) {
        TrailerTitleScreen(onBack, onPlay, onOpenSettings, ratingKey, tmdbType, tmdbId, onPlayServerTrailer)
    } else {
        TrailerCatalogScreen(onBack, onOpenSettings, onOpenTitle, initialFeedKey, onSelectFeed)
    }
}

@Composable
private fun TrailerCatalogScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTitle: (String, Int) -> Unit,
    initialFeedKey: String?,
    onSelectFeed: ((TrailerFeed) -> Unit)?,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dependencies = remember(context) { EntryPointAccessors.fromApplication(context.applicationContext, TrailerDependencies::class.java) }
    TrailerCatalogContent(onBack, onOpenSettings, onOpenTitle, initialFeedKey, onSelectFeed,
        dependencies.trailers(), dependencies.preferences())
}

@Composable
internal fun TrailerCatalogContent(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTitle: (String, Int) -> Unit,
    initialFeedKey: String?,
    onSelectFeed: ((TrailerFeed) -> Unit)?,
    repository: TmdbTrailerRepository,
    preferences: com.ambient.tv.data.local.AppPreferences,
) {
    var gridColumns by remember { mutableIntStateOf(preferences.libraryGridColumns) }
    var listRows by remember { mutableIntStateOf(preferences.libraryListRows) }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val credentialVersion by repository.configuration.collectAsState()
    val configured = repository.configured
    val enabled = preferences.trailersEnabled
    val language = when (preferences.trailerLanguage) { "de" -> "de-DE"; "en" -> "en-US"; else -> locale.toLanguageTag() }
    val region = locale.country.ifBlank { "US" }
    val initialFeed = initialFeedKey?.let { key -> TrailerFeed.entries.firstOrNull { it.name == key } } ?: TrailerFeed.CINEMA
    var selectedFeedName by rememberSaveable(initialFeed.name) { mutableStateOf(initialFeed.name) }
    val feed = TrailerFeed.valueOf(selectedFeedName)
    val stateKey = "trailers:${feed.name}"
    var viewMode by remember(selectedFeedName) { mutableStateOf(preferences.sectionViewMode(stateKey)
        ?.let { runCatching { TrailerViewMode.valueOf(it) }.getOrNull() } ?: TrailerViewMode.POSTER) }
    var sort by remember(selectedFeedName) { mutableStateOf(preferences.sectionSort(stateKey)
        ?.let { runCatching { TrailerSort.valueOf(it) }.getOrNull() } ?: TrailerSort.DEFAULT) }
    var sortDescending by remember(selectedFeedName) {
        mutableStateOf(when (preferences.sectionSortOrder(stateKey)) {
            "DESCENDING" -> true
            "ASCENDING" -> false
            else -> sort == TrailerSort.DATE
        })
    }
    var filter by remember(selectedFeedName) { mutableStateOf(preferences.sectionFilter(stateKey)
        ?.let { runCatching { TrailerFilter.valueOf(it) }.getOrNull() } ?: TrailerFilter.ALL) }
    var focusedKey by remember(selectedFeedName) { mutableStateOf(preferences.sectionFocus(stateKey)) }
    var showViewOptions by rememberSaveable { mutableStateOf(false) }
    var optionsOpened by remember { mutableStateOf(false) }
    val optionsFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val filterFocus = remember { FocusRequester() }
    LaunchedEffect(showViewOptions, optionsOpened) {
        if (!showViewOptions && optionsOpened) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { optionsFocus.requestFocus() }
            optionsOpened = false
        }
    }
    val snapshot: TrailerCatalogSnapshotViewModel = viewModel(key = "trailer-catalog:${initialFeed.name}")
    val states = snapshot.feeds
    val loads = snapshot.loads
    val scope = rememberCoroutineScope()
    val loadKey = "$credentialVersion:$language:$region:$enabled"
    val currentLoadKey by rememberUpdatedState(loadKey)
    var entryRefresh by remember { mutableIntStateOf(0) }
    var loadedPages by rememberSaveable(loadKey) { mutableStateOf(IntArray(TrailerFeed.entries.size)) }

    suspend fun loadPage(target: TrailerFeed, page: Int, replace: Boolean, keepContent: Boolean = false) {
        if (!replace && (loads.containsKey(target) || states[target]?.loadKey != loadKey)) return
        val token = Any()
        loads[target] = token
        val before = states[target] ?: TrailerFeedUi()
        val refreshThrough = maxOf(page, before.page.takeIf { before.loadKey == loadKey } ?: 0, loadedPages[target.ordinal])
        states[target] = if (replace) before.copy(loadKey = loadKey,
            entries = if (keepContent) before.entries else emptyList(), page = if (keepContent) before.page else 0,
            loadingInitial = !keepContent || before.page == 0, loadingMore = false, error = null, nextError = null, partialFailure = false)
        else before.copy(loadingMore = true, nextError = null)
        try {
            com.ambient.tv.util.suspendResult {
                var result = repository.feedPage(target, page, language, region).getOrThrow()
                if (replace && refreshThrough > page) {
                    // Keep the focused page visible until its complete loaded extent is fresh.
                    val entries = result.entries.toMutableList()
                    var partialFailure = result.partialFailure
                    while (result.page < minOf(refreshThrough, result.totalPages)) {
                        if (loads[target] !== token || currentLoadKey != loadKey) return@suspendResult result
                        result = repository.feedPage(target, result.page + 1, language, region).getOrThrow()
                        entries += result.entries
                        partialFailure = partialFailure || result.partialFailure
                    }
                    result.copy(entries = entries.distinctBy { it.key }, partialFailure = partialFailure)
                } else result
            }.fold(
                onSuccess = { result ->
                    if (loads[target] !== token || currentLoadKey != loadKey) return@fold
                    val current = states[target] ?: TrailerFeedUi()
                    val entries = if (replace) result.entries else (current.entries + result.entries).distinctBy { it.key }
                    states[target] = current.copy(loadKey = loadKey, entries = entries, page = result.page,
                        totalPages = result.totalPages, loadingInitial = false, loadingMore = false,
                        error = null, nextError = null, partialFailure = current.partialFailure || result.partialFailure)
                    loadedPages = loadedPages.copyOf().also { it[target.ordinal] = result.page }
                },
                onFailure = { failure ->
                    if (loads[target] !== token || currentLoadKey != loadKey) return@fold
                    val current = states[target] ?: TrailerFeedUi()
                    states[target] = if (replace) current.copy(loadKey = loadKey, loadingInitial = false,
                        error = failure.takeIf { current.entries.isEmpty() })
                    else current.copy(loadingMore = false, nextError = failure)
                },
            )
        } finally {
            if (loads[target] === token) {
                loads.remove(target)
                val current = states[target]
                // Switching tabs cancels the initial effect. Do not leave a cached spinner forever.
                if (current?.loadingInitial == true || currentLoadKey != loadKey) states.remove(target)
                else if (current != null) states[target] = current.copy(loadingMore = false)
            }
        }
    }

    com.ambient.tv.ui.common.RefreshOnEnterAndReconnect(setOf("trailers")) {
        // Old requests cannot republish pages from before this entry; other feeds reload when opened.
        loads.clear()
        states.keys.filter { it != feed }.forEach { states.remove(it) }
        entryRefresh++
    }
    LaunchedEffect(feed, loadKey, configured, enabled, entryRefresh) {
        if (entryRefresh > 0 && configured && enabled) {
            loadPage(feed, 1, replace = true, keepContent = states[feed]?.loadKey == loadKey)
        }
    }
    val state = states[feed]?.takeIf { it.loadKey == loadKey }
        ?: TrailerFeedUi(loadKey = loadKey, loadingInitial = configured && enabled)
    val visibleEntries = remember(state.entries, sort, sortDescending, filter) {
        applyTrailerViewOptions(state.entries, sort, filter, sortDescending)
    }
    ReportSidebarContentReady(!state.loadingInitial)
    BackHandler(enabled = showViewOptions) { showViewOptions = false }
    BackHandler(enabled = !showViewOptions) { optionsOpened = false; onBack() }
    val filterModel = FilterBarModel(
        groups = listOf(PanelOptionGroup("filter", stringResource(R.string.library_filter),
            TrailerFilter.entries.map { PanelOption(it.name, trailerFilterLabel(it)) }, filter.name)),
        onSelect = { _, key -> TrailerFilter.valueOf(key).let { value -> filter = value; preferences.setSectionFilter(stateKey, value.name) } },
        sorts = TrailerSort.entries.map { PanelOption(it.name, stringResource(when (it) {
            TrailerSort.DEFAULT -> R.string.trailers_sort_default
            TrailerSort.TITLE -> R.string.library_sort_title
            TrailerSort.DATE -> R.string.trailers_sort_date
        })) }, selectedSort = sort.name,
        onSort = { TrailerSort.valueOf(it).let { value ->
            val descending = value == TrailerSort.DATE
            sort = value
            sortDescending = descending
            preferences.setSectionSort(stateKey, value.name)
            preferences.setSectionSortOrder(stateKey, if (descending) "DESCENDING" else "ASCENDING")
        } },
        sortOrders = trailerSortOrders(sort),
        selectedSortOrder = if (sortDescending) "DESCENDING" else "ASCENDING",
        onSortOrder = { key ->
            sortDescending = key == "DESCENDING"
            preferences.setSectionSortOrder(stateKey, key)
        },
        onReset = { filter = TrailerFilter.ALL; preferences.setSectionFilter(stateKey, TrailerFilter.ALL.name) },
    )
    val filterBarShown = configured && enabled && LocalFilterBarSetting.current.shown

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = com.ambient.tv.ui.common.pageHeaderTop(28.dp))) {
            PageHeader(actions = {
                TvChip(stringResource(R.string.trailers_back), modifier = Modifier.leftKeyAction(onBack), icon = Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true, onClick = onBack)
                if (configured && enabled) ViewOptionsChip(
                    activeCount = if (filterBarShown) 0 else filterModel.activeCount,
                    onClick = { optionsOpened = true; showViewOptions = true },
                    modifier = Modifier.focusRequester(optionsFocus),
                )
            }) { SystemPageTitle(MediaSystem.TRAILERS) }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                items(TrailerFeed.entries, key = { it.name }) { option ->
                    TvChip(stringResource(option.label), selected = option == feed, onClick = {
                        if (onSelectFeed == null) selectedFeedName = option.name else onSelectFeed(option)
                    })
                }
            }
            if (filterBarShown) FilterBar(filterModel, Modifier.padding(bottom = 8.dp), focusRequester = filterFocus)
            Box(
                Modifier.fillMaxWidth().weight(1f).then(if (!filterBarShown) Modifier else Modifier.focusProperties {
                    onExit = {
                        if (requestedFocusDirection == FocusDirection.Up) {
                            cancelFocusChange()
                            runCatching { filterFocus.requestFocus() }
                        }
                    }
                }),
            ) {
                SoftViewChange(viewMode, Modifier.fillMaxSize()) { when {
                    !configured || !enabled -> TrailerSetupRequired(onOpenSettings)
                    state.loadingInitial -> LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        val skeletonCount = if (viewMode == TrailerViewMode.POSTER) gridColumns else landscapeGridColumns(gridColumns)
                        items(3) { SkeletonRow("", style = if (viewMode == TrailerViewMode.POSTER) MediaTileStyle.POSTER else MediaTileStyle.LANDSCAPE, itemCount = skeletonCount) }
                    }
                    state.error != null -> TrailerErrorPanel(state.error, { scope.launch { loadPage(feed, 1, true) } })
                    visibleEntries.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (filter == TrailerFilter.ALL) {
                            StatePanel(stringResource(R.string.trailers_empty))
                        } else {
                            StatePanel(
                                title = stringResource(R.string.trailers_empty_filtered),
                                actionLabel = stringResource(R.string.library_filter_reset),
                                onAction = {
                                    filter = TrailerFilter.ALL
                                    preferences.setSectionFilter(stateKey, TrailerFilter.ALL.name)
                                },
                            )
                        }
                        // A filter may hide this page but match later ones. Do not strand the user
                        // or automatically crawl hundreds of empty pages in the background.
                        if (state.hasNextPage && !state.loadingMore && state.nextError == null) {
                            TvActionButton(stringResource(R.string.files_more), onClick = {
                                scope.launch { loadPage(feed, state.page + 1, false) }
                            })
                        }
                        if (state.loadingMore || state.nextError != null) {
                            TrailerPagingFooter(state, { scope.launch { loadPage(feed, state.page + 1, false) } })
                        }
                    }
                    viewMode == TrailerViewMode.LIST -> TrailerList(visibleEntries, listRows, focusedKey,
                        { entry -> focusedKey = entry.key; preferences.setSectionFocus(stateKey, entry.key) },
                        onOpenTitle, state, { scope.launch { loadPage(feed, state.page + 1, false) } })
                    else -> TrailerGrid(visibleEntries, viewMode, gridColumns, focusedKey,
                        { entry -> focusedKey = entry.key; preferences.setSectionFocus(stateKey, entry.key) },
                        onOpenTitle, state, { scope.launch { loadPage(feed, state.page + 1, false) } })
                }
                }
            }
        }
        if (showViewOptions) ViewOptionsPanel(
            views = TrailerViewMode.entries.map { mode -> ViewOption(mode.name, stringResource(when (mode) {
                TrailerViewMode.LANDSCAPE -> R.string.trailers_view_landscape
                TrailerViewMode.POSTER -> R.string.trailers_view_poster
                TrailerViewMode.LIST -> R.string.trailers_view_list
            }), when (mode) { TrailerViewMode.LANDSCAPE -> ViewPreview.LANDSCAPE; TrailerViewMode.POSTER -> ViewPreview.POSTER; TrailerViewMode.LIST -> ViewPreview.INFO_LIST }) },
            selectedView = viewMode.name,
            onView = { TrailerViewMode.valueOf(it).let { mode -> viewMode = mode; preferences.setSectionViewMode(stateKey, mode.name) } },
            gridColumns = gridColumns, listRows = listRows,
            onGridColumns = { value -> preferences.libraryGridColumns = value; gridColumns = preferences.libraryGridColumns },
            onListRows = { value -> preferences.libraryListRows = value; listRows = preferences.libraryListRows },
            onClose = { showViewOptions = false }, modifier = Modifier.align(Alignment.CenterEnd),
            sorts = filterModel.sorts, selectedSort = filterModel.selectedSort, onSort = filterModel.onSort,
            sortOrders = filterModel.sortOrders, selectedSortOrder = filterModel.selectedSortOrder, onSortOrder = filterModel.onSortOrder,
            filterGroups = filterModel.groups, onFilterGroup = filterModel.onSelect,
            filtersActive = filterModel.activeCount > 0, onResetFilters = filterModel.onReset,
        )
    }
}

@Composable
private fun trailerSortOrders(sort: TrailerSort): List<PanelOption> = when (sort) {
    TrailerSort.DEFAULT -> emptyList()
    TrailerSort.TITLE -> sortDirectionOptions(stringResource(R.string.library_sort_az), stringResource(R.string.library_sort_za))
    TrailerSort.DATE -> sortDirectionOptions(stringResource(R.string.library_sort_oldest), stringResource(R.string.library_sort_newest))
}

@Composable
private fun trailerFilterLabel(filter: TrailerFilter) = stringResource(when (filter) {
    TrailerFilter.ALL -> R.string.library_filter_all
    TrailerFilter.OFFICIAL -> R.string.trailers_filter_official
    TrailerFilter.TRAILERS -> R.string.trailers_filter_trailers
    TrailerFilter.TEASERS -> R.string.trailers_filter_teasers
})

@Composable
private fun TrailerGrid(entries: List<TrailerEntry>, mode: TrailerViewMode, columns: Int, focusedKey: String?,
    onFocused: (TrailerEntry) -> Unit, onOpenTitle: (String, Int) -> Unit, state: TrailerFeedUi, onLoadMore: () -> Unit) {
    val selectedIndex = entries.indexOfFirst { it.key == focusedKey }.coerceAtLeast(0)
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = (selectedIndex - 1).coerceAtLeast(0))
    LaunchedEffect(gridState, state.page, state.hasNextPage, state.loadingMore, state.nextError) {
        snapshotFlow {
            val layout = gridState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            state.hasNextPage && !state.loadingMore && state.nextError == null &&
                layout.totalItemsCount > 0 && lastVisible >= layout.totalItemsCount - maxOf(5, layout.visibleItemsInfo.size / 2)
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) onLoadMore() }
    }
    LazyVerticalGrid(columns = if (mode == TrailerViewMode.POSTER) deviceDensityGridCells(columns) else deviceDensityGridCells(landscapeGridColumns(columns), 260.dp, 4),
        state = gridState, contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(entries, key = { it.key }) { entry ->
            MediaTile(item = entry.toMediaItem(), style = if (mode == TrailerViewMode.POSTER) MediaTileStyle.POSTER else MediaTileStyle.LANDSCAPE,
                onClick = { onOpenTitle(entry.mediaType, entry.title.id) }, onFocused = { onFocused(entry) },
                requestInitialFocus = entry.key == entries[selectedIndex].key, showDescription = mode == TrailerViewMode.POSTER, fillCell = true)
        }
        item(key = "trailer-paging", span = { GridItemSpan(maxLineSpan) }) { TrailerPagingFooter(state, onLoadMore) }
    }
}

@Composable
private fun TrailerList(entries: List<TrailerEntry>, listRows: Int, focusedKey: String?, onFocused: (TrailerEntry) -> Unit,
    onOpenTitle: (String, Int) -> Unit, state: TrailerFeedUi, onLoadMore: () -> Unit) {
    val rowScale = (7f / listRows.coerceIn(4, 10)).coerceIn(0.72f, 1.75f)
    val index = entries.indexOfFirst { it.key == focusedKey }.coerceAtLeast(0)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (index - 1).coerceAtLeast(0))
    LaunchedEffect(listState, state.page, state.hasNextPage, state.loadingMore, state.nextError) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            state.hasNextPage && !state.loadingMore && state.nextError == null &&
                layout.totalItemsCount > 0 && lastVisible >= layout.totalItemsCount - maxOf(3, layout.visibleItemsInfo.size)
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) onLoadMore() }
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(entries, key = { it.key }) { entry ->
            TrailerListItem(entry, rowScale, { onOpenTitle(entry.mediaType, entry.title.id) }, { onFocused(entry) },
                entry.key == entries[index].key)
        }
        item(key = "trailer-paging") { TrailerPagingFooter(state, onLoadMore) }
    }
}

@Composable
private fun TrailerListItem(entry: TrailerEntry, rowScale: Float, onClick: () -> Unit, onFocused: () -> Unit, initialFocus: Boolean) {
    var focused by remember(entry.key) { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(initialFocus) { if (initialFocus) { delay(100); runCatching { requester.requestFocus() } } }
    val shape = RoundedCornerShape(10.dp)
    Surface(onClick = onClick, colors = transparentSurfaceColors(), scale = noSurfaceScale(),
        modifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }) {
        Row(Modifier.background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else MaterialTheme.colorScheme.surface.copy(alpha = .72f), shape)
            .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .08f), shape).padding(10.dp * rowScale),
            horizontalArrangement = Arrangement.spacedBy(16.dp * rowScale), verticalAlignment = Alignment.CenterVertically) {
            ArtworkImage(entry.title.imageUrl, entry.title.displayTitle, Modifier.width(220.dp * rowScale).aspectRatio(16f / 9f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(entry.title.displayTitle, style = MaterialTheme.typography.titleMedium)
                entry.title.year?.let { year -> Text(year.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(entry.title.overview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TrailerPagingFooter(state: TrailerFeedUi, onLoadMore: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.partialFailure) Text(stringResource(R.string.trailers_partial_failure), color = MaterialTheme.colorScheme.error)
        when {
            state.loadingMore -> SkeletonRow("", style = MediaTileStyle.LANDSCAPE, itemCount = 4)
            state.nextError != null -> TrailerErrorPanel(state.nextError, onLoadMore, compact = true)
        }
    }
}

@Composable
private fun TrailerTitleScreen(onBack: () -> Unit, onPlay: (String) -> Unit, onOpenSettings: () -> Unit,
    ratingKey: String?, tmdbType: String?, tmdbId: Int?, onPlayServerTrailer: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dependencies = remember(context) { EntryPointAccessors.fromApplication(context.applicationContext, TrailerDependencies::class.java) }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val version by dependencies.trailers().configuration.collectAsState()
    val language = when (dependencies.preferences().trailerLanguage) { "de" -> "de-DE"; "en" -> "en-US"; else -> locale.toLanguageTag() }
    val requestKey = "$ratingKey:$tmdbType:$tmdbId:$language:$version"
    val snapshot: TrailerTitleSnapshotViewModel = viewModel(key = "trailer-title:$ratingKey:$tmdbType:$tmdbId")
    var title by snapshot.title
    var videos by snapshot.videos
    var loadedKey by snapshot.loadedKey
    var loading by remember(requestKey) { mutableStateOf(loadedKey != requestKey) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var selectedVideo by rememberSaveable { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    var serverTrailers by snapshot.serverTrailers
    var initialFocusRequested by remember(requestKey) { mutableStateOf(false) }
    val sources = setOfNotNull("trailers", ratingKey?.let { com.ambient.tv.data.browse.mediaProvider(it) })
    com.ambient.tv.ui.common.RefreshOnEnterAndReconnect(sources) {
        retry++
    }
    BackHandler { onBack() }
    LaunchedEffect(ratingKey, retry) {
        if (retry == 0) return@LaunchedEffect
        if (ratingKey == null) serverTrailers = emptyList()
        else dependencies.media().getServerTrailers(ratingKey).onSuccess { serverTrailers = it }
    }
    LaunchedEffect(ratingKey, tmdbType, tmdbId, language, retry, version) {
        if (retry == 0) return@LaunchedEffect
        val keepContent = loadedKey == requestKey
        loading = !keepContent; error = null
        if (!keepContent) videos = emptyList()
        if (!dependencies.trailers().configured || !dependencies.preferences().trailersEnabled) { loading = false; return@LaunchedEffect }
        com.ambient.tv.util.suspendResult {
            if (tmdbType != null && tmdbId != null) {
                val result = dependencies.trailers().forTmdb(tmdbType, tmdbId, language).getOrThrow()
                result.first.displayTitle to result.second
            } else {
                val item = dependencies.media().getMetadataFast(requireNotNull(ratingKey)).getOrThrow()
                    ?: error("Missing media")
                item.title to dependencies.trailers().forItem(item, language).getOrThrow()
            }
        }.onSuccess { (freshTitle, freshVideos) ->
            title = freshTitle; videos = freshVideos; loadedKey = requestKey
        }.onFailure { if (!keepContent) error = it }
        loading = false
    }
    LaunchedEffect(loading, videos, serverTrailers) {
        if (!initialFocusRequested && !loading && (videos.isNotEmpty() || serverTrailers.isNotEmpty())) {
            withFrameNanos { }
            initialFocusRequested = runCatching { firstFocus.requestFocus() }.getOrDefault(false)
        }
    }
    ReportSidebarContentReady(!loading)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 32.dp, end = 32.dp, bottom = 32.dp, top = com.ambient.tv.ui.common.pageHeaderTop(32.dp)), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageHeader(actions = {
            TvChip(stringResource(R.string.trailers_back), modifier = Modifier.leftKeyAction(onBack), icon = Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true, onClick = onBack)
        }) {
            Text(title.ifBlank { stringResource(R.string.trailers_title) }, style = MaterialTheme.typography.headlineLarge)
        } }
        if (serverTrailers.isNotEmpty()) item {
            ServerTrailerRow(serverTrailers, onPlayServerTrailer, firstFocus)
        }
        when {
            !dependencies.trailers().configured || !dependencies.preferences().trailersEnabled ->
                if (serverTrailers.isEmpty()) item { TrailerSetupRequired(onOpenSettings) } else Unit
            loading -> items(3) { SkeletonRow("", style = MediaTileStyle.LANDSCAPE) }
            error != null -> item { TrailerErrorPanel(error, { retry++ }) }
            videos.isEmpty() -> if (serverTrailers.isEmpty()) item {
                StatePanel(stringResource(R.string.trailers_empty), stringResource(R.string.trailers_empty_title))
            } else Unit
            else -> item {
                TrailerVideoRow(videos, { selectedVideo = it; onPlay(it) },
                    if (serverTrailers.isEmpty()) firstFocus else remember { FocusRequester() }, selectedVideo)
            }
        }
    }
}

/** Trailers stored with the title on the media server; played by the regular player. */
@Composable
private fun ServerTrailerRow(trailers: List<MediaItem>, onPlay: (String) -> Unit, firstFocus: FocusRequester) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.trailers_from_server), style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(trailers, key = { it.ratingKey }) { trailer ->
                val first = trailer === trailers.first()
                TvActionButton(
                    text = trailer.title.ifBlank { stringResource(R.string.trailers_title) },
                    onClick = { onPlay(trailer.ratingKey) },
                    modifier = if (first) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun TrailerSetupRequired(onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        StatePanel(stringResource(R.string.trailers_unconfigured), stringResource(R.string.trailers_setup_hint))
        TvActionButton(stringResource(R.string.trailers_settings), onOpenSettings)
    }
}

@Composable
private fun TrailerErrorPanel(error: Throwable?, onRetry: () -> Unit, compact: Boolean = false) {
    val retryAfter = (error as? TrailerRateLimited)?.retryAfterSeconds?.coerceAtLeast(1) ?: 0
    var remaining by remember(error) { mutableLongStateOf(retryAfter) }
    LaunchedEffect(error) {
        val deadline = SystemClock.elapsedRealtime() + retryAfter * 1_000
        while (remaining > 0) { delay(1_000); remaining = ((deadline - SystemClock.elapsedRealtime() + 999) / 1_000).coerceAtLeast(0) }
    }
    val message = stringResource(when (error) {
        is TrailerAuthenticationFailed -> R.string.trailers_key_invalid
        is TrailerRateLimited -> R.string.trailers_rate_limited
        is TrailerNotFound -> R.string.trailers_not_found
        is TrailerServiceUnavailable -> R.string.trailers_service_unavailable
        else -> R.string.trailers_failed
    })
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp)) {
        Text(if (remaining > 0) "$message ${stringResource(R.string.trailers_retry_seconds, remaining)}" else message,
            color = MaterialTheme.colorScheme.error)
        TvActionButton(stringResource(R.string.trailers_retry), onRetry, enabled = remaining == 0L)
    }
}

private fun TrailerEntry.toMediaItem() = MediaItem(
    ratingKey = "trailer-title:$mediaType:${title.id}", title = title.displayTitle, type = mediaType,
    index = null, thumbUrl = title.posterUrl ?: title.imageUrl, artUrl = title.imageUrl,
    year = title.year, summary = title.overview, tagline = null, durationMs = null, viewOffsetMs = null,
    parentTitle = null, parentRatingKey = null, grandparentTitle = null,
    grandparentRatingKey = null, contentRating = null, rating = null, studio = "TMDB",
    guid = "tmdb://${title.id}", guids = listOf("tmdb://${title.id}"),
)

fun TrailerSearchItem.toMediaItem() = MediaItem(
    ratingKey = "trailer-title:$mediaType:${title.id}", title = title.displayTitle, type = if (mediaType == "tv") "show" else mediaType,
    index = null, thumbUrl = title.posterUrl ?: title.imageUrl, artUrl = title.imageUrl,
    year = title.year, summary = title.overview, tagline = null, durationMs = null, viewOffsetMs = null,
    parentTitle = null, parentRatingKey = null, grandparentTitle = null, grandparentRatingKey = null,
    contentRating = null, rating = null, studio = "TMDB", guid = "tmdb://${title.id}", guids = listOf("tmdb://${title.id}"),
)

@Composable
private fun TrailerVideoRow(videos: List<TrailerVideo>, onPlay: (String) -> Unit, focus: FocusRequester?, selectedVideo: String?) {
    val target = selectedVideo?.takeIf { selected -> videos.any { it.key == selected } } ?: videos.firstOrNull()?.key
    val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = videos.indexOfFirst { it.key == target }.coerceAtLeast(0))
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(videos, key = { it.key }) { video ->
            MediaTile(item = video.toMediaItem(), style = MediaTileStyle.LANDSCAPE, onClick = { onPlay(video.key) },
                modifier = if (focus != null && video.key == target) Modifier.focusRequester(focus) else Modifier,
                showDescription = false)
        }
    }
}

private fun TrailerVideo.toMediaItem() = MediaItem(
    ratingKey = "trailer-video:$key", title = name.ifBlank { type }, type = "clip",
    index = null, thumbUrl = "https://i.ytimg.com/vi/$key/hqdefault.jpg", artUrl = "https://i.ytimg.com/vi/$key/hqdefault.jpg",
    year = null, summary = null, tagline = null, durationMs = null, viewOffsetMs = null,
    parentTitle = null, parentRatingKey = null, grandparentTitle = null, grandparentRatingKey = null,
    contentRating = null, rating = null, studio = null, guid = null, guids = emptyList(),
)
