package com.ambient.tv.ui.search

import com.ambient.tv.ui.theme.screenGutter
import androidx.activity.compose.BackHandler
import com.ambient.tv.ui.mobile.deviceDp

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.ambient.tv.ui.common.TvLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.ambient.tv.ui.common.rememberBrowseListState as rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.ambient.tv.ui.common.CenteredMarqueeTitle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.branding.BrandMark
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.ui.radio.radioStationSubtitle
import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.spotify.SpotifyBrowseItem
import com.ambient.tv.spotify.SpotifyEntityType
import com.ambient.tv.tvheadend.domain.TvhChannel
import com.ambient.tv.tvheadend.domain.TvhDvrEntry
import com.ambient.tv.tvheadend.domain.TvhEpgEvent
import com.ambient.tv.ui.common.SoftViewChange
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.ContentRatingBadge
import com.ambient.tv.ui.common.ContentSectionHeader
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.MarqueeTitle
import com.ambient.tv.ui.common.MediaTile
import com.ambient.tv.ui.common.MediaTileStyle
import com.ambient.tv.ui.common.MediaArtworkWithStreamIcons
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.common.SkeletonRow
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.LocalFilterFocusState
import com.ambient.tv.ui.common.ViewOption
import com.ambient.tv.ui.common.ViewOptionsChip
import com.ambient.tv.ui.common.ViewOptionsPanel
import com.ambient.tv.ui.common.ViewPreview
import com.ambient.tv.ui.common.fillWindowHeight
import com.ambient.tv.ui.common.rememberFilterFocusState
import com.ambient.tv.ui.common.pageContentUp
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.overlayLazyScrollbar
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.mediaFocusMotion
import com.ambient.tv.ui.common.formatDuration
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.systemName
import com.ambient.tv.ui.common.transparentSurfaceColors
import com.ambient.tv.ui.common.bringFocusedContextIntoView
import com.ambient.tv.ui.common.containRowFocusExit
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SearchScreen(
    onItemClick: (String) -> Unit,
    onYouTubeClick: (String) -> Unit,
    onSpotifyClick: (SpotifyBrowseItem) -> Unit,
    onRadioClick: (RadioStation) -> Unit,
    onTvheadendChannelClick: (TvhChannel) -> Unit,
    onTvheadendProgramClick: (TvhEpgEvent) -> Unit,
    onTvheadendRecordingClick: (TvhDvrEntry) -> Unit,
    /** Root Back: focus sidebar like Home/Files/YT. */
    onBack: (() -> Unit)? = null,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val filterFocusState = rememberFilterFocusState(viewModel)
    val optionsFocus = remember { FocusRequester() }
    val queryFocus = remember { FocusRequester() }
    var showOptions by remember(viewModel) { mutableStateOf(false) }
    var restoreOptionsFocus by remember(viewModel) { mutableStateOf(false) }
    LaunchedEffect(uiState.hasResults) {
        if (!uiState.hasResults) showOptions = false
    }
    LaunchedEffect(showOptions, restoreOptionsFocus, uiState.hasResults) {
        if (!showOptions && restoreOptionsFocus) {
            val target = if (uiState.hasResults) optionsFocus else queryFocus
            repeat(3) {
                withFrameNanos { }
                if (runCatching { target.requestFocus() }.getOrDefault(false)) {
                    restoreOptionsFocus = false
                    return@LaunchedEffect
                }
            }
        }
    }
    // A query from the system search, voice search or the Assistant runs as typed here.
    val externalQuery by com.ambient.tv.search.ExternalSearch.requests.collectAsState()
    var entryReady by remember(viewModel) { mutableStateOf(false) }
    LifecycleResumeEffect(viewModel) {
        onPauseOrDispose { entryReady = false }
    }
    fun consumeExternalQuery(): Boolean {
        val query = com.ambient.tv.search.ExternalSearch.requests.value ?: return false
        showOptions = false
        restoreOptionsFocus = false
        viewModel.onQueryChanged(query)
        filterFocusState.enterContent()
        viewModel.submitSearch()
        com.ambient.tv.search.ExternalSearch.consumed(query)
        return true
    }
    com.ambient.tv.ui.common.RefreshOnEnterAndReconnect(viewModel.reconnectSources) {
        entryReady = true
        // Entry and an incoming search share one request after cache preparation.
        if (!consumeExternalQuery()) viewModel.refreshAfterReconnect()
    }
    LaunchedEffect(externalQuery, entryReady) {
        if (entryReady) consumeExternalQuery()
    }
    if (onBack != null) {
        BackHandler(enabled = !showOptions) { onBack() }
    }
    BackHandler(enabled = showOptions) { showOptions = false }
    CompositionLocalProvider(LocalFilterFocusState provides filterFocusState) {
        Box(Modifier.fillMaxSize()) {
            val focusChain = com.ambient.tv.ui.common.rememberPageFocusChain()
            Column(modifier = Modifier.fillMaxSize().padding(start = screenGutter(), end = screenGutter(), top = com.ambient.tv.ui.common.pageHeaderTop(28.dp))) {
                PageHeader(focusChain = focusChain, actions = {
                    if (uiState.hasResults) ViewOptionsChip(
                        activeCount = 0,
                        modifier = Modifier.focusRequester(optionsFocus),
                        onClick = {
                            filterFocusState.keepControls()
                            restoreOptionsFocus = true
                            showOptions = true
                        },
                    )
                }) { Text(stringResource(R.string.search_title), style = MaterialTheme.typography.headlineMedium) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvTextField(
                        value = uiState.query,
                        onValueChange = viewModel::onQueryChanged,
                        label = stringResource(R.string.search_placeholder),
                        modifier = Modifier.weight(1f).focusRequester(queryFocus),
                    )
                    TvActionButton(
                        text = stringResource(R.string.action_search),
                        onClick = { filterFocusState.enterContent(); viewModel.submitSearch() },
                        enabled = uiState.query.trim().length >= 2 && uiState.selectedSources.isNotEmpty(),
                        loading = uiState.isSearching,
                    )
                }
                if (uiState.availableSources.isEmpty()) {
                    Text(stringResource(R.string.search_no_sources), modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    // Sources are a multi-select filter: entering the row starts at "All" until a chip was focused.
                    com.ambient.tv.ui.common.PageChipRow(focusChain,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        up = queryFocus,
                    ) {
                        item(key = "all") {
                            TvChip(
                                modifier = Modifier.focusRequester(focusChain.selectedChip),
                                text = stringResource(R.string.search_all_sources),
                                selected = uiState.selectedSources == uiState.availableSources,
                                onClick = viewModel::selectAllSources,
                            )
                        }
                        items(SearchSource.entries.filter { it in uiState.availableSources }, key = { it.name }) { source ->
                            TvChip(
                                text = searchSourceLabel(source),
                                selected = source in uiState.selectedSources,
                                onClick = { viewModel.toggleSource(source) },
                            )
                        }
                    }
                }
                // Up from the results: source chips, then the query field (no chips: straight to the field).
                Box(Modifier.fillMaxSize()
                    .pageContentUp(focusChain) { if (uiState.availableSources.isEmpty()) queryFocus else focusChain.contentUp }
                    .onFocusChanged { if (it.hasFocus && !showOptions) filterFocusState.enterContent() }
                    .focusGroup()) {
                    when {
                        !uiState.hasSearched -> RecentSearches(uiState.recentSearches, viewModel::useRecentSearch, viewModel::clearRecentSearches)
                        uiState.hasSearched && !uiState.hasResults && !uiState.isSearching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                BrandMark(size = 32.dp, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f), contentDescription = null)
                                StatePanel(
                                    stringResource(R.string.search_no_results),
                                    if (listOf(uiState.plexState, uiState.jellyfinState, uiState.embyState, uiState.trailerState, uiState.youtubeState, uiState.spotifyState, uiState.radioState, uiState.tvheadendState).any { it == SearchSourceState.ERROR || it == SearchSourceState.PARTIAL }) {
                                        stringResource(R.string.search_no_results_partial_error)
                                    } else stringResource(R.string.search_no_results_body),
                                )
                            }
                        }
                        else -> SoftViewChange(uiState.viewMode, Modifier.fillMaxSize()) {
                            if (uiState.viewMode == SearchViewMode.ROWS) SearchRows(
                                uiState,
                                uiState.focusedRatingKey,
                                uiState.restoreResultFocus,
                                uiState.hasResults && !uiState.isSearching && !uiState.restoreResultFocus,
                                onItemClick,
                                onYouTubeClick,
                                onSpotifyClick,
                                viewModel::playRadio,
                                onTvheadendChannelClick,
                                onTvheadendProgramClick,
                                onTvheadendRecordingClick,
                                viewModel::onItemFocused,
                            ) else SearchList(
                                uiState,
                                uiState.focusedRatingKey,
                                uiState.restoreResultFocus,
                                uiState.hasResults && !uiState.isSearching && !uiState.restoreResultFocus,
                                onItemClick,
                                onYouTubeClick,
                                onSpotifyClick,
                                viewModel::playRadio,
                                onTvheadendChannelClick,
                                onTvheadendProgramClick,
                                onTvheadendRecordingClick,
                                viewModel::onItemFocused,
                            )
                        }
                    }
                }
            }
            if (showOptions && uiState.hasResults) {
                Box(Modifier.fillWindowHeight().fillMaxSize().background(Color.Black.copy(alpha = 0.38f)))
                Box(Modifier.align(Alignment.TopEnd)) {
                    ViewOptionsPanel(
                        views = listOf(
                            ViewOption(SearchViewMode.ROWS.name, stringResource(R.string.search_view_rows), ViewPreview.ROWS),
                            ViewOption(SearchViewMode.LIST.name, stringResource(R.string.search_view_list), ViewPreview.LIST),
                        ),
                        selectedView = uiState.viewMode.name,
                        onView = { filterFocusState.keepControls(); viewModel.setViewMode(SearchViewMode.valueOf(it)) },
                        showDensity = false,
                        hint = stringResource(R.string.library_view),
                        gridColumns = 6, listRows = 6,
                        onGridColumns = {}, onListRows = {},
                        onClose = { showOptions = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun searchSourceLabel(source: SearchSource): String = when (source) {
    SearchSource.PLEX -> searchSystemName(MediaSystem.PLEX)
    SearchSource.JELLYFIN -> searchSystemName(MediaSystem.JELLYFIN)
    SearchSource.EMBY -> searchSystemName(MediaSystem.EMBY)
    SearchSource.LIVE_TV -> stringResource(R.string.tvh_live)
    SearchSource.TRAILERS -> searchSystemName(MediaSystem.TRAILERS)
    SearchSource.YOUTUBE -> searchSystemName(MediaSystem.YOUTUBE)
    SearchSource.SPOTIFY -> searchSystemName(MediaSystem.SPOTIFY)
    SearchSource.RADIO -> searchSystemName(MediaSystem.RADIO)
}

@Composable
private fun RecentSearches(recent: List<String>, onSelect: (String) -> Unit, onClear: () -> Unit) {
    if (recent.isEmpty()) { Text(stringResource(R.string.search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.search_recent), style = MaterialTheme.typography.titleMedium)
        TvChip(text = stringResource(R.string.search_clear_recent), icon = Icons.Filled.Delete, onClick = onClear)
    }
    LazyRow(state = rememberLazyListState(), contentPadding = PaddingValues(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(recent, key = { it }) { query -> TvChip(text = query, onClick = { onSelect(query) }) }
    }
}

@Composable
private fun SearchRows(
    state: SearchUiState,
    focusedKey: String?,
    restoreResultFocus: Boolean,
    requestInitialResultFocus: Boolean,
    onItemClick: (String) -> Unit,
    onYouTubeClick: (String) -> Unit,
    onSpotifyClick: (SpotifyBrowseItem) -> Unit,
    onRadioClick: (RadioStation) -> Unit,
    onTvheadendChannelClick: (TvhChannel) -> Unit,
    onTvheadendProgramClick: (TvhEpgEvent) -> Unit,
    onTvheadendRecordingClick: (TvhDvrEntry) -> Unit,
    onFocused: (MediaItem) -> Unit,
) {
    val plexGroups = listOf(
        R.string.search_movies to state.results.filter { it.type == "movie" },
        R.string.search_shows to state.results.filter { it.type == "show" },
        R.string.search_episodes to state.results.filter { it.type == "episode" },
        R.string.search_other to state.results.filter { it.type !in setOf("movie", "show", "episode") },
    ).filter { it.second.isNotEmpty() }
    val jellyfinGroups = listOf(
        R.string.search_movies to state.jellyfinResults.filter { it.type == "movie" },
        R.string.search_shows to state.jellyfinResults.filter { it.type == "show" },
        R.string.search_episodes to state.jellyfinResults.filter { it.type == "episode" },
        R.string.search_other to state.jellyfinResults.filter { it.type !in setOf("movie", "show", "episode") },
    ).filter { it.second.isNotEmpty() }
    val embyGroups = listOf(
        R.string.search_movies to state.embyResults.filter { it.type == "movie" },
        R.string.search_shows to state.embyResults.filter { it.type == "show" },
        R.string.search_episodes to state.embyResults.filter { it.type == "episode" },
        R.string.search_other to state.embyResults.filter { it.type !in setOf("movie", "show", "episode") },
    ).filter { it.second.isNotEmpty() }
    val trailerGroups = searchMediaGroups(state.trailerResults)
    val spotifyGroups = SpotifyEntityType.entries.mapNotNull { type ->
        state.spotifyResults.filter { it.type == type }.takeIf { it.isNotEmpty() }?.let { type to it }
    }
    val mediaTileStyle = MediaTileStyle.POSTER
    val firstMediaKey = plexGroups.firstOrNull()?.second?.firstOrNull()?.ratingKey
        ?: jellyfinGroups.firstOrNull()?.second?.firstOrNull()?.ratingKey
        ?: embyGroups.firstOrNull()?.second?.firstOrNull()?.ratingKey
        ?: trailerGroups.firstOrNull()?.second?.firstOrNull()?.ratingKey
    val firstExternalGroup = when {
        firstMediaKey != null -> null
        state.tvheadendResults.channels.isNotEmpty() -> "tvh-channels"
        state.tvheadendResults.programs.isNotEmpty() -> "tvh-programs"
        state.tvheadendResults.recordings.isNotEmpty() -> "tvh-recordings"
        state.youtubeResults.isNotEmpty() -> "youtube"
        state.spotifyResults.isNotEmpty() -> "spotify"
        state.radioResults.isNotEmpty() -> "radio"
        else -> null
    }
    val allMediaGroups = plexGroups + jellyfinGroups + embyGroups + trailerGroups
    val restoreGroupIndex = remember(allMediaGroups, focusedKey) {
        allMediaGroups.indexOfFirst { (_, items) -> focusedKey != null && items.any { focusedKey in it.allRatingKeys } }.coerceAtLeast(0)
    }
    val mediaGroupListIndices = buildList {
        var index = 1 // Result count.
        listOf(
            state.plexState to plexGroups.size,
            state.jellyfinState to jellyfinGroups.size,
            state.embyState to embyGroups.size,
            state.trailerState to trailerGroups.size,
        ).forEach { (sourceState, count) ->
            if (sourceState == SearchSourceState.LOADING || sourceState == SearchSourceState.ERROR || sourceState == SearchSourceState.PARTIAL) index++
            repeat(count) { add(index++) }
        }
    }
    val restoreListIndex = mediaGroupListIndices.getOrNull(restoreGroupIndex) ?: 0
    val rowsState = rememberLazyListState(initialFirstVisibleItemIndex = if (restoreResultFocus) restoreListIndex else 0)
    LaunchedEffect(focusedKey, allMediaGroups, restoreResultFocus, restoreListIndex) {
        if (restoreResultFocus && focusedKey != null && restoreGroupIndex in allMediaGroups.indices) {
            // Status rows are real lazy items too.
            rowsState.scrollToItem(restoreListIndex)
        }
    }
    LazyColumn(
        allowUpExit = true,
        state = rowsState,
        modifier = Modifier.fillMaxSize().clipToBounds(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
        // MediaTile reserves 8 dp below its visible surface. Together with this 8 dp gap,
        // grouped search rows use the same 16 dp visual separation as YouTube Home.
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text(stringResource(R.string.search_result_count, state.resultCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        sourceStatusItem("plex-status", MediaSystem.PLEX, state.plexState)
        itemsIndexed(plexGroups, key = { _, group -> "plex-${group.first}" }) { groupIndex, (titleRes, groupItems) ->
            Column(
                modifier = Modifier.bringFocusedContextIntoView(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SearchGroupTitle(MediaSystem.PLEX, titleRes)
                LazyRow(state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.containRowFocusExit()) {
                    itemsIndexed(groupItems, key = { _, it -> it.ratingKey }) { _, item ->
                        MediaTile(item = item, style = mediaTileStyle, showDescription = false, onClick = { onItemClick(item.ratingKey) }, onFocused = { onFocused(item) }, requestInitialFocus = (restoreResultFocus && item.ratingKey == focusedKey) || (requestInitialResultFocus && item.ratingKey == firstMediaKey))
                    }
                }
            }
        }
        sourceStatusItem("jellyfin-status", MediaSystem.JELLYFIN, state.jellyfinState)
        itemsIndexed(jellyfinGroups, key = { _, group -> "jellyfin-${group.first}" }) { groupIndex, (titleRes, groupItems) ->
            Column(modifier = Modifier.bringFocusedContextIntoView(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SearchGroupTitle(MediaSystem.JELLYFIN, titleRes)
                LazyRow(state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.containRowFocusExit()) {
                    itemsIndexed(groupItems, key = { _, it -> it.ratingKey }) { _, item ->
                        MediaTile(item = item, style = mediaTileStyle, showDescription = false, onClick = { onItemClick(item.ratingKey) }, onFocused = { onFocused(item) }, requestInitialFocus = (restoreResultFocus && item.ratingKey == focusedKey) || (requestInitialResultFocus && plexGroups.isEmpty() && item.ratingKey == firstMediaKey))
                    }
                }
            }
        }
        sourceStatusItem("emby-status", MediaSystem.EMBY, state.embyState)
        itemsIndexed(embyGroups, key = { _, group -> "emby-${group.first}" }) { groupIndex, (titleRes, groupItems) ->
            Column(modifier = Modifier.bringFocusedContextIntoView(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SearchGroupTitle(MediaSystem.EMBY, titleRes)
                LazyRow(state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.containRowFocusExit()) {
                    itemsIndexed(groupItems, key = { _, it -> it.ratingKey }) { _, item ->
                        MediaTile(item = item, style = mediaTileStyle, showDescription = false, onClick = { onItemClick(item.ratingKey) }, onFocused = { onFocused(item) }, requestInitialFocus = (restoreResultFocus && item.ratingKey == focusedKey) || (requestInitialResultFocus && plexGroups.isEmpty() && jellyfinGroups.isEmpty() && item.ratingKey == firstMediaKey))
                    }
                }
            }
        }
        sourceStatusItem("trailers-status", MediaSystem.TRAILERS, state.trailerState)
        itemsIndexed(trailerGroups, key = { _, group -> "trailers-${group.first}" }) { groupIndex, (titleRes, groupItems) ->
            Column(modifier = Modifier.bringFocusedContextIntoView(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SearchGroupTitle(MediaSystem.TRAILERS, titleRes)
                LazyRow(state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.containRowFocusExit()) {
                    itemsIndexed(groupItems, key = { _, it -> it.ratingKey }) { _, item ->
                        MediaTile(item = item, style = mediaTileStyle, showDescription = false, onClick = { onItemClick(item.ratingKey) },
                            onFocused = { onFocused(item) },
                            requestInitialFocus = (restoreResultFocus && item.ratingKey == focusedKey) ||
                                (requestInitialResultFocus && plexGroups.isEmpty() && jellyfinGroups.isEmpty() && embyGroups.isEmpty() && item.ratingKey == firstMediaKey))
                    }
                }
            }
        }
        sourceStatusItem("tvheadend-status", MediaSystem.TVHEADEND, state.tvheadendState, labelOverride = state.liveTvSources.values.map { it.name }.distinct().joinToString(", ").ifBlank { null })
        state.liveTvSources.values.forEach { source ->
            val channels = state.tvheadendResults.channels.filter { it.sourceId == source.id }
            val programs = state.tvheadendResults.programs.filter { it.sourceId == source.id }
            val recordings = state.tvheadendResults.recordings.filter { it.sourceId == source.id }
            val headers = state.liveTvHeaders[source.id].orEmpty()
            val system = MediaSystem.fromKey(source.system) ?: MediaSystem.TVHEADEND
            if (channels.isNotEmpty()) item(key = "tv-channels:${source.id}") {
                TvheadendChannelSearchRow(channels, headers, onTvheadendChannelClick, requestInitialResultFocus && firstExternalGroup == "tvh-channels" && channels.first() == state.tvheadendResults.channels.firstOrNull(), system, source.name)
            }
            if (programs.isNotEmpty()) item(key = "tv-programs:${source.id}") {
                TvheadendProgramSearchRow(programs, headers, onTvheadendProgramClick, requestInitialResultFocus && firstExternalGroup == "tvh-programs" && programs.first() == state.tvheadendResults.programs.firstOrNull(), system, source.name)
            }
            if (recordings.isNotEmpty()) item(key = "tv-recordings:${source.id}") {
                TvheadendRecordingSearchRow(recordings, headers, onTvheadendRecordingClick, requestInitialResultFocus && firstExternalGroup == "tvh-recordings" && recordings.first() == state.tvheadendResults.recordings.firstOrNull(), system, source.name)
            }
        }
        sourceStatusItem("youtube-status", MediaSystem.YOUTUBE, state.youtubeState)
        if (state.youtubeResults.isNotEmpty()) {
            item(key = "youtube-results") { YouTubeSearchRow(state.youtubeResults, onYouTubeClick, requestInitialResultFocus && firstExternalGroup == "youtube") }
        }
        sourceStatusItem("spotify-status", MediaSystem.SPOTIFY, state.spotifyState)
        items(spotifyGroups, key = { "spotify-${it.first}" }) { (type, items) ->
            SpotifySearchRow(type, items, onSpotifyClick, requestInitialResultFocus && firstExternalGroup == "spotify" && type == spotifyGroups.firstOrNull()?.first)
        }
        sourceStatusItem("radio-status", MediaSystem.RADIO, state.radioState)
        if (state.radioResults.isNotEmpty()) {
            item(key = "radio-results") {
                RadioSearchRow(state.radioResults, onRadioClick, requestInitialResultFocus && firstExternalGroup == "radio")
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sourceStatusItem(
    key: String,
    system: MediaSystem,
    state: SearchSourceState,
    labelRes: Int? = null,
    labelOverride: String? = null,
) {
    when (state) {
        SearchSourceState.LOADING -> item(key = key) {
            SkeletonRow(
                title = stringResource(R.string.search_source_loading, labelOverride ?: labelRes?.let { stringResource(it) } ?: searchSystemName(system)),
                style = MediaTileStyle.POSTER,
                itemCount = 4,
            )
        }
        SearchSourceState.ERROR, SearchSourceState.PARTIAL -> item(key = key) {
            Text(
                stringResource(if (state == SearchSourceState.PARTIAL) R.string.search_source_partial else R.string.search_source_failed, labelOverride ?: labelRes?.let { stringResource(it) } ?: searchSystemName(system)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        else -> Unit
    }
}

/** Search groups its results under the provider name (TVHeadend, not "Live TV") unless the system was renamed. */
@Composable
private fun searchSystemName(system: MediaSystem): String = systemName(system, system.brand ?: stringResource(system.label))

@Composable
private fun SearchGroupTitle(system: MediaSystem, groupRes: Int) {
    ContentSectionHeader(
        title = stringResource(R.string.search_system_group, searchSystemName(system), stringResource(groupRes)),
        startPadding = 0.dp,
        bottomPadding = 0.dp,
    )
}


@Composable
private fun YouTubeSearchRow(youtubeItems: List<YouTubeItem>, onClick: (String) -> Unit, requestInitialFocus: Boolean = false) {
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchGroupTitle(MediaSystem.YOUTUBE, R.string.search_videos)
        LazyRow(state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.containRowFocusExit()) {
            items(youtubeItems, key = { it.videoId }) { item ->
                var focused by remember(item.videoId) { mutableStateOf(false) }
                val cardShape = RoundedCornerShape(10.dp)
                Surface(onClick = { onClick(item.videoId) }, colors = transparentSurfaceColors(), scale = noSurfaceScale(), shape = ClickableSurfaceDefaults.shape(shape = cardShape), modifier = initialFocusModifier(requestInitialFocus && item == youtubeItems.firstOrNull()).width(250.dp).mediaFocusMotion(focused).activateOnTvKey { onClick(item.videoId) }.onFocusChanged { focused = it.isFocused }) {
                    Column(
                        Modifier.background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), cardShape)
                            .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f), cardShape)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        ArtworkImage(model = item.imageUrl, title = item.title, modifier = Modifier.fillMaxWidth().aspectRatio(if (squareArtwork) 1f else 16f / 9f).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                        CenteredMarqueeTitle(item.title, focused = focused, style = MaterialTheme.typography.titleSmall)
                        item.author?.let {
                            CenteredMarqueeTitle(
                                it,
                                focused = focused,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioSearchRow(
    items: List<RadioStation>,
    onClick: (RadioStation) -> Unit,
    requestInitialFocus: Boolean = false,
) {
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchGroupTitle(MediaSystem.RADIO, R.string.radio_title)
        LazyRow(
            state = rememberLazyListState(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.containRowFocusExit(),
        ) {
            items(items, key = { it.id }) { station ->
                SearchArtworkCard(
                    imageUrl = station.artworkUrl,
                    title = station.name,
                    subtitle = radioStationSubtitle(station),
                    squareArtwork = true,
                    onClick = { onClick(station) },
                    requestInitialFocus = requestInitialFocus && station == items.firstOrNull(),
                )
            }
        }
    }
}

@Composable
private fun SpotifySearchRow(type: SpotifyEntityType, items: List<SpotifyBrowseItem>, onClick: (SpotifyBrowseItem) -> Unit, requestInitialFocus: Boolean = false) {
    val titleRes = spotifyTypeTitleRes(type)
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchGroupTitle(MediaSystem.SPOTIFY, titleRes)
        LazyRow(state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.containRowFocusExit()) {
            items(items, key = { "${it.type}:${it.id}" }) { item ->
                var focused by remember(item.type, item.id) { mutableStateOf(false) }
                val cardShape = RoundedCornerShape(10.dp)
                Surface(onClick = { onClick(item) }, colors = transparentSurfaceColors(), scale = noSurfaceScale(), shape = ClickableSurfaceDefaults.shape(shape = cardShape), modifier = initialFocusModifier(requestInitialFocus && item == items.firstOrNull()).width(220.dp).mediaFocusMotion(focused).activateOnTvKey { onClick(item) }.onFocusChanged { focused = it.isFocused }) {
                    Column(
                        Modifier.background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), cardShape)
                            .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f), cardShape)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        ArtworkImage(model = item.artworkUrl, title = item.title, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                        CenteredMarqueeTitle(item.title, focused = focused, style = MaterialTheme.typography.titleSmall)
                        item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvheadendChannelSearchRow(
    channels: List<TvhChannel>,
    imageHeaders: Map<String, String>,
    onClick: (TvhChannel) -> Unit,
    requestInitialFocus: Boolean = false,
    system: MediaSystem = MediaSystem.TVHEADEND,
    sourceName: String = "",
) {
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(sourceName, style = MaterialTheme.typography.labelMedium)
        SearchGroupTitle(system, R.string.search_channels)
        LazyRow(state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.containRowFocusExit()) {
            items(channels, key = { it.uuid }) { channel ->
                SearchArtworkCard(
                    imageUrl = channel.iconUrl,
                    title = channel.name,
                    subtitle = channel.numberLabel,
                    imageHeaders = imageHeaders,
                    onClick = { onClick(channel) },
                    requestInitialFocus = requestInitialFocus && channel == channels.firstOrNull(),
                )
            }
        }
    }
}

@Composable
private fun TvheadendProgramSearchRow(
    programs: List<TvhEpgEvent>,
    imageHeaders: Map<String, String>,
    onClick: (TvhEpgEvent) -> Unit,
    requestInitialFocus: Boolean = false,
    system: MediaSystem = MediaSystem.TVHEADEND,
    sourceName: String = "",
) {
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(sourceName, style = MaterialTheme.typography.labelMedium)
        SearchGroupTitle(system, R.string.tvh_guide)
        LazyRow(state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.containRowFocusExit()) {
            items(programs, key = { it.eventId }) { event ->
                SearchArtworkCard(
                    imageUrl = event.imageUrl,
                    title = event.title,
                    subtitle = "${event.channelName} · ${formatSearchDateTime(event.startEpochSeconds)}",
                    imageHeaders = imageHeaders,
                    onClick = { onClick(event) },
                    requestInitialFocus = requestInitialFocus && event == programs.firstOrNull(),
                )
            }
        }
    }
}

@Composable
private fun TvheadendRecordingSearchRow(
    recordings: List<TvhDvrEntry>,
    imageHeaders: Map<String, String>,
    onClick: (TvhDvrEntry) -> Unit,
    requestInitialFocus: Boolean = false,
    system: MediaSystem = MediaSystem.TVHEADEND,
    sourceName: String = "",
) {
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(sourceName, style = MaterialTheme.typography.labelMedium)
        SearchGroupTitle(system, R.string.tvh_recordings)
        LazyRow(state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.containRowFocusExit()) {
            items(recordings, key = { it.uuid }) { recording ->
                SearchArtworkCard(
                    imageUrl = recording.imageUrl ?: recording.fanartUrl,
                    title = recording.title,
                    subtitle = "${recording.channelName} · ${formatSearchDateTime(recording.startEpochSeconds)}",
                    imageHeaders = imageHeaders,
                    onClick = { onClick(recording) },
                    requestInitialFocus = requestInitialFocus && recording == recordings.firstOrNull(),
                )
            }
        }
    }
}

@Composable
private fun SearchArtworkCard(
    imageUrl: String?,
    title: String,
    subtitle: String?,
    imageHeaders: Map<String, String> = emptyMap(),
    squareArtwork: Boolean = false,
    onClick: () -> Unit,
    requestInitialFocus: Boolean = false,
) {
    var focused by remember(title, imageUrl) { mutableStateOf(false) }
    val cardShape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape = cardShape),
        modifier = initialFocusModifier(requestInitialFocus).width(250.dp).mediaFocusMotion(focused).activateOnTvKey(onClick = onClick).onFocusChanged { focused = it.isFocused },
    ) {
        Column(
            Modifier.background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), cardShape)
                .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f), cardShape)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            ArtworkImage(
                model = imageUrl,
                title = title,
                headers = imageHeaders,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop,
            )
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            subtitle?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SearchList(
    state: SearchUiState,
    focusedKey: String?,
    restoreResultFocus: Boolean,
    requestInitialResultFocus: Boolean,
    onItemClick: (String) -> Unit,
    onYouTubeClick: (String) -> Unit,
    onSpotifyClick: (SpotifyBrowseItem) -> Unit,
    onRadioClick: (RadioStation) -> Unit,
    onTvheadendChannelClick: (TvhChannel) -> Unit,
    onTvheadendProgramClick: (TvhEpgEvent) -> Unit,
    onTvheadendRecordingClick: (TvhDvrEntry) -> Unit,
    onFocused: (MediaItem) -> Unit,
) {
    val plexGroups = searchMediaGroups(state.results)
    val jellyfinGroups = searchMediaGroups(state.jellyfinResults)
    val embyGroups = searchMediaGroups(state.embyResults)
    val trailerGroups = searchMediaGroups(state.trailerResults)
    val spotifyGroups = SpotifyEntityType.entries.mapNotNull { type ->
        state.spotifyResults.filter { it.type == type }.takeIf { it.isNotEmpty() }?.let { type to it }
    }
    val firstMediaKey = plexGroups.firstOrNull()?.second?.firstOrNull()?.ratingKey
        ?: jellyfinGroups.firstOrNull()?.second?.firstOrNull()?.ratingKey
        ?: embyGroups.firstOrNull()?.second?.firstOrNull()?.ratingKey
        ?: trailerGroups.firstOrNull()?.second?.firstOrNull()?.ratingKey
    val requestedMediaKey = focusedKey.takeIf { restoreResultFocus } ?: firstMediaKey.takeIf { requestInitialResultFocus }
    val firstExternalGroup = when {
        firstMediaKey != null -> null
        state.tvheadendResults.channels.isNotEmpty() -> "tvh-channels"
        state.tvheadendResults.programs.isNotEmpty() -> "tvh-programs"
        state.tvheadendResults.recordings.isNotEmpty() -> "tvh-recordings"
        state.youtubeResults.isNotEmpty() -> "youtube"
        spotifyGroups.isNotEmpty() -> "spotify"
        state.radioResults.isNotEmpty() -> "radio"
        else -> null
    }

    val listState = rememberLazyListState()
    LazyColumn(
        allowUpExit = true,
        state = listState,
        modifier = Modifier.overlayLazyScrollbar(listState),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        sourceStatusItem("plex-list-status", MediaSystem.PLEX, state.plexState)
        plexGroups.forEach { (groupRes, groupItems) ->
            item(key = "plex-list-header:$groupRes") { SearchListGroupHeader(MediaSystem.PLEX, groupRes) }
            items(groupItems, key = { "plex-media:${it.ratingKey}" }) { item ->
                SearchMediaListItem(
                    item = item,
                    onClick = { onItemClick(item.ratingKey) },
                    onFocused = { onFocused(item) },
                    requestInitialFocus = item.ratingKey == requestedMediaKey,
                )
            }
        }

        sourceStatusItem("jellyfin-list-status", MediaSystem.JELLYFIN, state.jellyfinState)
        jellyfinGroups.forEach { (groupRes, groupItems) ->
            item(key = "jellyfin-list-header:$groupRes") { SearchListGroupHeader(MediaSystem.JELLYFIN, groupRes) }
            items(groupItems, key = { "jellyfin-media:${it.ratingKey}" }) { item ->
                SearchMediaListItem(
                    item = item,
                    onClick = { onItemClick(item.ratingKey) },
                    onFocused = { onFocused(item) },
                    requestInitialFocus = item.ratingKey == requestedMediaKey,
                )
            }
        }

        sourceStatusItem("emby-list-status", MediaSystem.EMBY, state.embyState)
        embyGroups.forEach { (groupRes, groupItems) ->
            item(key = "emby-list-header:$groupRes") { SearchListGroupHeader(MediaSystem.EMBY, groupRes) }
            items(groupItems, key = { "emby-media:${it.ratingKey}" }) { item ->
                SearchMediaListItem(
                    item = item,
                    onClick = { onItemClick(item.ratingKey) },
                    onFocused = { onFocused(item) },
                    requestInitialFocus = item.ratingKey == requestedMediaKey,
                )
            }
        }

        sourceStatusItem("trailers-list-status", MediaSystem.TRAILERS, state.trailerState)
        trailerGroups.forEach { (groupRes, groupItems) ->
            item(key = "trailers-list-header:$groupRes") { SearchListGroupHeader(MediaSystem.TRAILERS, groupRes) }
            items(groupItems, key = { "trailers-media:${it.ratingKey}" }) { item ->
                SearchMediaListItem(
                    item = item,
                    onClick = { onItemClick(item.ratingKey) },
                    onFocused = { onFocused(item) },
                    requestInitialFocus = item.ratingKey == requestedMediaKey,
                )
            }
        }

        sourceStatusItem("tvheadend-list-status", MediaSystem.TVHEADEND, state.tvheadendState, labelOverride = state.liveTvSources.values.map { it.name }.distinct().joinToString(", ").ifBlank { null })
        state.liveTvSources.values.forEach { source ->
            val channels = state.tvheadendResults.channels.filter { it.sourceId == source.id }
            val programs = state.tvheadendResults.programs.filter { it.sourceId == source.id }
            val recordings = state.tvheadendResults.recordings.filter { it.sourceId == source.id }
            val system = MediaSystem.fromKey(source.system) ?: MediaSystem.TVHEADEND
        if (channels.isNotEmpty()) {
            item(key = "tv-channel-header:${source.id}") { SearchListGroupHeader(system, R.string.search_channels) }
            items(channels, key = { "tvh-channel:${it.sourceId}:${it.uuid}" }) { channel ->
                ExternalSearchListItem(
                    imageUrl = channel.iconUrl,
                    title = channel.name,
                    subtitle = state.liveTvSources[channel.sourceId]?.name,
                    imageHeaders = state.liveTvHeaders[channel.sourceId].orEmpty(),
                    onClick = { onTvheadendChannelClick(channel) },
                    requestInitialFocus = requestInitialResultFocus && firstExternalGroup == "tvh-channels" && channel == state.tvheadendResults.channels.firstOrNull(),
                )
            }
        }
        if (programs.isNotEmpty()) {
            item(key = "tv-program-header:${source.id}") { SearchListGroupHeader(system, R.string.tvh_guide) }
            items(programs, key = { "tvh-program:${it.sourceId}:${it.eventId}" }) { event ->
                ExternalSearchListItem(
                    imageUrl = event.imageUrl,
                    title = event.title,
                    subtitle = "${state.liveTvSources[event.sourceId]?.name.orEmpty()} · " + stringResource(R.string.search_program_meta, event.channelName, formatSearchDateTime(event.startEpochSeconds)),
                    imageHeaders = state.liveTvHeaders[event.sourceId].orEmpty(),
                    onClick = { onTvheadendProgramClick(event) },
                    requestInitialFocus = requestInitialResultFocus && firstExternalGroup == "tvh-programs" && event == state.tvheadendResults.programs.firstOrNull(),
                )
            }
        }
        if (recordings.isNotEmpty()) {
            item(key = "tv-recording-header:${source.id}") { SearchListGroupHeader(system, R.string.tvh_recordings) }
            items(recordings, key = { "tvh-recording:${it.sourceId}:${it.uuid}" }) { recording ->
                ExternalSearchListItem(
                    imageUrl = recording.imageUrl ?: recording.fanartUrl,
                    title = recording.title,
                    subtitle = "${state.liveTvSources[recording.sourceId]?.name.orEmpty()} · " + stringResource(R.string.search_recording_meta, recording.channelName, formatSearchDateTime(recording.startEpochSeconds)),
                    imageHeaders = state.liveTvHeaders[recording.sourceId].orEmpty(),
                    onClick = { onTvheadendRecordingClick(recording) },
                    requestInitialFocus = requestInitialResultFocus && firstExternalGroup == "tvh-recordings" && recording == state.tvheadendResults.recordings.firstOrNull(),
                )
            }
        }

        }
        sourceStatusItem("youtube-list-status", MediaSystem.YOUTUBE, state.youtubeState)
        if (state.youtubeResults.isNotEmpty()) {
            item(key = "youtube-list-header") { SearchListGroupHeader(MediaSystem.YOUTUBE, R.string.search_videos) }
            items(state.youtubeResults, key = { "youtube:${it.videoId}" }) { item ->
                ExternalSearchListItem(
                    imageUrl = item.imageUrl,
                    title = item.title,
                    subtitle = item.author ?: stringResource(R.string.search_videos),
                    onClick = { onYouTubeClick(item.videoId) },
                    requestInitialFocus = requestInitialResultFocus && firstExternalGroup == "youtube" && item == state.youtubeResults.firstOrNull(),
                )
            }
        }

        sourceStatusItem("spotify-list-status", MediaSystem.SPOTIFY, state.spotifyState)
        spotifyGroups.forEachIndexed { groupIndex, (type, groupItems) ->
            val titleRes = spotifyTypeTitleRes(type)
            item(key = "spotify-list-header:$type") { SearchListGroupHeader(MediaSystem.SPOTIFY, titleRes) }
            items(groupItems, key = { "spotify:${it.type}:${it.id}" }) { item ->
                ExternalSearchListItem(
                    imageUrl = item.artworkUrl,
                    title = item.title,
                    subtitle = item.subtitle,
                    squareArtwork = true,
                    onClick = { onSpotifyClick(item) },
                    requestInitialFocus = requestInitialResultFocus && firstExternalGroup == "spotify" && groupIndex == 0 && item == groupItems.firstOrNull(),
                )
            }
        }
        sourceStatusItem("radio-list-status", MediaSystem.RADIO, state.radioState)
        if (state.radioResults.isNotEmpty()) {
            item(key = "radio-list-header") { SearchListGroupHeader(MediaSystem.RADIO, R.string.radio_title) }
            items(state.radioResults, key = { "radio:${it.id}" }) { station ->
                ExternalSearchListItem(
                    imageUrl = station.artworkUrl,
                    title = station.name,
                    subtitle = radioStationSubtitle(station),
                    squareArtwork = true,
                    onClick = { onRadioClick(station) },
                    requestInitialFocus = requestInitialResultFocus && firstExternalGroup == "radio" && station == state.radioResults.firstOrNull(),
                )
            }
        }
    }
}

private fun searchMediaGroups(items: List<MediaItem>): List<Pair<Int, List<MediaItem>>> = listOf(
    R.string.search_movies to items.filter { it.type == "movie" },
    R.string.search_shows to items.filter { it.type == "show" },
    R.string.search_episodes to items.filter { it.type == "episode" },
    R.string.search_other to items.filter { it.type !in setOf("movie", "show", "episode") },
).filter { it.second.isNotEmpty() }

private fun spotifyTypeTitleRes(type: SpotifyEntityType): Int = when (type) {
    SpotifyEntityType.TRACK -> R.string.search_tracks
    SpotifyEntityType.ALBUM -> R.string.nav_spotify_albums
    SpotifyEntityType.ARTIST -> R.string.nav_spotify_artists
    SpotifyEntityType.PLAYLIST -> R.string.nav_spotify_playlists
}

@Composable
private fun SearchListGroupHeader(system: MediaSystem, groupRes: Int) {
    ContentSectionHeader(
        title = stringResource(R.string.search_system_group, searchSystemName(system), stringResource(groupRes)),
        modifier = Modifier.padding(top = 12.dp),
        startPadding = 0.dp,
        bottomPadding = 2.dp,
    )
}

@Composable
private fun SearchMediaListItem(
    item: MediaItem,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    requestInitialFocus: Boolean,
) {
    var isFocused by remember(item.ratingKey) { mutableStateOf(false) }
    val rowShape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape = rowShape),
        modifier = initialFocusModifier(requestInitialFocus)
            .fillMaxWidth()
            .mediaFocusMotion(isFocused, focusedScale = 1.012f)
            .activateOnTvKey(onClick = onClick)
            .bringFocusedContextIntoView()
            .onFocusChanged { isFocused = it.isFocused; if (it.isFocused) onFocused() },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.58f), rowShape)
                .border(if (isFocused) 1.5.dp else 1.dp, if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.06f), rowShape)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaArtworkWithStreamIcons(
                item = item,
                model = item.landscapeArtUrl,
                modifier = Modifier.width(166.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                MarqueeTitle(item.title, focused = isFocused, style = MaterialTheme.typography.titleMedium)
                val meta = buildList { item.year?.let { add(it.toString()) }; formatDuration(item.durationMs)?.let(::add) }.joinToString(" · ")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    item.contentRating?.takeIf { it.isNotBlank() }?.let { ContentRatingBadge(it) }
                }
            }
        }
    }
}

@Composable
private fun ExternalSearchListItem(
    imageUrl: String?,
    title: String,
    subtitle: String?,
    imageHeaders: Map<String, String> = emptyMap(),
    squareArtwork: Boolean = false,
    onClick: () -> Unit,
    requestInitialFocus: Boolean = false,
) {
    var focused by remember(title, imageUrl) { mutableStateOf(false) }
    val rowShape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape = rowShape),
        modifier = initialFocusModifier(requestInitialFocus).fillMaxWidth().mediaFocusMotion(focused, focusedScale = 1.012f).activateOnTvKey(onClick = onClick).bringFocusedContextIntoView().onFocusChanged { focused = it.isFocused },
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.58f), rowShape)
                .border(if (focused) 1.5.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.06f), rowShape)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkImage(
                model = imageUrl,
                title = title,
                headers = imageHeaders,
                modifier = if (squareArtwork) {
                    Modifier.width(76.dp).aspectRatio(1f).clip(RoundedCornerShape(9.dp))
                } else {
                    Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(9.dp))
                },
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                MarqueeTitle(title, focused = focused, style = MaterialTheme.typography.titleMedium)
                subtitle?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun initialFocusModifier(requestInitialFocus: Boolean): Modifier {
    if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) return Modifier

    val requester = remember { FocusRequester() }
    val filterFocus = LocalFilterFocusState.current
    // View changes remount the results behind the panel. Their old automatic focus
    // requests are consumed while toolbar controls own focus.
    LaunchedEffect(requestInitialFocus, filterFocus) {
        if (requestInitialFocus && filterFocus?.allowContentAutoFocus != false) {
            delay(120)
            repeat(4) {
                if (filterFocus?.allowContentAutoFocus == false) return@LaunchedEffect
                if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                delay(32)
            }
        }
    }
    return if (requestInitialFocus) Modifier.focusRequester(requester) else Modifier
}

@Composable
private fun spotifyTypeLabel(type: SpotifyEntityType): String = stringResource(
    when (type) {
        SpotifyEntityType.TRACK -> R.string.search_tracks
        SpotifyEntityType.ALBUM -> R.string.nav_spotify_albums
        SpotifyEntityType.ARTIST -> R.string.nav_spotify_artists
        SpotifyEntityType.PLAYLIST -> R.string.nav_spotify_playlists
    },
)

private val searchDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE dd.MM. HH:mm")
    .withZone(ZoneId.systemDefault())

private fun formatSearchDateTime(epochSeconds: Long): String = searchDateTimeFormatter.format(Instant.ofEpochSecond(epochSeconds))
