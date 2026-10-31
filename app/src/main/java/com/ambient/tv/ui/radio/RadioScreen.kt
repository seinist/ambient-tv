package com.ambient.tv.ui.radio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.radio.RadioFacet
import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.ui.common.AlphabetBar
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.CenteredMarqueeTitle
import com.ambient.tv.ui.common.MarqueeTitle
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.common.SystemPageTitle
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.TvLazyColumn
import com.ambient.tv.ui.common.TvLazyVerticalGrid
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.ViewOption
import com.ambient.tv.ui.common.ViewOptionsChip
import com.ambient.tv.ui.common.ViewOptionsPanel
import com.ambient.tv.ui.common.ViewPreview
import com.ambient.tv.ui.common.sortDirectionOptions
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.bringFocusedContextIntoView
import com.ambient.tv.ui.common.BrowseFocusContentIdentity
import com.ambient.tv.ui.common.BrowseFocusInitialTarget
import com.ambient.tv.ui.common.RegisterBrowseFocusGrid
import com.ambient.tv.ui.common.RegisterBrowseFocusList
import com.ambient.tv.ui.common.browseFocusTarget
import com.ambient.tv.ui.common.rememberBrowseGridState
import com.ambient.tv.ui.common.rememberBrowseListState
import com.ambient.tv.ui.common.handheldSwipeTabs
import com.ambient.tv.ui.common.mediaFocusMotion
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.overlayLazyGridScrollbar
import com.ambient.tv.ui.common.pageContentUp
import com.ambient.tv.ui.common.LocalBrowseRightIndexFocus
import com.ambient.tv.ui.common.pageFocusStage
import com.ambient.tv.ui.common.PageFocusLevel
import com.ambient.tv.ui.common.skeletonPulseAlpha
import com.ambient.tv.ui.common.transparentSurfaceColors
import com.ambient.tv.ui.mobile.deviceDensityGridCells
import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.theme.screenGutter
import kotlinx.coroutines.launch
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun RadioScreen(
    onBack: (() -> Unit)? = null,
    onOpenPlayer: () -> Unit = {},
    initialSectionKey: String? = null,
    onSelectSection: ((RadioSection) -> Unit)? = null,
    viewModel: RadioViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val playback by BackgroundPlaybackSession.snapshot.collectAsState()
    val selectSection = onSelectSection ?: viewModel::selectSection
    var showViewOptions by remember { mutableStateOf(false) }
    var restoreViewOptionsFocus by remember { mutableStateOf(false) }
    val viewOptionsFocusRequester = remember { FocusRequester() }
    LaunchedEffect(showViewOptions, restoreViewOptionsFocus) {
        if (!showViewOptions && restoreViewOptionsFocus) {
            withFrameNanos { }
            runCatching { viewOptionsFocusRequester.requestFocus() }
            restoreViewOptionsFocus = false
        }
    }
    var showSearch by remember { mutableStateOf(false) }
    val searchVisible = showSearch || state.query.isNotBlank() || state.submittedQuery.isNotBlank()
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val requestedSection = RadioSection.fromKey(initialSectionKey) ?: RadioSection.POPULAR
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // NavHost paints before its first RESUMED callback. A reused VM can still hold an
    // old confirmed-empty error; shield the first frame with the requested route's layout.
    var entryPending by remember(viewModel, requestedSection) { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner, viewModel, requestedSection) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE &&
                viewModel.state.value.needsRemoteRefreshOnReturn()
            ) {
                // This happens while leaving, before the returning destination may draw.
                // Populated cached content stays on screen instead of reverting to a skeleton.
                entryPending = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    com.ambient.tv.ui.common.RefreshOnEnterAndReconnect(setOf("radio")) {
        // PR #151 redirects disabled category routes; don't fetch that hidden category.
        if (requestedSection in viewModel.state.value.visibleSections) viewModel.enter(requestedSection)
        entryPending = false
    }
    // A category disabled in Settings must not strand a deep link on a hidden tab.
    // Route through the same sidebar navigation as a normal chip selection.
    LaunchedEffect(initialSectionKey, state.visibleSections) {
        val requested = RadioSection.fromKey(initialSectionKey) ?: RadioSection.POPULAR
        if (requested !in state.visibleSections) selectSection(state.visibleSections.first())
    }
    val requestedPagePending = state.awaitingRequestedPage(requestedSection, entryPending)
    val visibleSection = if (entryPending || state.section != requestedSection) requestedSection else state.section
    ReportSidebarContentReady(!requestedPagePending && state.contentReady)

    val focusScope = state.focusScope()
    val showingFacets = if (requestedPagePending && state.section != requestedSection)
        requestedSection.isFacetSection else state.isFacetOverview()
    val locale = LocalContext.current.resources.configuration.locales[0]
    // Alphabetical order uses the localized display names, not raw Radio Browser codes.
    val sortedFacets = remember(state.section, state.facets, locale, state.sortDescending) {
        radioAlphabetFacets(state.section, state.facets, locale, descending = state.sortDescending)
    }
    val alphabetJumps = remember(sortedFacets) { radioAlphabetJumps(sortedFacets) }
    val alphabetLetters = remember(alphabetJumps) { alphabetJumps.mapNotNull { it.letter.singleOrNull() } }
    // Senderlisten (auch Raster, Suche und Favoriten) verwenden denselben Index wie Kategorien.
    // Die Sender selbst sind bereits vom ViewModel alphabetisch in der angezeigten Reihenfolge.
    val stationJumps = remember(state.stations) { radioStationAlphabetJumps(state.stations) }
    val stationLetters = remember(stationJumps) { stationJumps.mapNotNull { it.letter.singleOrNull() } }
    val effectiveViewMode = if (requestedPagePending && visibleSection.isFacetSection) RadioViewMode.LIST else state.effectiveViewMode()
    val contentIds = if (showingFacets) sortedFacets.map { it.facet.id } else state.stations.map(RadioStation::id)
    val playingStationId = playback.metadata?.takeIf { it.source == "radio" }?.mediaId
    // Hold the initial selection stable while browsing: paging and metadata updates must not
    // jump the list back to a previously focused card.
    val initialContentId = remember(
        focusScope, effectiveViewMode, state.contentReady, state.returnFocusRevision, requestedPagePending, state.sortDescending,
    ) {
        if (requestedPagePending || !state.contentReady) null
        else radioInitialFocusId(
            viewModel.lastFocusedContent(focusScope),
            playingStationId.takeUnless { showingFacets },
            contentIds,
        )
    }
    var pendingContentFocus by remember(viewModel) { mutableStateOf<RadioFocusScope?>(null) }
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            if (viewModel.consumePlayerReturn()) {
                // Re-enter the actually playing station, not the old clicked card.
                pendingContentFocus = viewModel.state.value.focusScope()
            }
            kotlinx.coroutines.awaitCancellation()
        }
    }
    var focusAfterViewOptions by remember(viewModel) { mutableStateOf(false) }
    val gridState = rememberBrowseGridState()
    val listState = rememberBrowseListState()
    val focusChain = com.ambient.tv.ui.common.rememberPageFocusChain()
    val contextStageFocus = remember { FocusRequester() }
    val searchStageFocus = remember { FocusRequester() }
    val initialFocusRequester = remember(focusScope, effectiveViewMode) { FocusRequester() }
    val alphabetReturnRequester = remember(focusScope) { FocusRequester() }
    val alphabetRightFocusRequester = remember(focusScope) { FocusRequester() }
    val facetListFocus = remember(focusScope) { FocusRequester() }
    val stationContentFocus = remember(focusScope, effectiveViewMode) { FocusRequester() }
    var alphabetJumpTargetId by remember(focusScope) { mutableStateOf<String?>(null) }
    var focusedStationId by remember(focusScope) { mutableStateOf<String?>(null) }
    val scrollScope = rememberCoroutineScope()
    // Focus keeps its facet identity across locale/sort changes, rather than resetting
    // the index indicator to the row currently at the top of the scrolling list.
    var focusedFacetId by remember(focusScope) { mutableStateOf<String?>(null) }
    val visibleLetter = sortedFacets.firstOrNull { it.facet.id == (focusedFacetId ?: initialContentId) }
        ?.letter?.singleOrNull()
        ?: sortedFacets.getOrNull(listState.firstVisibleItemIndex)?.letter?.singleOrNull()
    // Prefer the focused tile over the first visible row, including after returning from playback.
    val currentStationId = focusedStationId?.takeIf { id -> state.stations.any { it.id == id } } ?: initialContentId
    val stationLetter = state.stations.firstOrNull { it.id == currentStationId }?.let(::radioStationLetter)
        ?: state.stations.getOrNull(if (effectiveViewMode == RadioViewMode.GRID) gridState.firstVisibleItemIndex
            else listState.firstVisibleItemIndex)?.let(::radioStationLetter)
    val showStationAlphabet = !showingFacets && stationLetters.size > 1 && !requestedPagePending

    BrowseFocusContentIdentity(listOf(viewModel, focusScope, effectiveViewMode, state.sortDescending, state.returnFocusRevision),
        restoreInitialTarget = true)
    if (!requestedPagePending && state.contentReady && contentIds.isNotEmpty()) {
        BrowseFocusInitialTarget(initialContentId)
        if (effectiveViewMode == RadioViewMode.GRID) RegisterBrowseFocusGrid(gridState, contentIds)
        else RegisterBrowseFocusList(listState, contentIds)
    }
    LaunchedEffect(focusScope, effectiveViewMode, state.contentReady, requestedPagePending, state.sortDescending) {
        if (requestedPagePending || !state.contentReady || initialContentId == null) return@LaunchedEffect
        val index = contentIds.indexOf(initialContentId).takeIf { it >= 0 } ?: return@LaunchedEffect
        if (effectiveViewMode == RadioViewMode.GRID) gridState.scrollToItem(index)
        else listState.scrollToItem(index)
    }
    // A facet opens another dataset inside the same destination. The sidebar's entry handler
    // only runs on route navigation, so hand focus explicitly to its newly loaded content.
    LaunchedEffect(focusScope, effectiveViewMode, state.contentReady, requestedPagePending, pendingContentFocus, showViewOptions, handheld) {
        if (requestedPagePending || !state.contentReady || pendingContentFocus != focusScope || showViewOptions || handheld) return@LaunchedEffect
        val targetId = initialContentId
        val index = targetId?.let(contentIds::indexOf)?.takeIf { it >= 0 }
        if (index == null) {
            pendingContentFocus = null
            return@LaunchedEffect
        }
        if (effectiveViewMode == RadioViewMode.GRID) gridState.scrollToItem(index)
        else listState.scrollToItem(index)
        repeat(6) {
            withFrameNanos { }
            if (runCatching { initialFocusRequester.requestFocus() }.getOrDefault(false)) {
                pendingContentFocus = null
                return@LaunchedEffect
            }
        }
        pendingContentFocus = null
    }
    val selectFacet: (RadioFacet) -> Unit = { facet ->
        pendingContentFocus = RadioFocusScope(state.section, facet.id, "")
        viewModel.selectFacet(facet)
    }
    val toggleStationFavorite: (RadioStation) -> Unit = { station ->
        // The focused favorite disappears from this page; restore the neighboring station.
        if (state.section == RadioSection.FAVORITES && station.id in state.favoriteIds) {
            pendingContentFocus = focusScope
        }
        viewModel.toggleFavorite(station)
    }
    val clearFacet: () -> Unit = {
        pendingContentFocus = RadioFocusScope(state.section, null, "")
        viewModel.clearFacet()
    }
    val submitSearch: () -> Unit = {
        val query = state.query.trim()
        if (query.length >= 2) pendingContentFocus = RadioFocusScope(state.section, state.selectedFacet?.id, query)
        viewModel.submitSearch()
    }
    val clearSearch: () -> Unit = {
        pendingContentFocus = RadioFocusScope(state.section, state.selectedFacet?.id, "")
        viewModel.clearSearch()
    }
    val closeViewOptions: () -> Unit = {
        showViewOptions = false
        if (focusAfterViewOptions) pendingContentFocus = focusScope
        else restoreViewOptionsFocus = true
        focusAfterViewOptions = false
    }
    BackHandler(enabled = !showViewOptions && searchVisible) {
        if (state.submittedQuery.isNotBlank() || state.query.isNotBlank()) clearSearch()
        showSearch = false
    }
    BackHandler(enabled = !showViewOptions && !searchVisible && state.selectedFacet != null) { clearFacet() }
    if (onBack != null) {
        BackHandler(enabled = !showViewOptions && !searchVisible && state.selectedFacet == null) { onBack() }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .handheldSwipeTabs(
                    tabs = state.visibleSections,
                    selected = visibleSection,
                    onSelect = selectSection,
                    enabled = !showViewOptions &&
                        !searchVisible &&
                        state.selectedFacet == null,
                )
                .padding(
                    start = screenGutter(),
                    end = screenGutter(),
                    bottom = screenGutter(),
                    top = com.ambient.tv.ui.common.pageHeaderTop(screenGutter()),
                ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PageHeader(
                focusChain = focusChain,
                actions = {
                    TvChip(
                        text = stringResource(R.string.action_search),
                        icon = Icons.Filled.Search,
                        iconOnly = true,
                        selected = searchVisible,
                        onClick = { showSearch = true },
                    )
                    TvChip(
                        text = stringResource(R.string.action_reload),
                        icon = Icons.Filled.Refresh,
                        iconOnly = true,
                        loading = state.loading || state.loadingMore,
                        onClick = viewModel::refresh,
                    )
                    ViewOptionsChip(0, onClick = { showViewOptions = true },
                        modifier = Modifier.focusRequester(viewOptionsFocusRequester))
                },
            ) {
                SystemPageTitle(com.ambient.tv.ui.common.MediaSystem.RADIO)
                Text(
                    if (requestedPagePending && state.section != requestedSection)
                        radioSectionLabel(requestedSection) else radioPageSubtitle(state),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Like SmartTube: show the enabled category chips on TV AND handheld.
            com.ambient.tv.ui.common.PageChipRow(
                focusChain,
                selectedIndex = state.visibleSections.indexOf(visibleSection),
            ) {
                lazyItems(state.visibleSections, key = { it.key }) { section ->
                    TvChip(
                        text = radioSectionLabel(section),
                        selected = visibleSection == section &&
                            state.submittedQuery.isBlank() &&
                            state.selectedFacet == null,
                        icon = com.ambient.tv.ui.common.radioMenuIcon(section.key),
                        modifier = if (visibleSection == section) Modifier.focusRequester(focusChain.selectedChip) else Modifier,
                        onClick = { selectSection(section) },
                    )
                }
            }

            state.selectedFacet?.let { selectedFacet ->
                Row(
                    Modifier.fillMaxWidth().pageFocusStage(focusChain, PageFocusLevel.CONTEXT, contextStageFocus),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvChip(
                        text = stringResource(R.string.radio_browse_back, radioSectionLabel(state.section)),
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        onClick = clearFacet,
                    )
                    Text(
                        radioFacetLabel(state.section, selectedFacet),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (searchVisible) {
                Row(
                    Modifier.fillMaxWidth().pageFocusStage(focusChain, PageFocusLevel.SEARCH_LOWER, searchStageFocus),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvTextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChanged,
                        label = stringResource(R.string.radio_search_placeholder),
                        modifier = Modifier.weight(1f),
                    )
                    TvActionButton(
                        text = stringResource(R.string.action_search),
                        onClick = submitSearch,
                        enabled = state.query.trim().length >= 2,
                    )
                    PlayerIconButton(
                        Icons.Filled.Close,
                        stringResource(R.string.action_close),
                        {
                            if (state.submittedQuery.isNotBlank() || state.query.isNotBlank()) clearSearch()
                            showSearch = false
                        },
                    )
                }
            }

            when {
                requestedPagePending -> RadioLoadingContent(
                    layout = state.requestedLoadingLayout(requestedSection),
                    gridColumns = state.gridColumns,
                    listRows = state.listRows,
                    modifier = Modifier.fillMaxSize().pageContentUp(focusChain),
                )
                state.error && !state.loading &&
                    (if (showingFacets) state.facets.isEmpty() else state.stations.isEmpty()) -> Column(
                    Modifier.fillMaxWidth().pageContentUp(focusChain).focusGroup(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatePanel(
                        title = stringResource(R.string.radio_error),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TvActionButton(
                        text = stringResource(R.string.action_reload),
                        onClick = viewModel::refresh,
                    )
                }
                showingFacets && state.facets.isEmpty() && state.contentReady -> StatePanel(
                    title = stringResource(R.string.radio_no_stations),
                    modifier = Modifier.fillMaxWidth(),
                )
                showingFacets -> Row(
                    Modifier.fillMaxSize()
                        .pageContentUp(focusChain)
                        .focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Genres, countries and languages are text-only: one consistent list,
                    // regardless of the remembered grid/list choice for actual stations.
                    Box(
                        Modifier.weight(1f).fillMaxSize()
                            .focusRequester(facetListFocus)
                            .focusGroup(),
                    ) {
                        androidx.compose.runtime.CompositionLocalProvider(LocalBrowseRightIndexFocus provides alphabetRightFocusRequester.takeIf { alphabetLetters.size > 5 }) {
                        RadioFacetList(
                            facets = sortedFacets,
                            listRows = state.listRows,
                            state = listState,
                            onClick = selectFacet,
                            onFocused = { facet ->
                                focusedFacetId = facet.id
                                viewModel.onFacetFocused(facet.id)
                            },
                            initialFocusId = initialContentId,
                            initialFocusRequester = initialFocusRequester,
                            alphabetJumpId = alphabetJumpTargetId,
                            alphabetReturnRequester = alphabetReturnRequester,
                        )
                        }
                    }
                    if (alphabetLetters.size > 5) {
                        // Shared component already used by the main library: no new A-Z widget.
                        AlphabetBar(
                            letters = alphabetLetters,
                            selected = visibleLetter,
                            onSelect = { letter ->
                                val index = alphabetJumps.firstOrNull { it.letter == letter.toString() }?.index
                                    ?: return@AlphabetBar
                                alphabetJumpTargetId = sortedFacets.getOrNull(index)?.facet?.id
                                focusedFacetId = alphabetJumpTargetId
                                scrollScope.launch { listState.scrollToItem(index) }
                            },
                            gridFocus = facetListFocus,
                            onReturnToContent = {
                                val candidate = if (alphabetJumpTargetId != null) alphabetReturnRequester else initialFocusRequester
                                if (!runCatching { candidate.requestFocus() }.getOrDefault(false)) {
                                    runCatching { facetListFocus.requestFocus() }
                                }
                            },
                            modifier = Modifier.fillMaxHeight(),
                            firstEntryFocusRequester = alphabetRightFocusRequester,
                            actionsFocus = focusChain.actions,
                        )
                    }
                }
                state.stations.isEmpty() && state.contentReady -> StatePanel(
                    title = stringResource(R.string.radio_no_stations),
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> Row(
                    Modifier.fillMaxSize().pageContentUp(focusChain).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .focusRequester(stationContentFocus).focusGroup(),
                    ) {
                        // Reuse the existing focus-aware A–Z bar in both grid and list mode.
                        // While shown, D-pad Right goes to its top entry rather than page actions.
                        androidx.compose.runtime.CompositionLocalProvider(
                            LocalBrowseRightIndexFocus provides alphabetRightFocusRequester.takeIf { showStationAlphabet },
                        ) {
                            com.ambient.tv.ui.common.SoftViewChange(state.viewMode, Modifier.fillMaxSize()) {
                                if (state.viewMode == RadioViewMode.GRID) {
                                    TvLazyVerticalGrid(
                                        columns = deviceDensityGridCells(state.gridColumns, 160.dp, 4),
                                        modifier = Modifier.fillMaxSize()
                                            .then(if (showStationAlphabet) Modifier else Modifier.overlayLazyGridScrollbar(gridState))
                                            .pageContentUp(focusChain).focusGroup(),
                                        state = gridState,
                                        contentPadding = PaddingValues(bottom = 40.dp),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        itemsIndexed(state.stations, key = { _, station -> station.id }) { _, station ->
                                            RadioStationTile(
                                                station = station,
                                                modifier = when (station.id) {
                                                    alphabetJumpTargetId -> Modifier.focusRequester(alphabetReturnRequester)
                                                    initialContentId -> Modifier.focusRequester(initialFocusRequester)
                                                    else -> Modifier
                                                },
                                                playing = playback.metadata?.source == "radio" &&
                                                    playback.metadata?.mediaId == station.id && playback.wantsToPlay,
                                                loading = state.startingStationId == station.id,
                                                favorite = station.id in state.favoriteIds,
                                                onClick = { viewModel.play(station, onOpenPlayer) },
                                                onFavorite = { toggleStationFavorite(station) },
                                                onFocused = {
                                                    focusedStationId = station.id
                                                    viewModel.onStationFocused(station.id)
                                                },
                                            )
                                        }
                                        if (state.loadingMore) {
                                            items(
                                                count = RADIO_GRID_END_PLACEHOLDERS,
                                                key = { radioGridEndPlaceholderKey(it) },
                                            ) {
                                                RadioEndPlaceholder(Modifier.width(deviceDp(190.dp, 160.dp)))
                                            }
                                        }
                                    }
                                } else {
                                    RadioStationList(
                                        stations = state.stations,
                                        listRows = state.listRows,
                                        playbackId = playback.metadata?.mediaId.takeIf { playback.metadata?.source == "radio" },
                                        playing = playback.wantsToPlay,
                                        startingStationId = state.startingStationId,
                                        favoriteIds = state.favoriteIds,
                                        loadingMore = state.loadingMore,
                                        state = listState,
                                        onClick = { station -> viewModel.play(station, onOpenPlayer) },
                                        onFavorite = toggleStationFavorite,
                                        onFocused = { station ->
                                            focusedStationId = station.id
                                            viewModel.onStationFocused(station.id)
                                        },
                                        initialFocusId = initialContentId,
                                        initialFocusRequester = initialFocusRequester,
                                        alphabetJumpId = alphabetJumpTargetId,
                                        alphabetReturnRequester = alphabetReturnRequester,
                                        modifier = Modifier.pageContentUp(focusChain),
                                    )
                                }
                            }
                        }
                    }
                    if (showStationAlphabet) {
                        AlphabetBar(
                            letters = stationLetters,
                            selected = stationLetter,
                            onSelect = { letter ->
                                val index = stationJumps.firstOrNull { it.letter == letter.toString() }?.index
                                    ?: return@AlphabetBar
                                val target = state.stations.getOrNull(index) ?: return@AlphabetBar
                                alphabetJumpTargetId = target.id
                                focusedStationId = target.id
                                scrollScope.launch {
                                    if (state.viewMode == RadioViewMode.GRID) gridState.scrollToItem(index)
                                    else listState.scrollToItem(index)
                                }
                            },
                            gridFocus = stationContentFocus,
                            onReturnToContent = {
                                // A distant letter is not necessarily composed yet; scroll first,
                                // then focus the exact matching sender instead of a nearby card.
                                val targetIndex = state.stations.indexOfFirst { it.id == alphabetJumpTargetId }
                                scrollScope.launch {
                                    if (targetIndex >= 0) {
                                        if (state.viewMode == RadioViewMode.GRID) gridState.scrollToItem(targetIndex)
                                        else listState.scrollToItem(targetIndex)
                                    }
                                    val target = if (targetIndex >= 0) alphabetReturnRequester else initialFocusRequester
                                    repeat(6) {
                                        withFrameNanos { }
                                        if (runCatching { target.requestFocus() }.getOrDefault(false)) return@launch
                                    }
                                    runCatching { stationContentFocus.requestFocus() }
                                }
                            },
                            modifier = Modifier.fillMaxHeight(),
                            firstEntryFocusRequester = alphabetRightFocusRequester,
                            actionsFocus = focusChain.actions,
                        )
                    }
                }
            }
        }

        if (showViewOptions) {
            com.ambient.tv.ui.common.RightPanelOverlay(onOutsideTap = closeViewOptions) {
            Box(Modifier.align(Alignment.TopEnd)) {
                ViewOptionsPanel(
                    views = if (showingFacets) emptyList() else listOf(
                        ViewOption(RadioViewMode.GRID.name, stringResource(R.string.library_view_grid), ViewPreview.GRID),
                        ViewOption(RadioViewMode.LIST.name, stringResource(R.string.library_view_list), ViewPreview.LIST),
                    ),
                    selectedView = effectiveViewMode.name,
                    densityPreview = if (showingFacets) ViewPreview.LIST else null,
                    onView = {
                        val mode = RadioViewMode.valueOf(it)
                        if (mode != state.viewMode) focusAfterViewOptions = true
                        viewModel.selectViewMode(mode)
                    },
                    gridColumns = state.gridColumns,
                    listRows = state.listRows,
                    onGridColumns = viewModel::setGridColumns,
                    onListRows = viewModel::setListRows,
                    sortOrders = sortDirectionOptions("A–Z", "Z–A"),
                    selectedSortOrder = if (state.sortDescending) "DESCENDING" else "ASCENDING",
                    onSortOrder = { viewModel.setSortDescending(it == "DESCENDING") },
                    onClose = closeViewOptions,
                )
            }
            }
        }
    }
}

@Composable
private fun radioPageSubtitle(state: RadioUiState): String = when {
    state.submittedQuery.isNotBlank() -> state.submittedQuery
    state.selectedFacet != null -> radioFacetLabel(state.section, state.selectedFacet)
    else -> radioSectionLabel(state.section)
}

@Composable
private fun radioSectionLabel(section: RadioSection): String = stringResource(
    when (section) {
        RadioSection.POPULAR -> R.string.radio_popular
        RadioSection.FAVORITES -> R.string.radio_favorites
        RadioSection.RECENT -> R.string.radio_recent
        RadioSection.GENRES -> R.string.radio_genres
        RadioSection.COUNTRIES -> R.string.radio_countries
        RadioSection.LANGUAGES -> R.string.radio_languages
    },
)

@Composable
private fun radioFacetLabel(section: RadioSection, facet: RadioFacet): String =
    radioFacetDisplayName(section, facet, LocalContext.current.resources.configuration.locales[0])

internal fun radioGenreDisplayName(raw: String): String {
    val compact = raw.trim().replace(Regex("\\s+"), " ")
    if (compact.isBlank()) return compact
    val known = mapOf(
        "r&b" to "R&B",
        "rnb" to "R&B",
        "edm" to "EDM",
        "dnb" to "DnB",
        "drum and bass" to "Drum & Bass",
        "hip hop" to "Hip-Hop",
        "hip-hop" to "Hip-Hop",
        "rock n roll" to "Rock ’n’ Roll",
        "rock'n'roll" to "Rock ’n’ Roll",
        "rock and roll" to "Rock & Roll",
        "lofi" to "Lo-Fi",
        "lo-fi" to "Lo-Fi",
        "kpop" to "K-Pop",
        "k-pop" to "K-Pop",
        "jpop" to "J-Pop",
        "j-pop" to "J-Pop",
    )
    known[compact.lowercase()]?.let { return it }
    return compact.split(' ').joinToString(" ") { word ->
        if (word.length <= 4 && word.all { it.isDigit() || it == 's' || it == 'S' }) {
            word.lowercase()
        } else {
            word.split('-').joinToString("-") { part ->
                part.replaceFirstChar { it.titlecase() }
            }
        }
    }
}

@Composable
private fun RadioFacetList(
    facets: List<RadioAlphabetFacet>,
    listRows: Int,
    state: androidx.compose.foundation.lazy.LazyListState,
    onClick: (RadioFacet) -> Unit,
    onFocused: (RadioFacet) -> Unit,
    initialFocusId: String?,
    initialFocusRequester: FocusRequester,
    alphabetJumpId: String?,
    alphabetReturnRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val rowScale = (7f / listRows.coerceIn(4, 10)).coerceIn(.72f, 1.35f)
    TvLazyColumn(
        allowUpExit = true,
        focusActionsOnRight = false, // D-pad Right enters the shared AlphabetBar, not page actions.
        modifier = modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp * rowScale),
    ) {
        lazyItems(facets, key = { it.facet.id }) { entry ->
            val facet = entry.facet
            var focused by remember(facet.id) { mutableStateOf(false) }
            val shape = RoundedCornerShape(10.dp)
            val focusModifier = when (facet.id) {
                alphabetJumpId -> Modifier.focusRequester(alphabetReturnRequester)
                initialFocusId -> Modifier.focusRequester(initialFocusRequester)
                else -> Modifier
            }
            Surface(
                onClick = { onClick(facet) },
                colors = transparentSurfaceColors(),
                scale = noSurfaceScale(),
                shape = ClickableSurfaceDefaults.shape(shape),
                modifier = focusModifier
                    .fillMaxWidth()
                    .browseFocusTarget(facet.id)
                    .mediaFocusMotion(focused, focusedScale = 1.012f)
                    .activateOnTvKey { onClick(facet) }
                    .bringFocusedContextIntoView()
                    .onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused) onFocused(facet)
                    },
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent, shape)
                        .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .06f), shape)
                        .padding(10.dp * rowScale),
                    horizontalArrangement = Arrangement.spacedBy(14.dp * rowScale),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Same geometry, accent and focus styling as the radio station list.
                    // Text-only facets place their station count in the trailing metadata slot.
                    Column(Modifier.weight(1f)) {
                        MarqueeTitle(entry.title, focused = focused, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        stringResource(R.string.radio_station_count, facet.stationCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal enum class RadioLoadingLayout { FACETS, GRID, LIST }

/** The loading shape follows the real content: facet overviews never use station cards. */
internal fun RadioUiState.loadingLayout(): RadioLoadingLayout = when {
    isFacetOverview() -> RadioLoadingLayout.FACETS
    viewMode == RadioViewMode.GRID -> RadioLoadingLayout.GRID
    else -> RadioLoadingLayout.LIST
}

@Composable
private fun RadioLoadingContent(
    layout: RadioLoadingLayout,
    gridColumns: Int,
    listRows: Int,
    modifier: Modifier = Modifier,
) {
    val pulse = if (com.ambient.tv.ui.performance.LocalPerformanceProfile.current.reducedEffects) null
        else com.ambient.tv.ui.common.rememberSharedPulse(com.ambient.tv.ui.common.SharedArtworkPulse)
    val rowScale = (7f / listRows.coerceIn(4, 10)).coerceIn(.72f, 1.35f)
    when (layout) {
        RadioLoadingLayout.GRID -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = deviceDensityGridCells(gridColumns, 160.dp, 4),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            userScrollEnabled = false,
        ) {
            items(24) { RadioEndPlaceholder(Modifier.fillMaxWidth(), pulse) }
        }
        RadioLoadingLayout.LIST, RadioLoadingLayout.FACETS -> {
            val facets = layout == RadioLoadingLayout.FACETS
            Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp * rowScale),
                    userScrollEnabled = false,
                ) {
                    items(if (facets) 22 else 12) {
                        RadioListPlaceholder(facets, rowScale, pulse)
                    }
                }
                if (facets) {
                    // Reserve the same width as the alphabet strip shown with real categories.
                    Box(Modifier.width(if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) 36.dp else 28.dp))
                }
            }
        }
    }
}

@Composable
private fun RadioListPlaceholder(isFacet: Boolean, rowScale: Float, pulse: State<Float>?) {
    val fill = MaterialTheme.colorScheme.surfaceVariant
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = .06f), shape)
            .padding(10.dp * rowScale),
        horizontalArrangement = Arrangement.spacedBy(14.dp * rowScale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isFacet) {
            Box(
                Modifier.size(72.dp * rowScale)
                    .skeletonPulseAlpha(pulse)
                    .background(fill, RoundedCornerShape(8.dp)),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier.fillMaxWidth(if (isFacet) .48f else .64f)
                    .height(20.dp * rowScale)
                    .skeletonPulseAlpha(pulse)
                    .background(fill, RoundedCornerShape(4.dp)),
            )
            if (!isFacet) Box(
                Modifier.fillMaxWidth(.4f)
                    .height(16.dp * rowScale)
                    .skeletonPulseAlpha(pulse)
                    .background(fill, RoundedCornerShape(4.dp)),
            )
        }
        if (isFacet) {
            Box(
                Modifier.width(64.dp * rowScale).height(16.dp * rowScale)
                    .skeletonPulseAlpha(pulse)
                    .background(fill, RoundedCornerShape(4.dp)),
            )
        }
    }
}

