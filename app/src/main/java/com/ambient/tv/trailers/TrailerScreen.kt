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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Tune
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
    LaunchedEffect(item, credentialVersion, language, enabled) {
        available = false
        if (!enabled || item.type !in setOf("movie", "show")) return@LaunchedEffect
        // Trailers stored on the media server need no TMDb key and are checked first.
        available = dependencies.media().getServerTrailers(item.ratingKey).getOrNull()?.isNotEmpty() == true
        if (!available && repository.configured) {
            available = repository.forItem(item, language).getOrNull()?.isNotEmpty() == true
        }
    }
    return available
}

private data class TrailerFeedUi(
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
        ?.let { runCatching { TrailerViewMode.valueOf(it) }.getOrNull() } ?: TrailerViewMode.LANDSCAPE) }
    var sort by remember(selectedFeedName) { mutableStateOf(preferences.sectionSort(stateKey)
        ?.let { runCatching { TrailerSort.valueOf(it) }.getOrNull() } ?: TrailerSort.DEFAULT) }
    var filter by remember(selectedFeedName) { mutableStateOf(preferences.sectionFilter(stateKey)
        ?.let { runCatching { TrailerFilter.valueOf(it) }.getOrNull() } ?: TrailerFilter.ALL) }
    var focusedKey by remember(selectedFeedName) { mutableStateOf(preferences.sectionFocus(stateKey)) }
    var showViewOptions by rememberSaveable { mutableStateOf(false) }
    var optionsOpened by remember { mutableStateOf(false) }
    val optionsFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(showViewOptions, optionsOpened) {
        if (!showViewOptions && optionsOpened) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { optionsFocus.requestFocus() }
            optionsOpened = false
        }
    }
    val states = remember { mutableStateMapOf<TrailerFeed, TrailerFeedUi>() }
    val loads = remember { mutableMapOf<TrailerFeed, Any>() }
    val scope = rememberCoroutineScope()
    val loadKey = "$credentialVersion:$language:$region:$enabled"
    val currentLoadKey by rememberUpdatedState(loadKey)

    suspend fun loadPage(target: TrailerFeed, page: Int, replace: Boolean) {
        if (!replace && (loads.containsKey(target) || states[target]?.loadKey != loadKey)) return
        val token = Any()
        loads[target] = token
        val before = states[target] ?: TrailerFeedUi()
        states[target] = if (replace) before.copy(loadKey = loadKey, entries = emptyList(), page = 0,
            loadingInitial = true, loadingMore = false, error = null, nextError = null, partialFailure = false)
        else before.copy(loadingMore = true, nextError = null)
        try {
            repository.feedPage(target, page, language, region).fold(
                onSuccess = { result ->
                    if (loads[target] !== token || currentLoadKey != loadKey) return@fold
                    val current = states[target] ?: TrailerFeedUi()
                    val entries = if (replace) result.entries else (current.entries + result.entries).distinctBy { it.key }
                    states[target] = current.copy(loadKey = loadKey, entries = entries, page = result.page,
                        totalPages = result.totalPages, loadingInitial = false, loadingMore = false,
                        error = null, nextError = null, partialFailure = current.partialFailure || result.partialFailure)
                },
                onFailure = { failure ->
                    if (loads[target] !== token || currentLoadKey != loadKey) return@fold
                    val current = states[target] ?: TrailerFeedUi()
                    states[target] = if (replace) current.copy(loadKey = loadKey, loadingInitial = false, error = failure)
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

    LaunchedEffect(feed, loadKey, configured, enabled) {
        if (configured && enabled && states[feed]?.loadKey != loadKey) loadPage(feed, 1, replace = true)
    }
    val state = states[feed] ?: TrailerFeedUi(loadKey = loadKey, loadingInitial = configured && enabled)
    val visibleEntries = remember(state.entries, sort, filter) { applyTrailerViewOptions(state.entries, sort, filter) }
    ReportSidebarContentReady(!state.loadingInitial)
    BackHandler(enabled = showViewOptions) { showViewOptions = false }
    BackHandler(enabled = !showViewOptions) { optionsOpened = false; onBack() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 28.dp)) {
            PageHeader(actions = {
                TvChip(stringResource(R.string.trailers_back), modifier = Modifier.leftKeyAction(onBack), icon = Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true, onClick = onBack)
                if (configured && enabled) TvChip(stringResource(R.string.action_view), icon = Icons.Filled.Tune, iconOnly = true, onClick = { optionsOpened = true; showViewOptions = true }, modifier = Modifier.focusRequester(optionsFocus))
            }) { SystemPageTitle(MediaSystem.TRAILERS) }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                items(TrailerFeed.entries, key = { it.name }) { option ->
                    TvChip(stringResource(option.label), selected = option == feed, onClick = {
                        if (onSelectFeed == null) selectedFeedName = option.name else onSelectFeed(option)
                    })
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    !configured || !enabled -> TrailerSetupRequired(onOpenSettings)
                    state.loadingInitial -> LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        val skeletonCount = if (viewMode == TrailerViewMode.POSTER) gridColumns else landscapeGridColumns(gridColumns)
                        items(3) { SkeletonRow("", style = if (viewMode == TrailerViewMode.POSTER) MediaTileStyle.POSTER else MediaTileStyle.LANDSCAPE, itemCount = skeletonCount) }
                    }
                    state.error != null -> TrailerErrorPanel(state.error, { scope.launch { loadPage(feed, 1, true) } })
                    visibleEntries.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatePanel(stringResource(R.string.trailers_empty), stringResource(R.string.trailers_empty_filtered))
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
            sorts = TrailerSort.entries.map { PanelOption(it.name, stringResource(when (it) {
                TrailerSort.DEFAULT -> R.string.trailers_sort_default
                TrailerSort.TITLE -> R.string.library_sort_title
                TrailerSort.DATE -> R.string.trailers_sort_date
            })) }, selectedSort = sort.name,
            onSort = { TrailerSort.valueOf(it).let { value -> sort = value; preferences.setSectionSort(stateKey, value.name) } },
            filters = TrailerFilter.entries.map { PanelOption(it.name, stringResource(when (it) {
                TrailerFilter.ALL -> R.string.library_filter_all
                TrailerFilter.OFFICIAL -> R.string.trailers_filter_official
                TrailerFilter.TRAILERS -> R.string.trailers_filter_trailers
                TrailerFilter.TEASERS -> R.string.trailers_filter_teasers
            })) }, selectedFilter = filter.name,
            onFilter = { TrailerFilter.valueOf(it).let { value -> filter = value; preferences.setSectionFilter(stateKey, value.name) } },
        )
    }
}

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
    var title by remember { mutableStateOf("") }
    var videos by remember { mutableStateOf<List<TrailerVideo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var selectedVideo by rememberSaveable { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    var serverTrailers by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    BackHandler { onBack() }
    LaunchedEffect(ratingKey) {
        serverTrailers = ratingKey?.let { dependencies.media().getServerTrailers(it).getOrNull() }.orEmpty()
    }
    LaunchedEffect(ratingKey, tmdbType, tmdbId, language, retry, version) {
        loading = true; error = null; videos = emptyList()
        if (!dependencies.trailers().configured || !dependencies.preferences().trailersEnabled) { loading = false; return@LaunchedEffect }
        com.ambient.tv.util.suspendResult {
            if (tmdbType != null && tmdbId != null) {
                dependencies.trailers().forTmdb(tmdbType, tmdbId, language).getOrThrow().also { title = it.first.displayTitle }.second
            } else {
                val item = dependencies.media().cachedMediaItem(requireNotNull(ratingKey))
                    ?: dependencies.media().getMetadataFast(ratingKey).getOrThrow() ?: error("Missing media")
                title = item.title
                dependencies.trailers().forItem(item, language).getOrThrow()
            }
        }.onSuccess { videos = it }.onFailure { error = it }
        loading = false
    }
    LaunchedEffect(loading, videos, serverTrailers) {
        if (!loading && (videos.isNotEmpty() || serverTrailers.isNotEmpty())) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }
    }
    ReportSidebarContentReady(!loading)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
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
