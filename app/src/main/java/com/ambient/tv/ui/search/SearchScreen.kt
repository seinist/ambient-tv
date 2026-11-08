package com.ambient.tv.ui.search

import com.ambient.tv.ui.theme.animatedFocusSurface
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
import com.ambient.tv.ui.common.rememberBrowseRowState
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
import com.ambient.tv.ui.common.BrowseFocusContentIdentity
import com.ambient.tv.ui.common.BrowseFocusInitialTarget
import com.ambient.tv.ui.common.BrowseFocusHoldEntry
import com.ambient.tv.ui.common.BrowseFocusKey
import com.ambient.tv.ui.common.BrowseFocusSlot
import com.ambient.tv.ui.common.BrowseFocusRow
import com.ambient.tv.ui.common.RegisterBrowseFocusSlots
import com.ambient.tv.ui.common.browseFocusTarget
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
import com.ambient.tv.ui.common.skeletonPulseAlpha
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.LocalFilterFocusState
import com.ambient.tv.ui.common.ViewOption
import com.ambient.tv.ui.common.ViewOptionsChip
import com.ambient.tv.ui.common.ViewOptionsPanel
import com.ambient.tv.ui.common.ViewPreview
import com.ambient.tv.ui.common.fillWindowHeight
import com.ambient.tv.ui.common.rememberFilterFocusState
import com.ambient.tv.ui.common.pageContentUp
import com.ambient.tv.ui.common.pageFocusStage
import com.ambient.tv.ui.common.PageFocusLevel
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
import com.ambient.tv.ui.common.RowColumnFocus
import com.ambient.tv.ui.common.rememberRowColumnFocus
import com.ambient.tv.ui.common.RegisterRowColumnFocus
import com.ambient.tv.ui.common.rowColumnFocusTarget
import com.ambient.tv.ui.common.rowColumnFocusWrapRight
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LocalSearchResultFocused = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Unit> { {} }
private val LocalSearchInitialResultKey = androidx.compose.runtime.staticCompositionLocalOf<String?> { null }