internal const val RADIO_GRID_END_PLACEHOLDERS = 2

internal fun radioGridEndPlaceholderKey(index: Int) = "radio-grid-more:$index"

@Composable
private fun RadioEndPlaceholder(modifier: Modifier = Modifier, sharedPulse: State<Float>? = null) {
    val pulse = sharedPulse ?: if (com.ambient.tv.ui.performance.LocalPerformanceProfile.current.reducedEffects) null
        else com.ambient.tv.ui.common.rememberSharedPulse(com.ambient.tv.ui.common.SharedArtworkPulse)
    val fill = MaterialTheme.colorScheme.surfaceVariant
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .58f), shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .skeletonPulseAlpha(pulse)
                .background(fill, RoundedCornerShape(8.dp)),
        )
        Box(
            Modifier
                .fillMaxWidth(.78f)
                .align(Alignment.CenterHorizontally)
                .padding(top = 2.dp)
                .aspectRatio(8f)
                .skeletonPulseAlpha(pulse)
                .background(fill, RoundedCornerShape(3.dp)),
        )
        Box(
            Modifier
                .fillMaxWidth(.5f)
                .align(Alignment.CenterHorizontally)
                .aspectRatio(10f)
                .skeletonPulseAlpha(pulse)
                .background(fill, RoundedCornerShape(3.dp)),
        )
    }
}

/**
 * Small separately focusable favorite action, matching the sibling actions on LiveTV
 * recording rows. It must not be nested inside the station's play Surface.
 */
@Composable
private fun RadioFavoriteAction(
    favorite: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(if (favorite) R.string.radio_remove_favorite else R.string.radio_add_favorite)
    TvActionButton(
        text = "",
        icon = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
        selected = favorite,
        onClick = onClick,
        modifier = modifier
            .semantics { contentDescription = description }
            .onFocusChanged { if (it.isFocused) onFocused() },
    )
}

@Composable
private fun RadioStationTile(
    station: RadioStation,
    modifier: Modifier = Modifier,
    playing: Boolean,
    loading: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onFocused: () -> Unit,
) {
    var focused by remember(station.id) { mutableStateOf(false) }
    var cardHasFocus by remember(station.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.fillMaxWidth().onFocusChanged { cardHasFocus = it.hasFocus }) {
        Surface(
            onClick = onClick,
            colors = transparentSurfaceColors(),
            scale = noSurfaceScale(),
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            modifier = modifier
                .fillMaxWidth()
                .browseFocusTarget(station.id)
                .mediaFocusMotion(focused)
                .activateOnTvKey(onClick = onClick)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                },
        ) {
            Column(
                Modifier
                    .background(
                        if (cardHasFocus || playing) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                        shape,
                    )
                    .border(
                        2.dp,
                        if (cardHasFocus || playing) MaterialTheme.colorScheme.primary
                        else Color.White.copy(alpha = 0.08f),
                        shape,
                    )
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    RadioArtwork(station, Modifier.fillMaxSize())
                    if (playing || loading) {
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(6.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f))
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                        ) {
                            androidx.compose.material3.Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
                CenteredMarqueeTitle(station.name, focused = cardHasFocus)
                Text(
                    radioStationSubtitle(station, LocalContext.current.resources.configuration.locales[0]),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        RadioFavoriteAction(
            favorite = favorite,
            onClick = onFavorite,
            onFocused = onFocused,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        )
    }
}

@Composable
private fun RadioStationList(
    stations: List<RadioStation>,
    listRows: Int,
    playbackId: String?,
    playing: Boolean,
    startingStationId: String?,
    favoriteIds: Set<String>,
    loadingMore: Boolean,
    state: androidx.compose.foundation.lazy.LazyListState,
    onClick: (RadioStation) -> Unit,
    onFavorite: (RadioStation) -> Unit,
    onFocused: (RadioStation) -> Unit,
    initialFocusId: String?,
    initialFocusRequester: FocusRequester,
    alphabetJumpId: String?,
    alphabetReturnRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val rowScale = (7f / listRows.coerceIn(4, 10)).coerceIn(.72f, 1.35f)
    val paginationPulse = if (loadingMore &&
        !com.ambient.tv.ui.performance.LocalPerformanceProfile.current.reducedEffects
    ) com.ambient.tv.ui.common.rememberSharedPulse(com.ambient.tv.ui.common.SharedArtworkPulse) else null
    TvLazyColumn(
        allowUpExit = true,
        focusActionsOnRight = false,
        modifier = modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp * rowScale),
    ) {
        lazyItems(stations, key = { it.id }) { station ->
            var focused by remember(station.id) { mutableStateOf(false) }
            var rowHasFocus by remember(station.id) { mutableStateOf(false) }
            val selected = playbackId == station.id
            val favorite = station.id in favoriteIds
            val shape = RoundedCornerShape(10.dp)
            val focusModifier = when (station.id) {
                alphabetJumpId -> Modifier.focusRequester(alphabetReturnRequester)
                initialFocusId -> Modifier.focusRequester(initialFocusRequester)
                else -> Modifier
            }
            // The play target and the favorite button are siblings, as in LiveTV's DVR rows.
            Row(
                Modifier.fillMaxWidth()
                    .onFocusChanged { rowHasFocus = it.hasFocus }
                    .background(
                        if (rowHasFocus || selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                        else MaterialTheme.colorScheme.surface.copy(alpha = .58f),
                        shape,
                    )
                    .border(
                        if (focused || rowHasFocus || selected) 2.dp else 1.dp,
                        if (focused || rowHasFocus || selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .06f),
                        shape,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    onClick = { onClick(station) },
                    colors = transparentSurfaceColors(),
                    scale = noSurfaceScale(),
                    shape = ClickableSurfaceDefaults.shape(shape),
                    modifier = focusModifier.weight(1f)
                        .browseFocusTarget(station.id)
                        .mediaFocusMotion(focused, focusedScale = 1.012f)
                        .activateOnTvKey { onClick(station) }
                        .bringFocusedContextIntoView()
                        .onFocusChanged {
                            focused = it.isFocused
                            if (it.isFocused) onFocused(station)
                        },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(10.dp * rowScale),
                        horizontalArrangement = Arrangement.spacedBy(14.dp * rowScale),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(72.dp * rowScale)) {
                            RadioArtwork(station, Modifier.fillMaxSize())
                            if ((selected && playing) || startingStationId == station.id) {
                                androidx.compose.material3.Icon(
                                    Icons.Default.PlayArrow,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.align(Alignment.BottomEnd).size(22.dp),
                                )
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            MarqueeTitle(station.name, focused = rowHasFocus, style = MaterialTheme.typography.titleMedium)
                            MarqueeTitle(
                                radioStationSubtitle(station, LocalContext.current.resources.configuration.locales[0]),
                                focused = rowHasFocus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                RadioFavoriteAction(
                    favorite = favorite,
                    onClick = { onFavorite(station) },
                    onFocused = { onFocused(station) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        if (loadingMore) {
            items(RADIO_GRID_END_PLACEHOLDERS) {
                RadioListPlaceholder(isFacet = false, rowScale = rowScale, pulse = paginationPulse)
            }
        }
    }
}

@Composable
private fun RadioArtwork(station: RadioStation, modifier: Modifier) {
    if (!station.artworkUrl.isNullOrBlank()) {
        ArtworkImage(
            station.artworkUrl,
            station.name,
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.DarkGray),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier.background(Color.DarkGray, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(Icons.Default.MusicNote, null)
        }
    }
}