@Composable
fun SearchScreen(
    onItemClick: (String) -> Unit,
    onYouTubeClick: (String) -> Unit,
    onSpotifyClick: (SpotifyBrowseItem) -> Unit,
    onTvheadendChannelClick: (TvhChannel) -> Unit,
    onTvheadendProgramClick: (TvhEpgEvent) -> Unit,
    onTvheadendRecordingClick: (TvhDvrEntry) -> Unit,
    onRadioStarted: () -> Unit = {},
    /** Root Back: focus sidebar like Home/Files/YT. */
    onBack: (() -> Unit)? = null,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val storedState by viewModel.uiState.collectAsState()
    val externalQuery by com.ambient.tv.search.ExternalSearch.requests.collectAsState()
    val navigationReturn = com.ambient.tv.ui.common.rememberNavigationPlaybackReturn()
    // Admit only the snapshot present on entry. A later query/provider response cannot turn an
    // unavailable old player item into a new focus request, or override an external search.
    val playbackReturn = remember(navigationReturn) {
        navigationReturn.takeIf { externalQuery == null && prepareSearchPlaybackReturn(storedState, it) != null }
    }
    val preparedReturn = prepareSearchPlaybackReturn(storedState, playbackReturn)
    val uiState = preparedReturn?.state ?: storedState
    val filterFocusState = rememberFilterFocusState(viewModel)
    // Capture once for this content identity. Ordinary focus only updates session memory.
    val resultFocus = remember(viewModel, uiState.query, uiState.selectedSources, uiState.viewMode,
        uiState.resultResetRevision, uiState.playbackReturnRevision) {
        preparedReturn?.let { SearchFocusRequest(it.target.item as String, initialLayoutPrepared = true) }
            ?: viewModel.resultFocusRequest()
    }
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
        if (!consumeExternalQuery()) viewModel.refreshAfterReconnect(playbackReturn)
    }
    LaunchedEffect(externalQuery, entryReady) {
        if (entryReady) consumeExternalQuery()
    }
    if (onBack != null) {
        BackHandler(enabled = !showOptions) { onBack() }
    }
    CompositionLocalProvider(LocalFilterFocusState provides filterFocusState,
        LocalSearchResultFocused provides viewModel::onResultFocused,
        LocalSearchInitialResultKey provides resultFocus.itemKey) {
        BrowseFocusContentIdentity(listOf(uiState.query, uiState.selectedSources, uiState.viewMode,
            uiState.resultResetRevision, uiState.playbackReturnRevision), restoreInitialTarget = true)
        val returnToken = playbackReturn?.revision?.takeIf { it == uiState.playbackReturnRevision }
        val returnTarget = preparedReturn?.target ?: searchResultFocusAddress(uiState, resultFocus.itemKey)
        if (returnToken != null) BrowseFocusInitialTarget(returnTarget?.item, returnTarget?.row)
        val playbackFocus = com.ambient.tv.ui.common.rememberBrowsePlaybackReturn(
            token = returnToken,
            target = returnTarget,
            enabled = !showOptions,
        )
        Box(Modifier.fillMaxSize()) {
            val focusChain = com.ambient.tv.ui.common.rememberPageFocusChain()
            val queryStageFocus = remember { FocusRequester() }
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
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                        .pageFocusStage(focusChain, PageFocusLevel.SEARCH_TOP, queryStageFocus),
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
                    .then(playbackFocus.modifier)
                    .pageContentUp(focusChain) { if (uiState.availableSources.isEmpty()) queryFocus else focusChain.contentUp }
                    .onFocusChanged {
                        if (it.hasFocus) {
                            resultFocus.contentFocused()
                            if (!showOptions) filterFocusState.enterContent()
                        }
                    }
                    .focusGroup()) {
                    androidx.compose.runtime.key(uiState.playbackReturnRevision) {
                    when {
                        !uiState.hasSearched -> RecentSearches(uiState.recentSearches, viewModel::useRecentSearch, viewModel::clearRecentSearches)
                        uiState.hasSearched && !uiState.hasResults && !uiState.isSearching ->
                            com.ambient.tv.ui.common.BrowseStatusPanel(
                                title = stringResource(R.string.search_no_results),
                                body = if (listOf(uiState.plexState, uiState.jellyfinState, uiState.embyState, uiState.trailerState, uiState.telegramState, uiState.youtubeState, uiState.spotifyState, uiState.radioState, uiState.tvheadendState).any { it == SearchSourceState.ERROR || it == SearchSourceState.PARTIAL }) {
                                    stringResource(R.string.search_no_results_partial_error)
                                } else stringResource(R.string.search_no_results_body),
                            )
                        else -> SoftViewChange(uiState.viewMode, Modifier.fillMaxSize()) {
                            if (uiState.viewMode == SearchViewMode.ROWS) SearchRows(
                                uiState,
                                resultFocus,
                                onItemClick,
                                onYouTubeClick,
                                onSpotifyClick,
                                { station -> viewModel.playRadio(station, onRadioStarted) },
                                onTvheadendChannelClick,
                                onTvheadendProgramClick,
                                onTvheadendRecordingClick,
                                viewModel::onItemFocused,
                            ) else SearchList(
                                uiState,
                                resultFocus,
                                onItemClick,
                                onYouTubeClick,
                                onSpotifyClick,
                                { station -> viewModel.playRadio(station, onRadioStarted) },
                                onTvheadendChannelClick,
                                onTvheadendProgramClick,
                                onTvheadendRecordingClick,
                                viewModel::onItemFocused,
                            )
                        }
                    }
                    }
                }
            }
            if (showOptions && uiState.hasResults) {
                com.ambient.tv.ui.common.RightPanelOverlay(onOutsideTap = { showOptions = false }) {
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
}

@Composable
private fun searchSourceLabel(source: SearchSource): String = when (source) {
    SearchSource.PLEX -> searchSystemName(MediaSystem.PLEX)
    SearchSource.JELLYFIN -> searchSystemName(MediaSystem.JELLYFIN)
    SearchSource.EMBY -> searchSystemName(MediaSystem.EMBY)
    SearchSource.LIVE_TV -> stringResource(R.string.tvh_live)
    SearchSource.TRAILERS -> searchSystemName(MediaSystem.TRAILERS)
    SearchSource.TELEGRAM -> searchSystemName(MediaSystem.TELEGRAM)
    SearchSource.YOUTUBE -> searchSystemName(MediaSystem.YOUTUBE)
    SearchSource.SPOTIFY -> searchSystemName(MediaSystem.SPOTIFY)
    SearchSource.RADIO -> searchSystemName(MediaSystem.RADIO)
}

@Composable
private fun RecentSearches(recent: List<String>, onSelect: (String) -> Unit, onClear: () -> Unit) {
    if (recent.isEmpty()) { Text(stringResource(R.string.search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    // Its own column: inside the page's Box, heading and chips were drawn on top of each other.
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.search_recent), style = MaterialTheme.typography.titleMedium)
            TvChip(text = stringResource(R.string.search_clear_recent), icon = Icons.Filled.Delete, onClick = onClear)
        }
        LazyRow(state = rememberBrowseRowState(), modifier = Modifier.containRowFocusExit(), contentPadding = PaddingValues(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(recent, key = { it }) { query -> TvChip(text = query, onClick = { onSelect(query) }) }
        }
    }
}

@Composable
private fun SearchRows(
    state: SearchUiState,
    resultFocus: SearchFocusRequest,
    onItemClick: (String) -> Unit,
    onYouTubeClick: (String) -> Unit,
    onSpotifyClick: (SpotifyBrowseItem) -> Unit,
    onRadioClick: (RadioStation) -> Unit,
    onTvheadendChannelClick: (TvhChannel) -> Unit,
    onTvheadendProgramClick: (TvhEpgEvent) -> Unit,
    onTvheadendRecordingClick: (TvhDvrEntry) -> Unit,
    onFocused: (MediaItem) -> Unit,
) {
    val plexGroups = remember(state.results) { searchMediaGroups(state.results) }
    val jellyfinGroups = remember(state.jellyfinResults) { searchMediaGroups(state.jellyfinResults) }
    val embyGroups = remember(state.embyResults) { searchMediaGroups(state.embyResults) }
    val trailerGroups = remember(state.trailerResults) { searchMediaGroups(state.trailerResults) }
    val telegramGroups = remember(state.telegramResults) { telegramSearchGroups(state.telegramResults) }
    val spotifyGroups = remember(state.spotifyResults) {
        SpotifyEntityType.entries.mapNotNull { type ->
            state.spotifyResults.filter { it.type == type }.takeIf { it.isNotEmpty() }?.let { type to it }
        }
    }
    val channelsBySource = remember(state.tvheadendResults.channels) { state.tvheadendResults.channels.groupBy { it.sourceId } }
    val programsBySource = remember(state.tvheadendResults.programs) { state.tvheadendResults.programs.groupBy { it.sourceId } }
    val recordingsBySource = remember(state.tvheadendResults.recordings) { state.tvheadendResults.recordings.groupBy { it.sourceId } }
    val mediaTileStyle = MediaTileStyle.POSTER
    val firstMediaKey = plexGroups.firstOrNull()?.second?.firstOrNull()?.ratingKey
        ?: jellyfinGroups.firstOrNull()?.second?.firstOrNull()?.ratingKey
        ?: embyGroups.firstOrNull()?.second?.firstOrNull()?.ratingKey
        ?: trailerGroups.firstOrNull()?.second?.firstOrNull()?.ratingKey
        ?: telegramGroups.firstOrNull()?.second?.firstOrNull()?.ratingKey
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
    // The outer list spans all provider rows. It can locate the first remaining result even
    // when the row holding focus disappears and its nested LazyRow is no longer composed.
    val focusSlots = remember(
        plexGroups, jellyfinGroups, embyGroups, trailerGroups, telegramGroups, spotifyGroups,
        channelsBySource, programsBySource, recordingsBySource, state.liveTvSources,
        state.youtubeResults, state.radioResults,
        state.plexState, state.jellyfinState, state.embyState, state.trailerState, state.telegramState,
        state.tvheadendState, state.youtubeState, state.spotifyState, state.radioState,
    ) { buildList {
        var index = 1 // Result count.
        fun status(source: SearchSourceState) {
            if (source == SearchSourceState.LOADING || source == SearchSourceState.ERROR ||
                source == SearchSourceState.PARTIAL) index++
        }
        fun mediaGroups(groups: List<Pair<Int, List<MediaItem>>>, provider: String) {
            groups.forEach { (category, items) ->
                items.forEach { add(BrowseFocusSlot(BrowseFocusKey("search:$provider:$category", it.ratingKey, true), index)) }
                index++
            }
        }
        status(state.plexState); mediaGroups(plexGroups, "plex")
        status(state.jellyfinState); mediaGroups(jellyfinGroups, "jellyfin")
        status(state.embyState); mediaGroups(embyGroups, "emby")
        status(state.trailerState); mediaGroups(trailerGroups, "trailers")
        status(state.telegramState); mediaGroups(telegramGroups, "telegram")
        status(state.tvheadendState)
        state.liveTvSources.values.forEach { source ->
            val channels = channelsBySource[source.id].orEmpty()
            val programs = programsBySource[source.id].orEmpty()
            val recordings = recordingsBySource[source.id].orEmpty()
            if (channels.isNotEmpty()) {
                channels.forEach { add(BrowseFocusSlot(BrowseFocusKey(
                    "search:tvh-channels:${source.id}", "search:tvh-channel:${it.sourceId}:${it.uuid}", true), index)) }
                index++
            }
            if (programs.isNotEmpty()) {
                programs.forEach { add(BrowseFocusSlot(BrowseFocusKey(
                    "search:tvh-programs:${source.id}", "search:tvh-program:${it.sourceId}:${it.eventId}", true), index)) }
                index++
            }
            if (recordings.isNotEmpty()) {
                recordings.forEach { add(BrowseFocusSlot(BrowseFocusKey(
                    "search:tvh-recordings:${source.id}", "search:tvh-recording:${it.sourceId}:${it.uuid}", true), index)) }
                index++
            }
        }
        status(state.youtubeState)
        if (state.youtubeResults.isNotEmpty()) {
            state.youtubeResults.forEach { add(BrowseFocusSlot(BrowseFocusKey(
                "search:youtube", "search:youtube:${it.videoId}", true), index)) }
            index++
        }
        status(state.spotifyState)
        spotifyGroups.forEach { (type, items) ->
            items.forEach { add(BrowseFocusSlot(BrowseFocusKey(
                "search:spotify:$type", "search:spotify:${it.type}:${it.id}", true), index)) }
            index++
        }
        status(state.radioState)
        if (state.radioResults.isNotEmpty()) {
            state.radioResults.forEach { add(BrowseFocusSlot(BrowseFocusKey(
                "search:radio", "search:radio:${it.id}", true), index)) }
        }
    } }
    val savedSlot = remember(focusSlots, resultFocus) {
        focusSlots.firstOrNull { it.key.item == resultFocus.itemKey }
    }
    val focusedKey = savedSlot?.key?.item
    val restoreResultFocus = resultFocus.pending && focusedKey != null
    val requestInitialResultFocus = resultFocus.pending && !state.isSearching && focusedKey == null
    val initialSlot = savedSlot ?: focusSlots.firstOrNull().takeUnless { resultFocus.itemKey != null && state.isSearching }
    val rowsState = rememberLazyListState(initialFirstVisibleItemIndex = savedSlot?.index ?: 0)
    RegisterBrowseFocusSlots(rowsState, focusSlots)
    BrowseFocusInitialTarget(initialSlot?.key?.item, initialSlot?.key?.row)
    BrowseFocusHoldEntry(resultFocus.pending && resultFocus.itemKey != null && savedSlot == null && state.isSearching)
    val rowFocus = rememberRowColumnFocus()
    LaunchedEffect(resultFocus, resultFocus.pending, focusSlots) {
        resultFocus.restore(focusSlots) { rowsState.scrollToItem(it) }
    }
    LazyColumn(
        allowUpExit = true,
        state = rowsState,
        modifier = Modifier.fillMaxSize().rowColumnFocusWrapRight(rowFocus).clipToBounds(),
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
                val rowKey = "search:plex:$titleRes"
                val keys = remember(groupItems) { groupItems.map { it.ratingKey } }
                val rowState = rememberBrowseRowState(initialFirstVisibleItemIndex = keys.indexOf(resultFocus.itemKey).coerceAtLeast(0))
                RegisterRowColumnFocus(rowFocus, rowKey, rowState, keys)
                BrowseFocusRow(rowKey) {
                LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.containRowFocusExit()) {
                    itemsIndexed(groupItems, key = { _, it -> it.ratingKey }) { _, item ->
                        MediaTile(item = item, modifier = Modifier.rowColumnFocusTarget(rowFocus, rowKey, item.ratingKey), style = mediaTileStyle, showDescription = false, onClick = { onItemClick(item.ratingKey) }, onFocused = { onFocused(item) }, requestInitialFocus = (restoreResultFocus && item.ratingKey == focusedKey) || (requestInitialResultFocus && item.ratingKey == firstMediaKey))
                    }
                }
                }
            }
        }
        sourceStatusItem("jellyfin-status", MediaSystem.JELLYFIN, state.jellyfinState)
        itemsIndexed(jellyfinGroups, key = { _, group -> "jellyfin-${group.first}" }) { groupIndex, (titleRes, groupItems) ->
            Column(modifier = Modifier.bringFocusedContextIntoView(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SearchGroupTitle(MediaSystem.JELLYFIN, titleRes)
                val rowKey = "search:jellyfin:$titleRes"
                val keys = remember(groupItems) { groupItems.map { it.ratingKey } }
                val rowState = rememberBrowseRowState(initialFirstVisibleItemIndex = keys.indexOf(resultFocus.itemKey).coerceAtLeast(0))
                RegisterRowColumnFocus(rowFocus, rowKey, rowState, keys)
                BrowseFocusRow(rowKey) {
                LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.containRowFocusExit()) {
                    itemsIndexed(groupItems, key = { _, it -> it.ratingKey }) { _, item ->
                        MediaTile(item = item, modifier = Modifier.rowColumnFocusTarget(rowFocus, rowKey, item.ratingKey), style = mediaTileStyle, showDescription = false, onClick = { onItemClick(item.ratingKey) }, onFocused = { onFocused(item) }, requestInitialFocus = (restoreResultFocus && item.ratingKey == focusedKey) || (requestInitialResultFocus && plexGroups.isEmpty() && item.ratingKey == firstMediaKey))
                    }
                }
                }
            }
        }
        sourceStatusItem("emby-status", MediaSystem.EMBY, state.embyState)
        itemsIndexed(embyGroups, key = { _, group -> "emby-${group.first}" }) { groupIndex, (titleRes, groupItems) ->
            Column(modifier = Modifier.bringFocusedContextIntoView(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SearchGroupTitle(MediaSystem.EMBY, titleRes)
                val rowKey = "search:emby:$titleRes"
                val keys = remember(groupItems) { groupItems.map { it.ratingKey } }
                val rowState = rememberBrowseRowState(initialFirstVisibleItemIndex = keys.indexOf(resultFocus.itemKey).coerceAtLeast(0))
                RegisterRowColumnFocus(rowFocus, rowKey, rowState, keys)
                BrowseFocusRow(rowKey) {
                LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.containRowFocusExit()) {
                    itemsIndexed(groupItems, key = { _, it -> it.ratingKey }) { _, item ->
                        MediaTile(item = item, modifier = Modifier.rowColumnFocusTarget(rowFocus, rowKey, item.ratingKey), style = mediaTileStyle, showDescription = false, onClick = { onItemClick(item.ratingKey) }, onFocused = { onFocused(item) }, requestInitialFocus = (restoreResultFocus && item.ratingKey == focusedKey) || (requestInitialResultFocus && plexGroups.isEmpty() && jellyfinGroups.isEmpty() && item.ratingKey == firstMediaKey))
                    }
                }
                }
            }
        }
        sourceStatusItem("trailers-status", MediaSystem.TRAILERS, state.trailerState)
        itemsIndexed(trailerGroups, key = { _, group -> "trailers-${group.first}" }) { groupIndex, (titleRes, groupItems) ->
            Column(modifier = Modifier.bringFocusedContextIntoView(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SearchGroupTitle(MediaSystem.TRAILERS, titleRes)
                val rowKey = "search:trailers:$titleRes"
                val keys = remember(groupItems) { groupItems.map { it.ratingKey } }
                val rowState = rememberBrowseRowState(initialFirstVisibleItemIndex = keys.indexOf(resultFocus.itemKey).coerceAtLeast(0))
                RegisterRowColumnFocus(rowFocus, rowKey, rowState, keys)
                BrowseFocusRow(rowKey) {
                LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.containRowFocusExit()) {
                    itemsIndexed(groupItems, key = { _, it -> it.ratingKey }) { _, item ->
                        MediaTile(item = item, modifier = Modifier.rowColumnFocusTarget(rowFocus, rowKey, item.ratingKey), style = mediaTileStyle, showDescription = false, onClick = { onItemClick(item.ratingKey) },
                            onFocused = { onFocused(item) },
                            requestInitialFocus = (restoreResultFocus && item.ratingKey == focusedKey) ||
                                (requestInitialResultFocus && plexGroups.isEmpty() && jellyfinGroups.isEmpty() && embyGroups.isEmpty() && item.ratingKey == firstMediaKey))
                    }
                }
                }
            }
        }
        sourceStatusItem("telegram-status", MediaSystem.TELEGRAM, state.telegramState, floodWaitSeconds = state.telegramRetryAfterSeconds)
        itemsIndexed(telegramGroups, key = { _, group -> "telegram-${group.first}" }) { _, (titleRes, groupItems) ->
            Column(modifier = Modifier.bringFocusedContextIntoView(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SearchGroupTitle(MediaSystem.TELEGRAM, titleRes)
                val rowKey = "search:telegram:$titleRes"
                val keys = remember(groupItems) { groupItems.map { it.ratingKey } }
                val rowState = rememberBrowseRowState(initialFirstVisibleItemIndex = keys.indexOf(resultFocus.itemKey).coerceAtLeast(0))
                RegisterRowColumnFocus(rowFocus, rowKey, rowState, keys)
                BrowseFocusRow(rowKey) {
                LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.containRowFocusExit()) {
                    itemsIndexed(groupItems, key = { _, it -> it.ratingKey }) { _, item ->
                        // Telegram previews are video frames and covers, not posters.
                        MediaTile(item = item, modifier = Modifier.rowColumnFocusTarget(rowFocus, rowKey, item.ratingKey), style = MediaTileStyle.LANDSCAPE, showDescription = false, onClick = { onItemClick(item.ratingKey) },
                            onFocused = { onFocused(item) },
                            requestInitialFocus = (restoreResultFocus && item.ratingKey == focusedKey) ||
                                (requestInitialResultFocus && item.ratingKey == firstMediaKey))
                    }
                }
                }
            }
        }
        sourceStatusItem("tvheadend-status", MediaSystem.TVHEADEND, state.tvheadendState, labelOverride = state.liveTvSources.values.map { it.name }.distinct().joinToString(", ").ifBlank { null })
        state.liveTvSources.values.forEach { source ->
            val channels = channelsBySource[source.id].orEmpty()
            val programs = programsBySource[source.id].orEmpty()
            val recordings = recordingsBySource[source.id].orEmpty()
            val headers = state.liveTvHeaders[source.id].orEmpty()
            val system = MediaSystem.fromKey(source.system) ?: MediaSystem.TVHEADEND
            if (channels.isNotEmpty()) item(key = "tv-channels:${source.id}") {
                TvheadendChannelSearchRow(channels, headers, onTvheadendChannelClick, requestInitialResultFocus && firstExternalGroup == "tvh-channels" && channels.first() == state.tvheadendResults.channels.firstOrNull(), system, source.name, rowFocus)
            }
            if (programs.isNotEmpty()) item(key = "tv-programs:${source.id}") {
                TvheadendProgramSearchRow(programs, headers, onTvheadendProgramClick, requestInitialResultFocus && firstExternalGroup == "tvh-programs" && programs.first() == state.tvheadendResults.programs.firstOrNull(), system, source.name, rowFocus)
            }
            if (recordings.isNotEmpty()) item(key = "tv-recordings:${source.id}") {
                TvheadendRecordingSearchRow(recordings, headers, onTvheadendRecordingClick, requestInitialResultFocus && firstExternalGroup == "tvh-recordings" && recordings.first() == state.tvheadendResults.recordings.firstOrNull(), system, source.name, rowFocus)
            }
        }
        sourceStatusItem("youtube-status", MediaSystem.YOUTUBE, state.youtubeState)
        if (state.youtubeResults.isNotEmpty()) {
            item(key = "youtube-results") { YouTubeSearchRow(state.youtubeResults, onYouTubeClick, requestInitialResultFocus && firstExternalGroup == "youtube", rowFocus) }
        }
        sourceStatusItem("spotify-status", MediaSystem.SPOTIFY, state.spotifyState)
        items(spotifyGroups, key = { "spotify-${it.first}" }) { (type, items) ->
            SpotifySearchRow(type, items, onSpotifyClick, requestInitialResultFocus && firstExternalGroup == "spotify" && type == spotifyGroups.firstOrNull()?.first, rowFocus)
        }
        sourceStatusItem("radio-status", MediaSystem.RADIO, state.radioState)
        if (state.radioResults.isNotEmpty()) {
            item(key = "radio-results") {
                RadioSearchRow(state.radioResults, onRadioClick, requestInitialResultFocus && firstExternalGroup == "radio", rowFocus)
            }
        }
    }
}

internal enum class SearchLoadingArtwork { POSTER, LANDSCAPE, SQUARE }

/** Search rows contain posters for media servers, landscape video, and square audio covers. */
internal fun searchLoadingArtwork(system: MediaSystem): SearchLoadingArtwork = when (system) {
    MediaSystem.SPOTIFY, MediaSystem.RADIO -> SearchLoadingArtwork.SQUARE
    MediaSystem.YOUTUBE, MediaSystem.TVHEADEND, MediaSystem.TELEGRAM -> SearchLoadingArtwork.LANDSCAPE
    else -> SearchLoadingArtwork.POSTER
}

private fun androidx.compose.foundation.lazy.LazyListScope.sourceListStatusItem(
    key: String,
    system: MediaSystem,
    state: SearchSourceState,
    labelRes: Int? = null,
    labelOverride: String? = null,
    floodWaitSeconds: Int? = null,
) = sourceStatusItem(key, system, state, labelRes, labelOverride, SearchViewMode.LIST, floodWaitSeconds)

private fun androidx.compose.foundation.lazy.LazyListScope.sourceStatusItem(
    key: String,
    system: MediaSystem,
    state: SearchSourceState,
    labelRes: Int? = null,
    labelOverride: String? = null,
    mode: SearchViewMode = SearchViewMode.ROWS,
    floodWaitSeconds: Int? = null,
) {
    when (state) {
        SearchSourceState.LOADING -> item(key = key) {
            val title = stringResource(R.string.search_source_loading, labelOverride ?: labelRes?.let { stringResource(it) } ?: searchSystemName(system))
            when {
                mode == SearchViewMode.LIST -> SearchLoadingList(title, system)
                // Telegram results render as MonogramMediaCard (MediaTile routes them), not MediaTile.
                system == MediaSystem.TELEGRAM -> SearchLoadingMonogramRow(title)
                searchLoadingArtwork(system) == SearchLoadingArtwork.SQUARE -> SearchLoadingSquareRow(title, system)
                else -> SkeletonRow(
                    title = title,
                    style = if (searchLoadingArtwork(system) == SearchLoadingArtwork.LANDSCAPE)
                        MediaTileStyle.LANDSCAPE else MediaTileStyle.POSTER,
                    itemCount = 4,
                )
            }
        }
        SearchSourceState.ERROR, SearchSourceState.PARTIAL -> item(key = key) {
            val label = labelOverride ?: labelRes?.let { stringResource(it) } ?: searchSystemName(system)
            Text(
                when {
                    floodWaitSeconds != null && state == SearchSourceState.ERROR ->
                        stringResource(R.string.search_source_flood_wait, label, floodWaitSeconds)
                    state == SearchSourceState.PARTIAL -> stringResource(R.string.search_source_partial, label)
                    else -> stringResource(R.string.search_source_failed, label)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        else -> Unit
    }
}

/** Same header, row paddings and SmartTube-style card as the Telegram search rows. */
@Composable
private fun SearchLoadingMonogramRow(title: String) {
    val pulse = com.ambient.tv.ui.common.rememberSkeletonPulseOrNull()
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ContentSectionHeader(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            userScrollEnabled = false,
        ) {
            items(4) { com.ambient.tv.ui.common.MonogramCardPlaceholder(pulse = pulse) }
        }
    }
}

/** Same section and 1:1 cover silhouette as the Spotify and Radio search rows. */
@Composable
private fun SearchLoadingSquareRow(title: String, system: MediaSystem) {
    val pulse = com.ambient.tv.ui.common.rememberSkeletonPulseOrNull()
    val fill = MaterialTheme.colorScheme.surfaceVariant
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ContentSectionHeader(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            userScrollEnabled = false,
        ) {
            items(4) {
                Column(
                    Modifier.width(if (system == MediaSystem.SPOTIFY) 220.dp else 250.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = .72f), shape)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(shape)
                        .skeletonPulseAlpha(pulse).background(fill, shape))
                    Box(Modifier.fillMaxWidth(.78f).height(17.dp)
                        .skeletonPulseAlpha(pulse).background(fill, RoundedCornerShape(5.dp)))
                    Box(Modifier.fillMaxWidth(.56f).height(12.dp)
                        .skeletonPulseAlpha(pulse).background(fill, RoundedCornerShape(5.dp)))
                }
            }
        }
    }
}

/** Loading status is a list-shaped source section when Search is set to LIST. */
@Composable
private fun SearchLoadingList(title: String, system: MediaSystem) {
    val pulse = com.ambient.tv.ui.common.rememberSkeletonPulseOrNull()
    val fill = MaterialTheme.colorScheme.surfaceVariant
    val shape = RoundedCornerShape(10.dp)
    // Telegram's list results use SearchMediaListItem like the media servers, so they share
    // that geometry even though their row/card artwork is landscape.
    val artwork = if (system == MediaSystem.TELEGRAM) SearchLoadingArtwork.POSTER else searchLoadingArtwork(system)
    val artWidth = when (artwork) {
        SearchLoadingArtwork.POSTER -> 166.dp  // SearchMediaListItem uses landscape artwork
        SearchLoadingArtwork.LANDSCAPE -> 132.dp
        SearchLoadingArtwork.SQUARE -> 76.dp
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ContentSectionHeader(title, modifier = Modifier.padding(top = 12.dp), startPadding = 0.dp, bottomPadding = 2.dp)
        repeat(3) {
            Row(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .58f), shape)
                    .border(1.dp, Color.White.copy(alpha = .06f), shape)
                    .padding(if (artwork == SearchLoadingArtwork.POSTER) 14.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(if (artwork == SearchLoadingArtwork.POSTER) 20.dp else 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(artWidth).aspectRatio(if (artwork == SearchLoadingArtwork.SQUARE) 1f else 16f / 9f)
                    .clip(shape).skeletonPulseAlpha(pulse).background(fill, shape))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.fillMaxWidth(.72f).height(18.dp).skeletonPulseAlpha(pulse).background(fill, shape))
                    Box(Modifier.fillMaxWidth(.44f).height(12.dp).skeletonPulseAlpha(pulse).background(fill, shape))
                }
            }
        }
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
private fun YouTubeSearchRow(youtubeItems: List<YouTubeItem>, onClick: (String) -> Unit, requestInitialFocus: Boolean = false, rowFocus: RowColumnFocus) {
    val rowKey = "search:youtube"
    val keys = remember(youtubeItems) { youtubeItems.map { "search:youtube:${it.videoId}" } }
    val initialKey = LocalSearchInitialResultKey.current
    val onResultFocused = LocalSearchResultFocused.current
    val rowState = rememberBrowseRowState(initialFirstVisibleItemIndex = keys.indexOf(initialKey).coerceAtLeast(0))
    RegisterRowColumnFocus(rowFocus, rowKey, rowState, keys)
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchGroupTitle(MediaSystem.YOUTUBE, R.string.search_videos)
        BrowseFocusRow(rowKey) {
        LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.containRowFocusExit()) {
            items(youtubeItems, key = { it.videoId }) { item ->
                var focused by remember(item.videoId) { mutableStateOf(false) }
                val cardShape = RoundedCornerShape(10.dp)
                Surface(onClick = { onClick(item.videoId) }, colors = transparentSurfaceColors(), scale = noSurfaceScale(), shape = ClickableSurfaceDefaults.shape(shape = cardShape), modifier = initialFocusModifier((requestInitialFocus && item == youtubeItems.firstOrNull()) || initialKey == "search:youtube:${item.videoId}").width(250.dp).rowColumnFocusTarget(rowFocus, rowKey, "search:youtube:${item.videoId}").browseFocusTarget("search:youtube:${item.videoId}").mediaFocusMotion(focused).activateOnTvKey { onClick(item.videoId) }.onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onResultFocused("search:youtube:${item.videoId}")
                }) {
                    Column(
                        Modifier.animatedFocusSurface(
                focused = focused, shape = cardShape,
                focusedBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                unfocusedBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                focusedBorder = MaterialTheme.colorScheme.primary,
                unfocusedBorder = Color.White.copy(alpha = 0.08f),
                borderWidth = 2.dp,
            )
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        ArtworkImage(model = item.imageUrl, title = item.title, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                        CenteredMarqueeTitle(item.title, focused = focused, style = MaterialTheme.typography.titleSmall)
                        val info = com.ambient.tv.ui.youtube.youtubeCardInfo(item)
                        listOfNotNull(info.channel, info.details.joinToString("  ·  ").takeIf(String::isNotBlank)).forEach {
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
}

@Composable
private fun RadioSearchRow(
    items: List<RadioStation>,
    onClick: (RadioStation) -> Unit,
    requestInitialFocus: Boolean = false,
    rowFocus: RowColumnFocus,
) {
    val rowKey = "search:radio"
    val keys = remember(items) { items.map { "search:radio:${it.id}" } }
    val rowState = rememberBrowseRowState(initialFirstVisibleItemIndex = keys.indexOf(LocalSearchInitialResultKey.current).coerceAtLeast(0))
    RegisterRowColumnFocus(rowFocus, rowKey, rowState, keys)
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchGroupTitle(MediaSystem.RADIO, R.string.radio_stations)
        BrowseFocusRow(rowKey) {
        LazyRow(
            state = rowState,
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
                    focusKey = "search:radio:${station.id}",
                    focusModifier = Modifier.rowColumnFocusTarget(rowFocus, rowKey, "search:radio:${station.id}"),
                    requestInitialFocus = requestInitialFocus && station == items.firstOrNull(),
                )
            }
        }
        }
    }
}

@Composable
private fun SpotifySearchRow(type: SpotifyEntityType, items: List<SpotifyBrowseItem>, onClick: (SpotifyBrowseItem) -> Unit, requestInitialFocus: Boolean = false, rowFocus: RowColumnFocus) {
    val titleRes = spotifyTypeTitleRes(type)
    val rowKey = "search:spotify:$type"
    val keys = remember(items) { items.map { "search:spotify:${it.type}:${it.id}" } }
    val initialKey = LocalSearchInitialResultKey.current
    val onResultFocused = LocalSearchResultFocused.current
    val rowState = rememberBrowseRowState(initialFirstVisibleItemIndex = keys.indexOf(initialKey).coerceAtLeast(0))
    RegisterRowColumnFocus(rowFocus, rowKey, rowState, keys)
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchGroupTitle(MediaSystem.SPOTIFY, titleRes)
        BrowseFocusRow(rowKey) {
        LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.containRowFocusExit()) {
            items(items, key = { "${it.type}:${it.id}" }) { item ->
                var focused by remember(item.type, item.id) { mutableStateOf(false) }
                val cardShape = RoundedCornerShape(10.dp)
                Surface(onClick = { onClick(item) }, colors = transparentSurfaceColors(), scale = noSurfaceScale(), shape = ClickableSurfaceDefaults.shape(shape = cardShape), modifier = initialFocusModifier((requestInitialFocus && item == items.firstOrNull()) || initialKey == "search:spotify:${item.type}:${item.id}").width(220.dp).rowColumnFocusTarget(rowFocus, rowKey, "search:spotify:${item.type}:${item.id}").browseFocusTarget("search:spotify:${item.type}:${item.id}").mediaFocusMotion(focused).activateOnTvKey { onClick(item) }.onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onResultFocused("search:spotify:${item.type}:${item.id}")
                }) {
                    Column(
                        Modifier.animatedFocusSurface(
                focused = focused, shape = cardShape,
                focusedBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                unfocusedBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                focusedBorder = MaterialTheme.colorScheme.primary,
                unfocusedBorder = Color.White.copy(alpha = 0.08f),
                borderWidth = 2.dp,
            )
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
}

@Composable
private fun TvheadendChannelSearchRow(
    channels: List<TvhChannel>,
    imageHeaders: Map<String, String>,
    onClick: (TvhChannel) -> Unit,
    requestInitialFocus: Boolean = false,
    system: MediaSystem = MediaSystem.TVHEADEND,
    sourceName: String = "",
    rowFocus: RowColumnFocus,
) {
    val rowKey = "search:tvh-channels:${channels.first().sourceId}"
    val keys = remember(channels) { channels.map { "search:tvh-channel:${it.sourceId}:${it.uuid}" } }
    val rowState = rememberBrowseRowState(initialFirstVisibleItemIndex = keys.indexOf(LocalSearchInitialResultKey.current).coerceAtLeast(0))
    RegisterRowColumnFocus(rowFocus, rowKey, rowState, keys)
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(sourceName, style = MaterialTheme.typography.labelMedium)
        SearchGroupTitle(system, R.string.search_channels)
        BrowseFocusRow(rowKey) {
        LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.containRowFocusExit()) {
            items(channels, key = { it.uuid }) { channel ->
                SearchArtworkCard(
                    imageUrl = channel.iconUrl,
                    title = channel.name,
                    subtitle = channel.numberLabel,
                    imageHeaders = imageHeaders,
                    onClick = { onClick(channel) },
                    focusKey = "search:tvh-channel:${channel.sourceId}:${channel.uuid}",
                    focusModifier = Modifier.rowColumnFocusTarget(rowFocus, rowKey, "search:tvh-channel:${channel.sourceId}:${channel.uuid}"),
                    requestInitialFocus = requestInitialFocus && channel == channels.firstOrNull(),
                )
            }
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
    rowFocus: RowColumnFocus,
) {
    val rowKey = "search:tvh-programs:${programs.first().sourceId}"
    val keys = remember(programs) { programs.map { "search:tvh-program:${it.sourceId}:${it.eventId}" } }
    val rowState = rememberBrowseRowState(initialFirstVisibleItemIndex = keys.indexOf(LocalSearchInitialResultKey.current).coerceAtLeast(0))
    RegisterRowColumnFocus(rowFocus, rowKey, rowState, keys)
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(sourceName, style = MaterialTheme.typography.labelMedium)
        SearchGroupTitle(system, R.string.tvh_guide)
        BrowseFocusRow(rowKey) {
        LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.containRowFocusExit()) {
            items(programs, key = { it.eventId }) { event ->
                SearchArtworkCard(
                    imageUrl = event.imageUrl,
                    title = event.title,
                    subtitle = "${event.channelName} · ${formatSearchDateTime(event.startEpochSeconds)}",
                    imageHeaders = imageHeaders,
                    onClick = { onClick(event) },
                    focusKey = "search:tvh-program:${event.sourceId}:${event.eventId}",
                    focusModifier = Modifier.rowColumnFocusTarget(rowFocus, rowKey, "search:tvh-program:${event.sourceId}:${event.eventId}"),
                    requestInitialFocus = requestInitialFocus && event == programs.firstOrNull(),
                )
            }
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
    rowFocus: RowColumnFocus,
) {
    val rowKey = "search:tvh-recordings:${recordings.first().sourceId}"
    val keys = remember(recordings) { recordings.map { "search:tvh-recording:${it.sourceId}:${it.uuid}" } }
    val rowState = rememberBrowseRowState(initialFirstVisibleItemIndex = keys.indexOf(LocalSearchInitialResultKey.current).coerceAtLeast(0))
    RegisterRowColumnFocus(rowFocus, rowKey, rowState, keys)
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(sourceName, style = MaterialTheme.typography.labelMedium)
        SearchGroupTitle(system, R.string.tvh_recordings)
        BrowseFocusRow(rowKey) {
        LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.containRowFocusExit()) {
            items(recordings, key = { it.uuid }) { recording ->
                SearchArtworkCard(
                    imageUrl = recording.imageUrl ?: recording.fanartUrl,
                    title = recording.title,
                    subtitle = "${recording.channelName} · ${formatSearchDateTime(recording.startEpochSeconds)}",
                    imageHeaders = imageHeaders,
                    onClick = { onClick(recording) },
                    focusKey = "search:tvh-recording:${recording.sourceId}:${recording.uuid}",
                    focusModifier = Modifier.rowColumnFocusTarget(rowFocus, rowKey, "search:tvh-recording:${recording.sourceId}:${recording.uuid}"),
                    requestInitialFocus = requestInitialFocus && recording == recordings.firstOrNull(),
                )
            }
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
    focusKey: String,
    focusModifier: Modifier = Modifier,
) {
    var focused by remember(title, imageUrl) { mutableStateOf(false) }
    val initialKey = LocalSearchInitialResultKey.current
    val onResultFocused = LocalSearchResultFocused.current
    val cardShape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape = cardShape),
        modifier = initialFocusModifier(requestInitialFocus || initialKey == focusKey).width(250.dp).then(focusModifier).browseFocusTarget(focusKey).mediaFocusMotion(focused).activateOnTvKey(onClick = onClick).onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onResultFocused(focusKey)
        },
    ) {
        Column(
            Modifier.animatedFocusSurface(
                focused = focused, shape = cardShape,
                focusedBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                unfocusedBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                focusedBorder = MaterialTheme.colorScheme.primary,
                unfocusedBorder = Color.White.copy(alpha = 0.08f),
                borderWidth = 2.dp,
            )
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            ArtworkImage(
                model = imageUrl,
                title = title,
                headers = imageHeaders,
                modifier = Modifier.fillMaxWidth().aspectRatio(if (squareArtwork) 1f else 16f / 9f).clip(RoundedCornerShape(10.dp)),
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
    resultFocus: SearchFocusRequest,
    onItemClick: (String) -> Unit,
    onYouTubeClick: (String) -> Unit,
    onSpotifyClick: (SpotifyBrowseItem) -> Unit,
    onRadioClick: (RadioStation) -> Unit,
    onTvheadendChannelClick: (TvhChannel) -> Unit,
    onTvheadendProgramClick: (TvhEpgEvent) -> Unit,
    onTvheadendRecordingClick: (TvhDvrEntry) -> Unit,
    onFocused: (MediaItem) -> Unit,
) {
    val plexGroups = remember(state.results) { searchMediaGroups(state.results) }
    val jellyfinGroups = remember(state.jellyfinResults) { searchMediaGroups(state.jellyfinResults) }
    val embyGroups = remember(state.embyResults) { searchMediaGroups(state.embyResults) }
    val trailerGroups = remember(state.trailerResults) { searchMediaGroups(state.trailerResults) }
    val telegramGroups = remember(state.telegramResults) { telegramSearchGroups(state.telegramResults) }
    val spotifyGroups = remember(state.spotifyResults) {
        SpotifyEntityType.entries.mapNotNull { type ->
            state.spotifyResults.filter { it.type == type }.takeIf { it.isNotEmpty() }?.let { type to it }
        }
    }
    val channelsBySource = remember(state.tvheadendResults.channels) { state.tvheadendResults.channels.groupBy { it.sourceId } }
    val programsBySource = remember(state.tvheadendResults.programs) { state.tvheadendResults.programs.groupBy { it.sourceId } }
    val recordingsBySource = remember(state.tvheadendResults.recordings) { state.tvheadendResults.recordings.groupBy { it.sourceId } }
    val firstMediaKey = plexGroups.firstOrNull()?.second?.firstOrNull()?.ratingKey
        ?: jellyfinGroups.firstOrNull()?.second?.firstOrNull()?.ratingKey
        ?: embyGroups.firstOrNull()?.second?.firstOrNull()?.ratingKey
        ?: trailerGroups.firstOrNull()?.second?.firstOrNull()?.ratingKey
        ?: telegramGroups.firstOrNull()?.second?.firstOrNull()?.ratingKey
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

    // Include non-focusable headers/status rows in physical lazy indices.
    val focusSlots = remember(
        plexGroups, jellyfinGroups, embyGroups, trailerGroups, telegramGroups, spotifyGroups,
        channelsBySource, programsBySource, recordingsBySource, state.liveTvSources,
        state.youtubeResults, state.radioResults,
        state.plexState, state.jellyfinState, state.embyState, state.trailerState, state.telegramState,
        state.tvheadendState, state.youtubeState, state.spotifyState, state.radioState,
    ) { buildList {
        var index = 0
        fun status(source: SearchSourceState) {
            if (source == SearchSourceState.LOADING || source == SearchSourceState.ERROR || source == SearchSourceState.PARTIAL) index++
        }
        fun mediaGroups(groups: List<Pair<Int, List<MediaItem>>>) {
            groups.forEach { (_, items) ->
                index++ // Group heading
                items.forEach { add(BrowseFocusSlot(BrowseFocusKey(null, it.ratingKey, true), index++)) }
            }
        }
        status(state.plexState); mediaGroups(plexGroups)
        status(state.jellyfinState); mediaGroups(jellyfinGroups)
        status(state.embyState); mediaGroups(embyGroups)
        status(state.trailerState); mediaGroups(trailerGroups)
        status(state.telegramState); mediaGroups(telegramGroups)
        status(state.tvheadendState)
        state.liveTvSources.values.forEach { source ->
            channelsBySource[source.id].orEmpty().takeIf { it.isNotEmpty() }?.let { items ->
                index++
                items.forEach { add(BrowseFocusSlot(BrowseFocusKey(null, "search:tvh-channel:${it.sourceId}:${it.uuid}", true), index++)) }
            }
            programsBySource[source.id].orEmpty().takeIf { it.isNotEmpty() }?.let { items ->
                index++
                items.forEach { add(BrowseFocusSlot(BrowseFocusKey(null, "search:tvh-program:${it.sourceId}:${it.eventId}", true), index++)) }
            }
            recordingsBySource[source.id].orEmpty().takeIf { it.isNotEmpty() }?.let { items ->
                index++
                items.forEach { add(BrowseFocusSlot(BrowseFocusKey(null, "search:tvh-recording:${it.sourceId}:${it.uuid}", true), index++)) }
            }
        }
        status(state.youtubeState)
        if (state.youtubeResults.isNotEmpty()) {
            index++
            state.youtubeResults.forEach { add(BrowseFocusSlot(BrowseFocusKey(null, "search:youtube:${it.videoId}", true), index++)) }
        }
        status(state.spotifyState)
        spotifyGroups.forEach { (_, items) ->
            index++
            items.forEach { add(BrowseFocusSlot(BrowseFocusKey(null, "search:spotify:${it.type}:${it.id}", true), index++)) }
        }
        status(state.radioState)
        if (state.radioResults.isNotEmpty()) {
            index++
            state.radioResults.forEach { add(BrowseFocusSlot(BrowseFocusKey(null, "search:radio:${it.id}", true), index++)) }
        }
    } }
    val savedSlot = remember(focusSlots, resultFocus) {
        focusSlots.firstOrNull { it.key.item == resultFocus.itemKey }
    }
    val focusedKey = savedSlot?.key?.item
    val restoreResultFocus = resultFocus.pending && focusedKey != null
    val requestInitialResultFocus = resultFocus.pending && !state.isSearching && focusedKey == null
    val initialSlot = savedSlot ?: focusSlots.firstOrNull().takeUnless { resultFocus.itemKey != null && state.isSearching }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = savedSlot?.index ?: 0)
    RegisterBrowseFocusSlots(listState, focusSlots)
    BrowseFocusInitialTarget(initialSlot?.key?.item, initialSlot?.key?.row)
    BrowseFocusHoldEntry(resultFocus.pending && resultFocus.itemKey != null && savedSlot == null && state.isSearching)
    val requestedMediaKey = focusedKey.takeIf { restoreResultFocus } ?: firstMediaKey.takeIf { requestInitialResultFocus }
    LaunchedEffect(resultFocus, resultFocus.pending, focusSlots) {
        resultFocus.restore(focusSlots) { listState.scrollToItem(it) }
    }
    LazyColumn(
        allowUpExit = true,
        state = listState,
        modifier = Modifier.overlayLazyScrollbar(listState),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        sourceListStatusItem("plex-list-status", MediaSystem.PLEX, state.plexState)
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

        sourceListStatusItem("jellyfin-list-status", MediaSystem.JELLYFIN, state.jellyfinState)
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

        sourceListStatusItem("emby-list-status", MediaSystem.EMBY, state.embyState)
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

        sourceListStatusItem("trailers-list-status", MediaSystem.TRAILERS, state.trailerState)
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

        sourceListStatusItem("telegram-list-status", MediaSystem.TELEGRAM, state.telegramState, floodWaitSeconds = state.telegramRetryAfterSeconds)
        telegramGroups.forEach { (groupRes, groupItems) ->
            item(key = "telegram-list-header:$groupRes") { SearchListGroupHeader(MediaSystem.TELEGRAM, groupRes) }
            items(groupItems, key = { "telegram-media:${it.ratingKey}" }) { item ->
                SearchMediaListItem(
                    item = item,
                    onClick = { onItemClick(item.ratingKey) },
                    onFocused = { onFocused(item) },
                    requestInitialFocus = item.ratingKey == requestedMediaKey,
                )
            }
        }

        sourceListStatusItem("tvheadend-list-status", MediaSystem.TVHEADEND, state.tvheadendState, labelOverride = state.liveTvSources.values.map { it.name }.distinct().joinToString(", ").ifBlank { null })
        state.liveTvSources.values.forEach { source ->
            val channels = channelsBySource[source.id].orEmpty()
            val programs = programsBySource[source.id].orEmpty()
            val recordings = recordingsBySource[source.id].orEmpty()
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
                    focusKey = "search:tvh-channel:${channel.sourceId}:${channel.uuid}",
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
                    focusKey = "search:tvh-program:${event.sourceId}:${event.eventId}",
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
                    focusKey = "search:tvh-recording:${recording.sourceId}:${recording.uuid}",
                    requestInitialFocus = requestInitialResultFocus && firstExternalGroup == "tvh-recordings" && recording == state.tvheadendResults.recordings.firstOrNull(),
                )
            }
        }

        }
        sourceListStatusItem("youtube-list-status", MediaSystem.YOUTUBE, state.youtubeState)
        if (state.youtubeResults.isNotEmpty()) {
            item(key = "youtube-list-header") { SearchListGroupHeader(MediaSystem.YOUTUBE, R.string.search_videos) }
            items(state.youtubeResults, key = { "youtube:${it.videoId}" }) { item ->
                ExternalSearchListItem(
                    imageUrl = item.imageUrl,
                    title = item.title,
                    subtitle = item.author ?: stringResource(R.string.search_videos),
                    onClick = { onYouTubeClick(item.videoId) },
                    focusKey = "search:youtube:${item.videoId}",
                    requestInitialFocus = requestInitialResultFocus && firstExternalGroup == "youtube" && item == state.youtubeResults.firstOrNull(),
                )
            }
        }

        sourceListStatusItem("spotify-list-status", MediaSystem.SPOTIFY, state.spotifyState)
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
                    focusKey = "search:spotify:${item.type}:${item.id}",
                    requestInitialFocus = requestInitialResultFocus && firstExternalGroup == "spotify" && groupIndex == 0 && item == groupItems.firstOrNull(),
                )
            }
        }
        sourceListStatusItem("radio-list-status", MediaSystem.RADIO, state.radioState)
        if (state.radioResults.isNotEmpty()) {
            item(key = "radio-list-header") { SearchListGroupHeader(MediaSystem.RADIO, R.string.radio_stations) }
            items(state.radioResults, key = { "radio:${it.id}" }) { station ->
                ExternalSearchListItem(
                    imageUrl = station.artworkUrl,
                    title = station.name,
                    subtitle = radioStationSubtitle(station),
                    squareArtwork = true,
                    onClick = { onRadioClick(station) },
                    focusKey = "search:radio:${station.id}",
                    requestInitialFocus = requestInitialResultFocus && firstExternalGroup == "radio" && station == state.radioResults.firstOrNull(),
                )
            }
        }
    }
}

internal fun searchMediaGroups(items: List<MediaItem>): List<Pair<Int, List<MediaItem>>> = listOf(
    R.string.search_movies to items.filter { it.type == "movie" },
    R.string.search_shows to items.filter { it.type == "show" },
    R.string.search_episodes to items.filter { it.type == "episode" },
    R.string.search_other to items.filter { it.type !in setOf("movie", "show", "episode") },
).filter { it.second.isNotEmpty() }

/** Telegram search returns only playable posts: videos first, then audio. */
internal fun telegramSearchGroups(items: List<MediaItem>): List<Pair<Int, List<MediaItem>>> = listOf(
    R.string.search_telegram_videos to items.filter { it.type != "track" },
    R.string.search_telegram_audio to items.filter { it.type == "track" },
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
            .browseFocusTarget(item.ratingKey)
            .mediaFocusMotion(isFocused, focusedScale = 1.012f)
            .activateOnTvKey(onClick = onClick)
            .bringFocusedContextIntoView()
            .onFocusChanged { isFocused = it.isFocused; if (it.isFocused) onFocused() },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .animatedFocusSurface(
                focused = isFocused, shape = rowShape,
                focusedBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f),
                unfocusedBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
                focusedBorder = MaterialTheme.colorScheme.primary,
                unfocusedBorder = Color.White.copy(alpha = 0.06f),
                borderWidth = 1.5.dp,
            )
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
    focusKey: String,
) {
    var focused by remember(title, imageUrl) { mutableStateOf(false) }
    val onResultFocused = LocalSearchResultFocused.current
    val rowShape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape = rowShape),
        modifier = initialFocusModifier(requestInitialFocus).fillMaxWidth().browseFocusTarget(focusKey).mediaFocusMotion(focused, focusedScale = 1.012f).activateOnTvKey(onClick = onClick).bringFocusedContextIntoView().onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onResultFocused(focusKey)
        },
    ) {
        Row(
            Modifier.fillMaxWidth()
                .animatedFocusSurface(
                focused = focused, shape = rowShape,
                focusedBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f),
                unfocusedBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
                focusedBorder = MaterialTheme.colorScheme.primary,
                unfocusedBorder = Color.White.copy(alpha = 0.06f),
                borderWidth = 1.5.dp,
            )
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
    val browseFocus = com.ambient.tv.ui.common.LocalBrowseFocusReturnState.current
    // View changes remount the results behind the panel. Their old automatic focus
    // requests are consumed while toolbar controls own focus.
    LaunchedEffect(requestInitialFocus, filterFocus, browseFocus) {
        if (browseFocus?.managesInitialFocus == true) return@LaunchedEffect
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
