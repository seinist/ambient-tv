@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.ambient.tv.ui.section

import com.ambient.tv.ui.common.glideToItem
import com.ambient.tv.ui.theme.screenGutter
import com.ambient.tv.ui.common.fillWindowHeight

import com.ambient.tv.ui.common.AlphabetBar

import com.ambient.tv.ui.mobile.LocalHandheldUi

import com.ambient.tv.ui.mobile.deviceGridCells
import com.ambient.tv.ui.mobile.deviceDensityGridCells

import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.ui.mobile.deviceHeroHeight

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import com.ambient.tv.ui.home.HomeHero
import com.ambient.tv.ui.home.HomeRowsLift
import com.ambient.tv.ui.home.fadeRowsBehindHomeHero
import com.ambient.tv.ui.home.toHomeHeroContent
import com.ambient.tv.ui.common.SoftViewChange
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.ambient.tv.ui.common.TvLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import com.ambient.tv.ui.common.TvLazyVerticalGrid as LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.items as gridItems
import com.ambient.tv.ui.common.rememberBrowseGridState as rememberLazyGridState
import com.ambient.tv.ui.common.rememberBrowseListState as rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Recommend
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import com.ambient.tv.ui.common.ExplicitRowScrollSpec
import com.ambient.tv.ui.common.rowColumnFocusNavigation
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ambient.tv.ui.common.CenteredMarqueeTitle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.CrossfadeBackdropImage
import com.ambient.tv.ui.common.TitleLogo
import com.ambient.tv.ui.common.acceleratedDpadNavigation
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.ContentRow
import com.ambient.tv.ui.common.ContentRowData
import com.ambient.tv.ui.common.ContentSectionHeader
import com.ambient.tv.ui.common.ContentRatingBadge
import com.ambient.tv.ui.common.PrimaryRatingScoreBadge
import com.ambient.tv.ui.common.MarqueeTitle
import com.ambient.tv.ui.common.MediaTile
import com.ambient.tv.ui.common.MediaTileStyle
import com.ambient.tv.ui.common.MediaArtworkWithStreamIcons
import com.ambient.tv.ui.common.StreamPropertyAdaptiveRows
import com.ambient.tv.ui.common.bringFocusedContextIntoView
import com.ambient.tv.ui.common.rememberRowCenteringState
import com.ambient.tv.ui.common.animateTvScrollToItem
import com.ambient.tv.ui.common.mediaFocusMotion
import com.ambient.tv.ui.common.FullscreenSpinner
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.common.sanitizeUiErrorMessage
import com.ambient.tv.ui.common.FilterBar
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.ViewOptionsChip
import com.ambient.tv.ui.common.overlayLazyGridScrollbar
import com.ambient.tv.ui.common.overlayLazyScrollbar
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.PageChipRow
import com.ambient.tv.ui.common.pageContentUp
import com.ambient.tv.ui.common.formatDuration
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.rememberUhdScale
import com.ambient.tv.ui.common.rememberArtworkAccent
import com.ambient.tv.ui.theme.LocalAccentModeKey
import com.ambient.tv.ui.common.transparentSurfaceColors
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import kotlinx.coroutines.delay


@Composable
fun SectionScreen(
    onItemClick: (String) -> Unit,
    onCollectionClick: (String, String) -> Unit,
    onOpenOverview: (com.ambient.tv.ui.common.MediaSystem) -> Unit,
    /** At section root (no options panel): focus sidebar like Files instead of popping to Home. */
    onBack: (() -> Unit)? = null,
    viewModel: SectionViewModel = hiltViewModel(),
) {
    com.ambient.tv.ui.common.RetryOfflineBrowse(viewModel::retryOffline)
    DisposableEffect(viewModel) { onDispose { viewModel.captureFocusForReturn() } }
    val uiState by viewModel.uiState.collectAsState()
    ReportSidebarContentReady(uiState !is SectionUiState.Loading)
    val refreshGeneration by viewModel.refreshGeneration.collectAsState()
    LaunchedEffect(refreshGeneration) {
        if (refreshGeneration > 0L) viewModel.load(force = true)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel.autoRefreshIntervalSeconds) {
        val seconds = viewModel.autoRefreshIntervalSeconds
        if (seconds > 0) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(seconds * 1_000L)
                viewModel.load(force = true)
            }
        }
    }
    // Loading/Error: same Files-style sidebar handoff (Loaded handles options vs root in SectionContent).
    if (onBack != null) {
        BackHandler(enabled = uiState !is SectionUiState.Loaded) { onBack() }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = uiState) {
            SectionUiState.Loading -> LibraryLoadingState(
                viewModel.loadingViewMode,
                viewModel.loadingGridColumns,
                viewModel.loadingListRows,
                viewModel.loadingPage,
            )
            is SectionUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                StatePanel(
                    title = stringResource(R.string.library_load_failed),
                    body = sanitizeUiErrorMessage(state.message) ?: stringResource(R.string.error_generic),
                    actionLabel = stringResource(R.string.home_retry),
                    onAction = viewModel::load,
                )
            }
            is SectionUiState.Loaded -> SectionContent(
                state = state,
                system = when {
                    com.ambient.tv.data.emby.EmbyBackend.owns(viewModel.sectionKey) -> com.ambient.tv.ui.common.MediaSystem.EMBY
                    com.ambient.tv.data.jellyfin.JellyfinBackend.owns(viewModel.sectionKey) -> com.ambient.tv.ui.common.MediaSystem.JELLYFIN
                    else -> com.ambient.tv.ui.common.MediaSystem.PLEX
                },
                onItemClick = { viewModel.captureFocusForReturn(); onItemClick(it) },
                onCollectionClick = { id, title -> viewModel.captureFocusForReturn(); onCollectionClick(id, title) },
                onOpenOverview = { viewModel.captureFocusForReturn(); onOpenOverview(it) },
                onBack = onBack?.let { back -> { viewModel.captureFocusForReturn(); back() } },
                onPage = viewModel::selectPage,
                onItemFocused = viewModel::onItemFocused,
                onRecommendationItemFocused = viewModel::onRecommendationItemFocused,
                onCollectionFocused = viewModel::onCollectionFocused,
                onSetSort = viewModel::setSort,
                onSetSortOrder = viewModel::setSortOrder,
                onSetFilterGroup = viewModel::setFilterGroup,
                onResetFilters = viewModel::resetFilters,
                onSetView = viewModel::setViewMode,
                onSetGridColumns = viewModel::setGridColumns,
                onSetListRows = viewModel::setListRows,
                onSelectLetter = viewModel::selectLetter,
            )
        }

        com.ambient.tv.ui.common.OfflineBrowseNotice(viewModel.availability, listOf(com.ambient.tv.data.browse.mediaProvider(viewModel.sectionKey)), Modifier.align(Alignment.BottomEnd).padding(12.dp))
    }
}

@Composable
internal fun SectionContent(
    state: SectionUiState.Loaded,
    system: com.ambient.tv.ui.common.MediaSystem,
    onItemClick: (String) -> Unit,
    onCollectionClick: (String, String) -> Unit,
    onOpenOverview: (com.ambient.tv.ui.common.MediaSystem) -> Unit,
    onBack: (() -> Unit)?,
    onPage: (SectionPage) -> Unit,
    onItemFocused: (MediaItem) -> Unit,
    onRecommendationItemFocused: (String, MediaItem) -> Unit,
    onCollectionFocused: (String) -> Unit,
    onSetSort: (SectionSort) -> Unit,
    onSetSortOrder: (SectionSortOrder) -> Unit,
    onSetFilterGroup: (String, String) -> Unit,
    onResetFilters: () -> Unit,
    onSetView: (SectionViewMode) -> Unit,
    onSetGridColumns: (Int) -> Unit,
    onSetListRows: (Int) -> Unit,
    onSelectLetter: (Char?) -> Unit,
) {
    var showOptions by remember { mutableStateOf(false) }
    var optionsOpened by remember { mutableStateOf(false) }
    val optionsFocusRequester = remember { FocusRequester() }
    var initialContentFocusPending by remember(state.page) { mutableStateOf(true) }
    val initialRecommendationRow = state.recommendationRows.firstOrNull { it.items.isNotEmpty() }
    val focusState = if (initialContentFocusPending) {
        when (state.page) {
            SectionPage.RECOMMENDATIONS -> {
                val savedExists = state.focusedRatingKey != null && state.recommendationRows.any { row ->
                    row.id == state.focusedRecommendationRowId &&
                        row.items.any { state.focusedRatingKey in it.allRatingKeys }
                }
                if (savedExists) state else state.copy(
                    focusedRatingKey = initialRecommendationRow?.items?.firstOrNull()?.ratingKey,
                    focusedRecommendationRowId = initialRecommendationRow?.id,
                )
            }
            SectionPage.LIBRARY -> {
                val savedExists = state.focusedRatingKey != null && state.items.any { state.focusedRatingKey in it.allRatingKeys }
                if (savedExists) state else state.copy(focusedRatingKey = state.items.firstOrNull()?.ratingKey)
            }
            SectionPage.COLLECTIONS -> {
                val savedExists = state.focusedCollectionId != null && state.collections.any { it.id == state.focusedCollectionId }
                if (savedExists) state else state.copy(focusedCollectionId = state.collections.firstOrNull()?.id)
            }
        }
    } else {
        state
    }
    // Entering the page from the sidebar lands on the last focused item, not on the header chips:
    // the content is rebuilt once so every view runs the same restore as after a detail page.
    val contentAreaFocus = remember { FocusRequester() }
    var contentAreaHasFocus by remember { mutableStateOf(false) }
    var contentEntryGeneration by remember { mutableIntStateOf(0) }
    var contentEntryPending by remember { mutableStateOf(false) }
    var contentEntryWaits by remember { mutableIntStateOf(0) }
    val restoreContentFocus = {
        contentEntryPending = true
        contentEntryWaits = 0
        contentEntryGeneration++
    }
    val enterContent: () -> Boolean? = remember {
        {
            when {
                contentAreaHasFocus -> true
                !contentEntryPending -> { restoreContentFocus(); null }
                // The sidebar asks once per frame; give the rebuilt view a few frames to restore.
                ++contentEntryWaits < 3 -> null
                // It did not take focus (e.g. empty): any content item will do.
                else -> { contentEntryPending = false; runCatching { contentAreaFocus.requestFocus() }.getOrDefault(false) }
            }
        }
    }
    DisposableEffect(enterContent) {
        com.ambient.tv.ui.navigation.SidebarContentEntry.enter = enterContent
        onDispose {
            if (com.ambient.tv.ui.navigation.SidebarContentEntry.enter === enterContent) {
                com.ambient.tv.ui.navigation.SidebarContentEntry.enter = null
            }
        }
    }
    val markInitialFocusHandled = { initialContentFocusPending = false }
    var visibleLibraryLetter by remember(state.items, state.page, state.sort, state.viewMode) {
        mutableStateOf(state.items.firstOrNull()?.let { libraryIndexLetter(it.title) })
    }
    // The bar follows the focused item, so moving sideways within a grid row updates it too.
    val onLibraryItemFocused: (MediaItem) -> Unit = { item ->
        markInitialFocusHandled()
        visibleLibraryLetter = libraryIndexLetter(item.title)
        onItemFocused(item)
    }
    BackHandler(enabled = showOptions) { showOptions = false }
    // Match Files root-Back: stay on route, hand focus to the sidebar.
    if (onBack != null) {
        BackHandler(enabled = !showOptions) { optionsOpened = false; onBack() }
    }
    LaunchedEffect(showOptions, optionsOpened) {
        if (!showOptions && optionsOpened) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { optionsFocusRequester.requestFocus() }
        }
    }

    // The recommendation rows' hero drives a full-screen backdrop like Home's, behind header and
    // tabs too, and hands the item to the shared background so its ambient light takes its colour.
    val recommendationHero = remember { MutableStateFlow<com.ambient.tv.ui.home.HomeHeroContent?>(null) }
    val heroBackdrop = !LocalHandheldUi.current && state.page == SectionPage.RECOMMENDATIONS &&
        recommendationViewMode(state.viewMode).let { it == SectionViewMode.POSTER || it == SectionViewMode.LANDSCAPE }
    val sharedBackgroundArtwork = com.ambient.tv.ui.common.LocalHomeBackgroundArtwork.current
    LaunchedEffect(heroBackdrop, sharedBackgroundArtwork) {
        if (heroBackdrop) recommendationHero.collect { hero -> if (hero != null) sharedBackgroundArtwork?.value = hero }
    }
    Box(Modifier.fillMaxSize()) {
        if (heroBackdrop) com.ambient.tv.ui.home.HomeFocusBackdrop(recommendationHero)
        Column(Modifier.fillMaxSize().focusProperties {
            onEnter = {
                // D-pad entry (e.g. Right out of the sidebar) restores like the sidebar handoff; plain
                // requests, such as a list restoring its item, pass.
                if (showOptions) {
                    cancelFocusChange()
                } else if (requestedFocusDirection != FocusDirection.Enter) {
                    // A second press while nothing was restored (e.g. empty view) enters normally.
                    if (contentEntryPending) {
                        contentEntryPending = false
                    } else {
                        restoreContentFocus()
                        cancelFocusChange()
                    }
                }
            }
        }.focusGroup()) {
            val focusChain = com.ambient.tv.ui.common.rememberPageFocusChain()
            val filterFocus = remember { FocusRequester() }
            val filterBarShown = state.page != SectionPage.RECOMMENDATIONS && com.ambient.tv.ui.common.LocalFilterBarSetting.current.shown
            SectionHeader(
                focusState,
                onPage,
                focusChain = focusChain,
                system = system,
                onOpenOverview = { onOpenOverview(system) },
                onOpenOptions = {
                    optionsOpened = true
                    showOptions = true
                },
                optionsModifier = Modifier.focusRequester(optionsFocusRequester),
                filterFocus = filterFocus,
                filterUp = focusChain.chips,
                onSetSort = onSetSort,
                onSetSortOrder = onSetSortOrder,
                onSetFilterGroup = onSetFilterGroup,
                onResetFilters = onResetFilters,
            )
            // Cinematic has no scrollable grid for the bar to jump in.
            val showAlphabetBar = state.page == SectionPage.LIBRARY &&
                state.viewMode != SectionViewMode.CINEMATIC && state.viewMode != SectionViewMode.POSTER_CINEMATIC &&
                state.sort == SectionSort.TITLE &&
                state.availableLetters.size > 5
            val libraryFocusRequester = remember { FocusRequester() }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .focusRequester(contentAreaFocus)
                    .onFocusChanged { contentAreaHasFocus = it.hasFocus; if (it.hasFocus) contentEntryPending = false }
                    .pageContentUp(focusChain) { if (filterBarShown) filterFocus else focusChain.contentUp }
                    .focusGroup()
                    .acceleratedDpadNavigation(enabled = !LocalHandheldUi.current),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (showAlphabetBar) {
                                Modifier.focusRequester(libraryFocusRequester).focusGroup()
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    // The alphabet bar already shows the position; a scrollbar would sit under it.
                    CompositionLocalProvider(LocalAlphabetBarShown provides showAlphabetBar) {
                        key(contentEntryGeneration) {
                            SoftViewChange(state.viewMode, Modifier.fillMaxSize()) { when (state.page) {
                            SectionPage.RECOMMENDATIONS -> RecommendationsPage(
                                focusState,
                                onItemClick,
                                { rowId, item -> markInitialFocusHandled(); onRecommendationItemFocused(rowId, item) },
                                recommendationHero,
                            )
                            SectionPage.LIBRARY -> {
                                if (state.items.isEmpty()) EmptyLibrary(!state.libraryFilters.isDefault, onResetFilters) else when (state.viewMode) {
                                    SectionViewMode.POSTER, SectionViewMode.GRID -> LibraryGrid(focusState, onItemClick, onLibraryItemFocused, onVisibleLetterChanged = {})
                                    SectionViewMode.LANDSCAPE, SectionViewMode.ROWS -> LandscapeGrid(focusState.items, focusState.focusedRatingKey, focusState.gridColumns, onItemClick, onLibraryItemFocused, focusState.selectedLetter)
                                    SectionViewMode.LIST, SectionViewMode.POSTER_LIST -> LibraryList(focusState, onItemClick, onLibraryItemFocused, onVisibleLetterChanged = {}, poster = state.viewMode == SectionViewMode.POSTER_LIST)
                                    SectionViewMode.INFO_LIST, SectionViewMode.POSTER_INFO_LIST -> InfoList(focusState.items, focusState.focusedRatingKey, focusState.listRows, onItemClick, onLibraryItemFocused, focusState.selectedLetter, poster = state.viewMode == SectionViewMode.POSTER_INFO_LIST)
                                    SectionViewMode.WALL, SectionViewMode.POSTER_WALL -> WallGrid(focusState.items, focusState.focusedRatingKey, focusState.gridColumns, onItemClick, onLibraryItemFocused, focusState.selectedLetter, poster = state.viewMode == SectionViewMode.POSTER_WALL)
                                    SectionViewMode.CINEMATIC, SectionViewMode.POSTER_CINEMATIC -> CinematicView(focusState.items, focusState.focusedRatingKey, onItemClick, poster = state.viewMode == SectionViewMode.POSTER_CINEMATIC) { item -> markInitialFocusHandled(); onItemFocused(item) }
                                }
                            }
                            SectionPage.COLLECTIONS -> {
                                if (state.collectionsLoading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    FullscreenSpinner()
                                } else if (state.collections.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    StatePanel(stringResource(R.string.collections_empty_title), stringResource(R.string.collections_empty_body))
                                } else when (state.viewMode) {
                                    SectionViewMode.POSTER, SectionViewMode.GRID, SectionViewMode.WALL, SectionViewMode.POSTER_WALL -> CollectionGrid(focusState, onCollectionClick) { id -> markInitialFocusHandled(); onCollectionFocused(id) }
                                    else -> CollectionList(focusState, onCollectionClick) { id -> markInitialFocusHandled(); onCollectionFocused(id) }
                                }
                            }
                        } }
                        }
                    }
                }
                if (showAlphabetBar) {
                    AlphabetBar(
                        letters = state.availableLetters,
                        selected = visibleLibraryLetter,
                        onSelect = { letter -> visibleLibraryLetter = letter; onSelectLetter(letter) },
                        gridFocus = libraryFocusRequester,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        }

        if (showOptions) Box(Modifier.fillWindowHeight().fillMaxSize().background(Color.Black.copy(alpha = 0.38f)))
        if (showOptions) Box(Modifier.align(Alignment.TopEnd)) {
            SectionOptionsPanel(
                state = state,
                onSetSort = onSetSort,
                onSetSortOrder = onSetSortOrder,
                onSetFilterGroup = onSetFilterGroup,
                onResetFilters = onResetFilters,
                onSetView = onSetView,
                onSetGridColumns = onSetGridColumns,
                onSetListRows = onSetListRows,
                onClose = { showOptions = false },
            )
        }
    }
}

@Composable
private fun SectionHeader(
    state: SectionUiState.Loaded,
    onPage: (SectionPage) -> Unit,
    onOpenOptions: () -> Unit,
    onOpenOverview: () -> Unit,
    system: com.ambient.tv.ui.common.MediaSystem,
    focusChain: com.ambient.tv.ui.common.PageFocusChain,
    optionsModifier: Modifier = Modifier,
    filterFocus: FocusRequester? = null,
    filterUp: FocusRequester? = null,
    onSetSort: (SectionSort) -> Unit = {},
    onSetSortOrder: (SectionSortOrder) -> Unit = {},
    onSetFilterGroup: (String, String) -> Unit = { _, _ -> },
    onResetFilters: () -> Unit = {},
) {
    @Composable fun PageChip(label: String, page: SectionPage, icon: androidx.compose.ui.graphics.vector.ImageVector) {
        val selected = state.page == page
        TvChip(label, selected, modifier = if (selected) Modifier.focusRequester(focusChain.selectedChip) else Modifier, icon = icon) { onPage(page) }
    }
    Column(Modifier.fillMaxWidth().padding(start = screenGutter(), end = screenGutter(), top = com.ambient.tv.ui.common.pageHeaderTop(24.dp), bottom = 10.dp)) {
        PageHeader(focusChain = focusChain, actions = {
            // Jumps straight to the owning system's overview, sparing the detour through the sidebar.
            TvChip(stringResource(R.string.nav_overview), icon = Icons.Filled.Home, iconOnly = true, onClick = onOpenOverview)
            ViewOptionsChip(
                if (com.ambient.tv.ui.common.LocalFilterBarSetting.current.shown || state.page != SectionPage.LIBRARY) 0 else state.libraryFilters.activeCount,
                onOpenOptions, optionsModifier,
            )
        }) {
            com.ambient.tv.ui.common.SystemPageTitle(system, title = state.title)
            val context = buildList {
                add(
                    when (state.page) {
                        SectionPage.RECOMMENDATIONS -> stringResource(R.string.library_tab_recommendations)
                        SectionPage.LIBRARY -> stringResource(R.string.library_item_count, state.items.size)
                        SectionPage.COLLECTIONS -> stringResource(R.string.collection_item_count, state.collections.size)
                    },
                )
            }
            Text(
                text = context.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PageChipRow(focusChain, Modifier.fillMaxWidth().padding(top = 12.dp), PaddingValues(horizontal = 2.dp)) {
            item { PageChip(stringResource(R.string.library_tab_recommendations), SectionPage.RECOMMENDATIONS, Icons.Filled.Recommend) }
            item { PageChip(stringResource(R.string.library_tab_library), SectionPage.LIBRARY, Icons.Filled.VideoLibrary) }
            item { PageChip(stringResource(R.string.library_tab_collections), SectionPage.COLLECTIONS, Icons.Filled.CollectionsBookmark) }
        }
        if (state.page != SectionPage.RECOMMENDATIONS && com.ambient.tv.ui.common.LocalFilterBarSetting.current.shown) FilterBar(
            groups = if (state.page == SectionPage.LIBRARY) libraryFilterGroups(state) else emptyList(),
            onSelect = onSetFilterGroup,
            modifier = Modifier.padding(top = 10.dp),
            sorts = SectionSort.entries.map { com.ambient.tv.ui.common.PanelOption(it.name, sortLabel(it)) },
            selectedSort = state.sort.name, onSort = { onSetSort(SectionSort.valueOf(it)) },
            sortOrders = sortOrderOptions(state.sort),
            selectedSortOrder = state.sortOrder.name, onSortOrder = { onSetSortOrder(SectionSortOrder.valueOf(it)) },
            onReset = onResetFilters.takeIf { state.page == SectionPage.LIBRARY },
            focusRequester = filterFocus,
            up = filterUp,
        )
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SectionOptionsPanel(
    state: SectionUiState.Loaded,
    onSetSort: (SectionSort) -> Unit,
    onSetSortOrder: (SectionSortOrder) -> Unit,
    onSetFilterGroup: (String, String) -> Unit,
    onResetFilters: () -> Unit,
    onSetView: (SectionViewMode) -> Unit,
    onSetGridColumns: (Int) -> Unit,
    onSetListRows: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = when (state.page) {
        SectionPage.COLLECTIONS -> listOf(SectionViewMode.POSTER, SectionViewMode.LIST)
        SectionPage.RECOMMENDATIONS, SectionPage.LIBRARY -> SELECTABLE_VIEW_MODES
    }
    // Old persisted GRID/ROWS keys and collection fallbacks describe the same visible layouts.
    val selectedMode = when (state.page) {
        SectionPage.COLLECTIONS ->
            if (state.viewMode in setOf(SectionViewMode.POSTER, SectionViewMode.GRID, SectionViewMode.WALL, SectionViewMode.POSTER_WALL)) SectionViewMode.POSTER else SectionViewMode.LIST
        SectionPage.RECOMMENDATIONS -> recommendationViewMode(state.viewMode)
        SectionPage.LIBRARY -> when (state.viewMode) {
            SectionViewMode.GRID -> SectionViewMode.POSTER
            SectionViewMode.ROWS -> SectionViewMode.LANDSCAPE
            else -> state.viewMode
        }
    }
    com.ambient.tv.ui.common.ViewOptionsPanel(
        showDensity = selectedMode != SectionViewMode.CINEMATIC && selectedMode != SectionViewMode.POSTER_CINEMATIC,
        views = modes.map { mode -> com.ambient.tv.ui.common.ViewOption(mode.name, stringResource(when (mode) {
            SectionViewMode.POSTER -> R.string.library_view_poster
            SectionViewMode.LANDSCAPE -> R.string.library_view_landscape
            SectionViewMode.LIST -> R.string.library_view_list
            SectionViewMode.INFO_LIST -> R.string.library_view_info_list
            SectionViewMode.WALL -> R.string.library_view_wall
            SectionViewMode.CINEMATIC -> R.string.library_view_cinematic
            SectionViewMode.GRID -> R.string.library_view_grid
            SectionViewMode.ROWS -> R.string.library_view_rows
            SectionViewMode.POSTER_LIST -> R.string.library_view_poster_list
            SectionViewMode.POSTER_INFO_LIST -> R.string.library_view_poster_info_list
            SectionViewMode.POSTER_WALL -> R.string.library_view_poster_wall
            SectionViewMode.POSTER_CINEMATIC -> R.string.library_view_poster_cinematic
        }), com.ambient.tv.ui.common.ViewPreview.valueOf(mode.name)) },
        selectedView = selectedMode.name, onView = { onSetView(SectionViewMode.valueOf(it)) },
        gridColumns = state.gridColumns, listRows = state.listRows,
        onGridColumns = onSetGridColumns, onListRows = onSetListRows, onClose = onClose, modifier = modifier,
        sorts = if (state.page == SectionPage.RECOMMENDATIONS) emptyList() else SectionSort.entries.map { com.ambient.tv.ui.common.PanelOption(it.name, sortLabel(it)) },
        selectedSort = state.sort.name, onSort = { onSetSort(SectionSort.valueOf(it)) },
        sortOrders = if (state.page == SectionPage.RECOMMENDATIONS) emptyList() else sortOrderOptions(state.sort),
        selectedSortOrder = state.sortOrder.name, onSortOrder = { onSetSortOrder(SectionSortOrder.valueOf(it)) },
        filterGroups = if (state.page != SectionPage.LIBRARY) emptyList() else libraryFilterGroups(state),
        onFilterGroup = onSetFilterGroup,
        filtersActive = state.page == SectionPage.LIBRARY && !state.libraryFilters.isDefault,
        onResetFilters = onResetFilters.takeIf { state.page == SectionPage.LIBRARY },
    )
}

@Composable private fun sortLabel(sort: SectionSort) = stringResource(when (sort) {
    SectionSort.TITLE -> R.string.library_sort_title
    SectionSort.YEAR -> R.string.library_sort_year
    SectionSort.RELEASE_DATE -> R.string.library_sort_release
    SectionSort.RECENTLY_ADDED -> R.string.library_sort_recent
    SectionSort.RECENTLY_VIEWED -> R.string.library_sort_viewed
    SectionSort.RATING -> R.string.library_sort_rating
    SectionSort.CRITIC_RATING -> R.string.library_sort_critic
    SectionSort.AUDIENCE_RATING -> R.string.library_sort_audience
    SectionSort.DURATION -> R.string.library_sort_duration
    SectionSort.PLAYS -> R.string.library_sort_plays
})

@Composable private fun sortOrderLabel(sort: SectionSort, order: SectionSortOrder) = stringResource(when (sort) {
    SectionSort.TITLE -> if (order == SectionSortOrder.ASCENDING) R.string.library_sort_az else R.string.library_sort_za
    SectionSort.DURATION -> if (order == SectionSortOrder.DESCENDING) R.string.library_sort_longest else R.string.library_sort_shortest
    SectionSort.RATING, SectionSort.CRITIC_RATING, SectionSort.AUDIENCE_RATING, SectionSort.PLAYS ->
        if (order == SectionSortOrder.DESCENDING) R.string.library_sort_highest else R.string.library_sort_lowest
    else -> if (order == SectionSortOrder.DESCENDING) R.string.library_sort_newest else R.string.library_sort_oldest
})

@Composable private fun sortOrderOptions(sort: SectionSort): List<com.ambient.tv.ui.common.PanelOption> {
    val primary = if (sort == SectionSort.TITLE) SectionSortOrder.ASCENDING else SectionSortOrder.DESCENDING
    val secondary = if (primary == SectionSortOrder.ASCENDING) SectionSortOrder.DESCENDING else SectionSortOrder.ASCENDING
    return listOf(
        com.ambient.tv.ui.common.PanelOption(primary.name, sortOrderLabel(sort, primary)),
        com.ambient.tv.ui.common.PanelOption(secondary.name, sortOrderLabel(sort, secondary)),
    )
}

@Composable private fun filterLabel(filter: SectionFilter) = stringResource(when (filter) {
    SectionFilter.ALL -> R.string.library_filter_all
    SectionFilter.UNWATCHED -> R.string.library_filter_unwatched
    SectionFilter.IN_PROGRESS -> R.string.library_filter_in_progress
    SectionFilter.WATCHED -> R.string.library_filter_watched
})

@Composable private fun qualityLabel(filter: SectionQualityFilter) = stringResource(when (filter) {
    SectionQualityFilter.ALL -> R.string.library_filter_all
    SectionQualityFilter.UHD -> R.string.library_filter_4k
    SectionQualityFilter.HD -> R.string.library_filter_hd
    SectionQualityFilter.SD -> R.string.library_filter_sd
    SectionQualityFilter.HDR -> R.string.library_filter_hdr
})

@Composable private fun decadeLabel(decade: Int) = stringResource(R.string.library_filter_decade_value, decade)

@Composable private fun libraryFilterGroups(state: SectionUiState.Loaded): List<com.ambient.tv.ui.common.PanelOptionGroup> {
    val all = stringResource(R.string.library_filter_all)
    val facets = state.filterFacets
    return buildList {
        add(
            com.ambient.tv.ui.common.PanelOptionGroup(
                id = SectionViewModel.FILTER_WATCH,
                title = stringResource(R.string.library_filter),
                options = SectionFilter.entries.map { com.ambient.tv.ui.common.PanelOption(it.name, filterLabel(it)) },
                selectedKey = state.filter.name,
            ),
        )
        if (facets.qualities.isNotEmpty()) {
            add(
                com.ambient.tv.ui.common.PanelOptionGroup(
                    id = SectionViewModel.FILTER_QUALITY,
                    title = stringResource(R.string.library_filter_quality),
                    options = (listOf(SectionQualityFilter.ALL) + facets.qualities).map {
                        com.ambient.tv.ui.common.PanelOption(it.name, qualityLabel(it))
                    },
                    selectedKey = state.qualityFilter.name,
                ),
            )
        }
        if (facets.genres.isNotEmpty()) {
            add(
                com.ambient.tv.ui.common.PanelOptionGroup(
                    id = SectionViewModel.FILTER_GENRE,
                    title = stringResource(R.string.library_filter_genre),
                    options = listOf(com.ambient.tv.ui.common.PanelOption("ALL", all)) +
                        facets.genres.map { com.ambient.tv.ui.common.PanelOption(it, it) },
                    selectedKey = state.genreFilter ?: "ALL",
                ),
            )
        }
        if (facets.decades.isNotEmpty()) {
            add(
                com.ambient.tv.ui.common.PanelOptionGroup(
                    id = SectionViewModel.FILTER_DECADE,
                    title = stringResource(R.string.library_filter_decade),
                    options = listOf(com.ambient.tv.ui.common.PanelOption("ALL", all)) +
                        facets.decades.map { com.ambient.tv.ui.common.PanelOption(it.toString(), decadeLabel(it)) },
                    selectedKey = state.decadeFilter?.toString() ?: "ALL",
                ),
            )
        }
        if (facets.contentRatings.isNotEmpty()) {
            add(
                com.ambient.tv.ui.common.PanelOptionGroup(
                    id = SectionViewModel.FILTER_CONTENT_RATING,
                    title = stringResource(R.string.library_filter_content_rating),
                    options = listOf(com.ambient.tv.ui.common.PanelOption("ALL", all)) +
                        facets.contentRatings.map { com.ambient.tv.ui.common.PanelOption(it, it) },
                    selectedKey = state.contentRatingFilter ?: "ALL",
                ),
            )
        }
    }
}

@Composable
private fun RecommendationsPage(
    state: SectionUiState.Loaded,
    onItemClick: (String) -> Unit,
    onRecommendationItemFocused: (String, MediaItem) -> Unit,
    heroContent: MutableStateFlow<com.ambient.tv.ui.home.HomeHeroContent?>,
) {
    if (state.recommendationRows.isEmpty()) { EmptyLibrary(filtered = false, onReset = {}); return }
    when (recommendationViewMode(state.viewMode)) {
        SectionViewMode.POSTER, SectionViewMode.LANDSCAPE -> RecommendationRowsPage(state, onItemClick, onRecommendationItemFocused, heroContent)
        SectionViewMode.LIST -> RecommendationListPage(state, onItemClick, onRecommendationItemFocused, detailed = false)
        SectionViewMode.INFO_LIST -> RecommendationListPage(state, onItemClick, onRecommendationItemFocused, detailed = true)
        SectionViewMode.POSTER_LIST -> RecommendationListPage(state, onItemClick, onRecommendationItemFocused, detailed = false, poster = true)
        SectionViewMode.POSTER_INFO_LIST -> RecommendationListPage(state, onItemClick, onRecommendationItemFocused, detailed = true, poster = true)
        SectionViewMode.WALL -> RecommendationWallPage(state, onItemClick, onRecommendationItemFocused)
        SectionViewMode.POSTER_WALL -> RecommendationWallPage(state, onItemClick, onRecommendationItemFocused, poster = true)
        SectionViewMode.CINEMATIC -> RecommendationCinematicPage(state, onItemClick, onRecommendationItemFocused)
        SectionViewMode.POSTER_CINEMATIC -> RecommendationCinematicPage(state, onItemClick, onRecommendationItemFocused, poster = true)
        SectionViewMode.ROWS, SectionViewMode.GRID -> Unit // normalized by recommendationViewMode()
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun RecommendationRowsPage(
    state: SectionUiState.Loaded,
    onItemClick: (String) -> Unit,
    onRecommendationItemFocused: (String, MediaItem) -> Unit,
    heroContent: MutableStateFlow<com.ambient.tv.ui.home.HomeHeroContent?>,
) {
    val tileStyle = if (recommendationViewMode(state.viewMode) == SectionViewMode.POSTER) MediaTileStyle.POSTER else MediaTileStyle.LANDSCAPE
    val initialFocusKey = remember { state.focusedRatingKey }
    val initialFocusRowId = remember { state.focusedRecommendationRowId }
    var restoreArmed by remember { mutableStateOf(initialFocusKey != null) }
    val restoreRowIndex = remember(state.recommendationRows, initialFocusRowId, initialFocusKey) {
        val key = initialFocusKey
        state.recommendationRows.indexOfFirst { row ->
            row.id == initialFocusRowId && key != null && row.items.any { key in it.allRatingKeys }
        }.takeIf { it >= 0 }
            ?: state.recommendationRows.indexOfFirst { row -> key != null && row.items.any { key in it.allRatingKeys } }.coerceAtLeast(0)
    }
    val rowsState = rememberLazyListState(initialFirstVisibleItemIndex = restoreRowIndex)
    val rowCentering = rememberRowCenteringState(rowsState)
    var centeredRowIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(centeredRowIndex) {
        if (centeredRowIndex >= 0) rowCentering.centerItem(centeredRowIndex, alignTop = true)
    }
    val handheld = LocalHandheldUi.current
    val rowScrollSpec = if (handheld) LocalBringIntoViewSpec.current else ExplicitRowScrollSpec
    val columnFocus = com.ambient.tv.ui.common.rememberRowColumnFocus()
    // On TV the focused item gets the Home hero over the rows, so the list keeps the full height.
    // A poster row is taller than the band under a stacked hero, and Down then never reaches
    // the next row. The hero only swaps text, never the rows.
    remember(state.recommendationRows) {
        val initial = state.recommendationRows.firstNotNullOfOrNull { row ->
            row.items.firstOrNull { item -> initialFocusKey != null && initialFocusKey in item.allRatingKeys }
        } ?: state.recommendationRows.firstOrNull()?.items?.firstOrNull()
        // Set during composition so the hero has its item on the first frame.
        heroContent.value = initial?.toHomeHeroContent()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = if (handheld) 0.dp else maxHeight * 0.42f
        val heroInsetPx = with(LocalDensity.current) { (heroHeight - HomeRowsLift).roundToPx().coerceAtLeast(0) }
        val performance = LocalPerformanceProfile.current
        Box(Modifier.fillMaxSize()) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalBringIntoViewSpec provides rowScrollSpec,
                com.ambient.tv.ui.common.LocalHeroRowTiles provides !handheld,
            ) {
                LazyColumn(
                    allowUpExit = true,
                    state = rowsState,
                    modifier = Modifier
                        .fillMaxSize()
                        .rowColumnFocusNavigation(
                            columnFocus,
                            scrollToTop = { rowsState.glideToItem(!performance.reducedEffects, 0) },
                            scrollToBottom = { rowsState.glideToItem(!performance.reducedEffects, state.recommendationRows.lastIndex) },
                            scrollToRow = { rowKey ->
                                val index = state.recommendationRows.indexOfFirst { it.id == rowKey }
                                if (index >= 0) rowCentering.centerItem(index, topInset = heroInsetPx, alignTop = true)
                            },
                        ) { state.recommendationRows.map { it.id } }
                        .clipToBounds()
                        .then(if (handheld) Modifier else Modifier.fadeRowsBehindHomeHero(performance.allowRowFadeSaveLayer, heroHeight)),
                    contentPadding = PaddingValues(
                        top = if (handheld) 8.dp else heroHeight - HomeRowsLift + 12.dp,
                        bottom = 36.dp,
                    ),
                ) {
                    itemsIndexed(state.recommendationRows, key = { _, row -> row.id }) { index, row ->
                        ContentRow(
                            row = ContentRowData(title = row.title, items = row.items, style = tileStyle, id = row.id, showDescription = false),
                            onItemClick = { onItemClick(it.ratingKey) },
                            onItemFocused = { item ->
                                restoreArmed = false
                                heroContent.value = item.toHomeHeroContent()
                                onRecommendationItemFocused(row.id, item)
                            },
                            onRowFocused = { centeredRowIndex = index },
                            restoreRatingKey = if (restoreArmed && index == restoreRowIndex) initialFocusKey else null,
                            columnFocus = columnFocus,
                        )
                    }
                }
            }
            if (!handheld) {
                HomeHero(
                    heroContent = heroContent,
                    heroStyleKey = "",
                    intensityPercent = 0,
                    spreadKey = "",
                    entranceProgress = { 1f },
                    drawAmbient = false,
                    height = heroHeight,
                    showSystemBadge = false,
                    modifier = Modifier.align(Alignment.TopCenter).zIndex(1f),
                )
            }
        }
    }
}

@Composable
private fun RecommendationListPage(
    state: SectionUiState.Loaded,
    onItemClick: (String) -> Unit,
    onRecommendationItemFocused: (String, MediaItem) -> Unit,
    detailed: Boolean,
    poster: Boolean = false,
) {
    val initialKey = remember(state.recommendationRows) { state.focusedRatingKey }
    val initialRowId = remember(state.recommendationRows) { state.focusedRecommendationRowId }
    val restoreRowId = remember(state.recommendationRows, initialRowId, initialKey) {
        initialRowId?.takeIf { id -> state.recommendationRows.any { it.id == id && initialKey != null && it.items.any { item -> initialKey in item.allRatingKeys } } }
            ?: state.recommendationRows.firstOrNull { row -> initialKey != null && row.items.any { initialKey in it.allRatingKeys } }?.id
    }
    val rowScale = (7f / state.listRows.coerceIn(4, 10)).coerceIn(0.72f, 1.75f) * rememberUhdScale()
    val listState = rememberLazyListState()
    LazyColumn(
        allowUpExit = true,
        state = listState,
        modifier = Modifier.fillMaxSize().overlayLazyScrollbar(listState, gutter = screenGutter()),
        contentPadding = PaddingValues(screenGutter(), 10.dp, screenGutter(), 36.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp * rowScale),
    ) {
        state.recommendationRows.forEachIndexed { sectionIndex, row ->
            item(key = "recommendation-list-header:${row.id}", contentType = "recommendation-header") {
                ContentSectionHeader(
                    title = row.title,
                    modifier = Modifier.padding(top = if (sectionIndex == 0) 0.dp else 12.dp),
                    startPadding = 0.dp,
                    bottomPadding = 2.dp,
                )
            }
            itemsIndexed(row.items, key = { itemIndex, item -> "recommendation-list:${row.id}:$itemIndex:${item.ratingKey}" }, contentType = { _, _ -> "recommendation-list-item" }) { itemIndex, item ->
                RecommendationListItem(
                    item = item,
                    rowScale = rowScale,
                    detailed = detailed,
                    poster = poster,
                    onClick = { onItemClick(item.ratingKey) },
                    onFocused = { onRecommendationItemFocused(row.id, item) },
                    requestInitialFocus = (row.id == restoreRowId && item.ratingKey == initialKey) ||
                        (initialKey == null && sectionIndex == 0 && itemIndex == 0),
                )
            }
        }
    }
}

@Composable
private fun RecommendationListItem(
    item: MediaItem,
    rowScale: Float,
    detailed: Boolean,
    poster: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    requestInitialFocus: Boolean,
) {
    var focused by remember(item.ratingKey) { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val handheldUi = LocalHandheldUi.current
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus && !handheldUi) {
            delay(120)
            runCatching { requester.requestFocus() }
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (requestInitialFocus) Modifier.focusRequester(requester) else Modifier)
            .mediaFocusMotion(focused, focusedScale = 1.012f)
            .activateOnTvKey(onClick = onClick)
            .bringFocusedContextIntoView()
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.58f), shape)
                .border(if (focused) 1.5.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.06f), shape)
                .padding(10.dp * rowScale),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp * rowScale),
        ) {
            MediaArtworkWithStreamIcons(
                item = item,
                model = if (poster) item.posterArtUrl else item.landscapeArtUrl,
                modifier = if (poster) {
                    Modifier.width(deviceDp(if (detailed) 84.dp else 60.dp, 52.dp) * rowScale).aspectRatio(2f / 3f).clip(RoundedCornerShape(7.dp))
                } else {
                    Modifier.width(deviceDp(if (detailed) 190.dp else 128.dp, 104.dp) * rowScale).aspectRatio(16f / 9f).clip(RoundedCornerShape(9.dp))
                },
                maxItems = if (detailed) 4 else 2,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = if (detailed) 2 else 1, overflow = TextOverflow.Ellipsis)
                val meta = buildList { item.year?.let { add(it.toString()) }; formatDuration(item.durationMs)?.let(::add) }.joinToString(" · ")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    item.contentRating?.takeIf { it.isNotBlank() }?.let { ContentRatingBadge(it) }
                    if (detailed && (item.rating != null || item.audienceRating != null || item.criticRating != null)) PrimaryRatingScoreBadge(item)
                }
                if (detailed) item.summary?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RecommendationWallPage(
    state: SectionUiState.Loaded,
    onItemClick: (String) -> Unit,
    onRecommendationItemFocused: (String, MediaItem) -> Unit,
    poster: Boolean = false,
) {
    val wallColumns = if (poster) coverWallColumns(state.gridColumns) else (state.gridColumns + 2).coerceIn(6, 14)
    // Captured once: the focused key changes with every D-pad step and must not re-request focus.
    val initialKey = remember { state.focusedRatingKey }
    val initialRowId = remember { state.focusedRecommendationRowId }
    val restoreRowId = remember(state.recommendationRows, initialRowId, initialKey) {
        initialRowId?.takeIf { id -> state.recommendationRows.any { it.id == id && initialKey != null && it.items.any { item -> initialKey in item.allRatingKeys } } }
            ?: state.recommendationRows.firstOrNull { row -> initialKey != null && row.items.any { initialKey in it.allRatingKeys } }?.id
    }
    LazyVerticalGrid(
        columns = deviceGridCells(wallColumns, if (poster) 96.dp else 144.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(screenGutter(), 10.dp, deviceDp(52.dp, 16.dp), 34.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.recommendationRows.forEachIndexed { sectionIndex, row ->
            item(key = "recommendation-wall-header:${row.id}", contentType = "recommendation-header", span = { GridItemSpan(maxLineSpan) }) {
                ContentSectionHeader(
                    title = row.title,
                    modifier = Modifier.padding(top = if (sectionIndex == 0) 0.dp else 14.dp),
                    startPadding = 0.dp,
                    bottomPadding = 4.dp,
                )
            }
            itemsIndexed(row.items, key = { itemIndex, item -> "recommendation-wall:${row.id}:$itemIndex:${item.ratingKey}" }, contentType = { _, _ -> "recommendation-wall-item" }) { itemIndex, item ->
                WallTile(
                    item = item,
                    poster = poster,
                    onClick = { onItemClick(item.ratingKey) },
                    onFocused = { onRecommendationItemFocused(row.id, item) },
                    requestInitialFocus = (row.id == restoreRowId && item.ratingKey == initialKey) ||
                        (initialKey == null && sectionIndex == 0 && itemIndex == 0),
                )
            }
        }
    }
}

@Composable
private fun WallTile(
    item: MediaItem,
    poster: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    requestInitialFocus: Boolean,
    modifier: Modifier = Modifier,
) {
    var focused by remember(item.ratingKey) { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val handheldUi = LocalHandheldUi.current
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus && !handheldUi) {
            delay(120)
            runCatching { requester.requestFocus() }
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape),
        modifier = modifier
            .then(if (requestInitialFocus) Modifier.focusRequester(requester) else Modifier)
            .mediaFocusMotion(focused)
            .activateOnTvKey(onClick = onClick)
            .bringFocusedContextIntoView()
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (poster) 2f / 3f else 16f / 9f)
                .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.06f), shape),
        ) {
            MediaArtworkWithStreamIcons(
                item = item,
                model = if (poster) item.posterArtUrl else item.landscapeArtUrl,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                maxItems = 3,
            )
            if (focused) {
                Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.72f)).padding(7.dp)) {
                    CenteredMarqueeTitle(item.title, focused = focused, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun RecommendationCinematicPage(
    state: SectionUiState.Loaded,
    onItemClick: (String) -> Unit,
    onRecommendationItemFocused: (String, MediaItem) -> Unit,
    poster: Boolean = false,
) {
    val initialKey = remember(state.recommendationRows) { state.focusedRatingKey }
    var restorePending by remember(state.recommendationRows) { mutableStateOf(true) }
    val initialRowId = remember(state.recommendationRows) { state.focusedRecommendationRowId }
    val restoreRowId = remember(state.recommendationRows, initialRowId, initialKey) {
        initialRowId?.takeIf { id -> state.recommendationRows.any { it.id == id && initialKey != null && it.items.any { item -> initialKey in item.allRatingKeys } } }
            ?: state.recommendationRows.firstOrNull { row -> initialKey != null && row.items.any { initialKey in it.allRatingKeys } }?.id
    }
    val handheld = LocalHandheldUi.current
    val restoreIndex = remember(state.recommendationRows, restoreRowId) { state.recommendationRows.indexOfFirst { it.id == restoreRowId }.coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = restoreIndex)
    // On TV each section fills the viewport and the list snaps to the section that holds focus,
    // so header, hero and tile row are always shown whole, in both scroll directions.
    var focusedSectionIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(focusedSectionIndex) {
        if (focusedSectionIndex >= 0 && !handheld) listState.animateTvScrollToItem(focusedSectionIndex)
    }
    LazyColumn(
        allowUpExit = true,
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = if (handheld) 36.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(state.recommendationRows, key = { _, row -> "recommendation-cinematic:${row.id}" }) { sectionIndex, row ->
            RecommendationCinematicSection(
                row = row,
                focusedKey = initialKey.takeIf { restoreRowId == row.id },
                onItemClick = onItemClick,
                onItemFocused = { item ->
                    restorePending = false
                    focusedSectionIndex = sectionIndex
                    onRecommendationItemFocused(row.id, item)
                },
                requestFallbackFocus = initialKey == null && sectionIndex == 0,
                restorePending = restorePending,
                poster = poster,
                modifier = if (handheld) Modifier else Modifier.fillParentMaxHeight(),
            )
        }
    }
}

@Composable
private fun CinematicHeroBackdrop(
    artworkUrl: String?,
    title: String,
    accent: Color,
    fadeBottom: Boolean = false,
) {
    val background = MaterialTheme.colorScheme.background
    if (com.ambient.tv.ui.common.LocalHomeBackgroundVisible.current || !com.ambient.tv.ui.common.LocalFocusArtworkBackdrop.current) return
    // The whole backdrop (artwork and its scrims) fades as one layer, so no scrim edge is left behind.
    val allowSaveLayer = com.ambient.tv.ui.performance.LocalPerformanceProfile.current.allowRowFadeSaveLayer
    Box(Modifier.fillMaxSize().cinematicBackdropFade(fadeBottom, allowSaveLayer, background)) {
        CrossfadeBackdropImage(
            model = artworkUrl,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            focusCrop = true,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to background.copy(alpha = 0.90f),
                    0.38f to background.copy(alpha = 0.48f),
                    0.70f to Color.Transparent,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.42f to Color.Transparent,
                    0.78f to background.copy(alpha = 0.62f),
                    1f to background.copy(alpha = 0.94f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to accent.copy(alpha = 0.10f),
                    0.40f to Color.Transparent,
                ),
            ),
        )
    }
}

private val CinematicEdgeFadeHeight = 96.dp

/**
 * Fades the backdrop to transparent at its top edge, and optionally at its bottom edge, mirroring
 * the Home hero's fade. Recommendation sections stack several heroes, so they fade both ways.
 */
private fun Modifier.cinematicBackdropFade(fadeBottom: Boolean, allowSaveLayer: Boolean, background: Color): Modifier =
    if (!allowSaveLayer) cinematicBackdropVeil(fadeBottom, background)
    else graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val fadeHeight = CinematicEdgeFadeHeight.toPx().coerceAtMost(size.height / 2f)
            val topMask = Brush.verticalGradient(
                0f to Color.Transparent,
                0.22f to Color.Black.copy(alpha = 0.28f),
                0.57f to Color.Black.copy(alpha = 0.82f),
                1f to Color.Black,
                startY = 0f,
                endY = fadeHeight,
            )
            val bottomMask = Brush.verticalGradient(
                0f to Color.Black,
                0.43f to Color.Black.copy(alpha = 0.82f),
                0.78f to Color.Black.copy(alpha = 0.28f),
                1f to Color.Transparent,
                startY = size.height - fadeHeight,
                endY = size.height,
            )
            onDrawWithContent {
                drawContent()
                drawRect(brush = topMask, size = Size(size.width, fadeHeight), blendMode = BlendMode.DstIn)
                if (fadeBottom) {
                    drawRect(brush = bottomMask, topLeft = Offset(0f, size.height - fadeHeight), size = Size(size.width, fadeHeight), blendMode = BlendMode.DstIn)
                }
            }
        }

/**
 * Reduced effects: no full-screen offscreen layer. The edges are covered with the page background
 * instead of masked out, which looks the same on the plain background and costs one draw.
 */
private fun Modifier.cinematicBackdropVeil(fadeBottom: Boolean, background: Color): Modifier =
    drawWithCache {
        val fadeHeight = CinematicEdgeFadeHeight.toPx().coerceAtMost(size.height / 2f)
        val top = Brush.verticalGradient(
            0f to background,
            0.22f to background.copy(alpha = 0.72f),
            0.57f to background.copy(alpha = 0.18f),
            1f to Color.Transparent,
            startY = 0f,
            endY = fadeHeight,
        )
        val bottom = Brush.verticalGradient(
            0f to Color.Transparent,
            0.43f to background.copy(alpha = 0.18f),
            0.78f to background.copy(alpha = 0.72f),
            1f to background,
            startY = size.height - fadeHeight,
            endY = size.height,
        )
        onDrawWithContent {
            drawContent()
            drawRect(brush = top, size = Size(size.width, fadeHeight))
            if (fadeBottom) drawRect(brush = bottom, topLeft = Offset(0f, size.height - fadeHeight), size = Size(size.width, fadeHeight))
        }
    }

@Composable
private fun CinematicHeroMetaRow(item: MediaItem) {
    val metaBeforeRating = buildList { item.year?.let { add(it.toString()) } }.joinToString("  ·  ")
    val metaAfterRating = buildList { formatDuration(item.durationMs)?.let(::add) }.joinToString("  ·  ")
    val hasHeroScore = item.rating != null || item.audienceRating != null || item.criticRating != null
    if (metaBeforeRating.isBlank() && item.contentRating.isNullOrBlank() && !hasHeroScore && metaAfterRating.isBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        if (metaBeforeRating.isNotBlank()) {
            Text(metaBeforeRating, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        }
        item.contentRating?.takeIf { it.isNotBlank() }?.let { ContentRatingBadge(it) }
        if (hasHeroScore) PrimaryRatingScoreBadge(item)
        if (metaAfterRating.isNotBlank()) {
            Text(metaAfterRating, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun RecommendationCinematicSection(
    row: RecommendationRow,
    focusedKey: String?,
    onItemClick: (String) -> Unit,
    onItemFocused: (MediaItem) -> Unit,
    requestFallbackFocus: Boolean,
    restorePending: Boolean,
    poster: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (row.items.isEmpty()) return
    var hero by remember(row.id, row.items, focusedKey) { mutableStateOf(row.items.firstOrNull { it.ratingKey == focusedKey } ?: row.items.first()) }
    val heroArtwork = hero.landscapeBackdropUrl
    val sampledAccent = rememberArtworkAccent(heroArtwork)
    val heroAccent = if (LocalAccentModeKey.current == "artwork") sampledAccent else MaterialTheme.colorScheme.primary
    val handheld = LocalHandheldUi.current
    Column(modifier.fillMaxWidth()) {
        ContentSectionHeader(title = row.title)
        Box(Modifier.fillMaxWidth().then(if (handheld) Modifier.height(deviceHeroHeight(500.dp, 390.dp)) else Modifier.weight(1f))) {
            CinematicHeroBackdrop(artworkUrl = heroArtwork, title = hero.title, accent = heroAccent, fadeBottom = true)
            CinematicHeroContent(hero = hero, poster = poster, infoWidthFraction = 0.72f, summaryMaxLines = 3, showStreamProperties = false) {
                LazyRow(
                    contentPadding = cinematicTileRowPadding(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(row.items, key = { _, item -> item.ratingKey }) { itemIndex, item ->
                        CinematicTile(
                            item = item,
                            poster = poster,
                            onClick = { onItemClick(item.ratingKey) },
                            onFocused = { hero = item; onItemFocused(item) },
                            requestInitialFocus = restorePending && (item.ratingKey == focusedKey || (focusedKey == null && requestFallbackFocus && itemIndex == 0)),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shared hero layout for the cinematic modes. The logo slot scales with the available height and
 * the summary only takes what is left, so logo, text and tile row always fit with fixed gaps:
 * one between summary and tiles, one below the tiles.
 */
@Composable
private fun CinematicHeroContent(
    hero: MediaItem,
    poster: Boolean,
    infoWidthFraction: Float,
    summaryMaxLines: Int,
    showStreamProperties: Boolean,
    tiles: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val logoHeight = (maxHeight * if (poster) 0.13f else 0.15f).coerceIn(deviceDp(48.dp, 36.dp), deviceDp(92.dp, 64.dp))
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxWidth(infoWidthFraction).padding(start = screenGutter(), top = deviceDp(22.dp, 14.dp)),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TitleLogo(
                    title = hero.title,
                    logoUrl = hero.logoUrl,
                    textStyle = MaterialTheme.typography.headlineMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    maxLogoWidth = deviceDp(440.dp, 240.dp),
                    fixedSlotHeight = logoHeight,
                )
                CinematicHeroMetaRow(hero)
                // Poster tiles leave less height, so keep the stream badges on one line there.
                if (showStreamProperties) StreamPropertyAdaptiveRows(hero, maxItems = 5, itemsPerRow = if (poster) 5 else 3)
                hero.summary?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = summaryMaxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
            Spacer(Modifier.height(deviceDp(18.dp, 12.dp)))
            tiles()
        }
    }
}

/**
 * Poster tiles in the cinematic row are covers without a caption: the hero already shows the
 * title. On TV they match Plex's poster size; the summary gives up lines when height runs short.
 */
@Composable
private fun CinematicTile(
    item: MediaItem,
    poster: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    requestInitialFocus: Boolean,
) {
    if (poster) {
        WallTile(
            item = item,
            poster = true,
            onClick = onClick,
            onFocused = onFocused,
            requestInitialFocus = requestInitialFocus,
            modifier = Modifier.width(deviceDp(172.dp, 84.dp) * rememberUhdScale()),
        )
    } else {
        MediaTile(
            item = item,
            onClick = onClick,
            style = MediaTileStyle.LANDSCAPE,
            showDescription = false,
            onFocused = onFocused,
            requestInitialFocus = requestInitialFocus,
        )
    }
}

@Composable
private fun cinematicTileRowPadding() = PaddingValues(
    start = screenGutter(),
    end = screenGutter(),
    top = 6.dp,
    bottom = deviceDp(30.dp, 18.dp),
)

@Composable
private fun EmptyLibrary(filtered: Boolean, onReset: () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    if (filtered) {
        StatePanel(
            title = stringResource(R.string.library_no_filter_matches),
            actionLabel = stringResource(R.string.library_filter_reset),
            onAction = onReset,
        )
    } else {
        StatePanel(stringResource(R.string.library_empty_title), stringResource(R.string.library_empty_body))
    }
}

private val LocalAlphabetBarShown = compositionLocalOf { false }

@Composable
private fun LibraryGrid(
    state: SectionUiState.Loaded,
    onItemClick: (String) -> Unit,
    onItemFocused: (MediaItem) -> Unit,
    onVisibleLetterChanged: (Char?) -> Unit,
) {
    // Restore target is captured once per item list. Keying this on the live focusedRatingKey made
    // every D-pad move snap the focused row to the top via scrollToItem, bypassing the glide.
    val restoreKey = remember(state.items) { state.focusedRatingKey }
    var restorePending by remember(state.items) { mutableStateOf(true) }
    val restoreIndex = remember(state.items, restoreKey) { state.items.indexOfFirst { it.ratingKey == restoreKey }.coerceAtLeast(0) }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = restoreIndex)
    LaunchedEffect(restoreKey, state.items) {
        if (state.selectedLetter == null && restoreKey != null && restoreIndex in state.items.indices) {
            gridState.scrollToItem(restoreIndex)
        }
    }
    LaunchedEffect(state.selectedLetter, state.items) { state.selectedLetter?.let { letter -> state.items.indexOfFirst { libraryIndexLetter(it.title) == letter }.takeIf { it >= 0 }?.let { gridState.scrollToItem(it) } } }
    LaunchedEffect(gridState, state.items) {
        snapshotFlow { gridState.firstVisibleItemIndex }
            .collect { index ->
                onVisibleLetterChanged(state.items.getOrNull(index)?.let { libraryIndexLetter(it.title) })
            }
    }
    LazyVerticalGrid(
        state = gridState,
        modifier = Modifier.overlayLazyGridScrollbar(gridState, gutter = deviceDp(52.dp, 16.dp), enabled = !LocalAlphabetBarShown.current),
        columns = deviceDensityGridCells(state.gridColumns),
        contentPadding = PaddingValues(screenGutter(), 12.dp, deviceDp(52.dp, 16.dp), 36.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(state.items, key = { _, it -> it.ratingKey }) { index, item ->
            MediaTile(
                fillCell = true,
                item = item,
                onClick = { onItemClick(item.ratingKey) },
                style = MediaTileStyle.POSTER,
                onFocused = { restorePending = false; onItemFocused(item) },
                requestInitialFocus = restorePending && restoreKey != null && index == restoreIndex,
            )
        }
    }
}

@Composable
private fun LibraryList(
    state: SectionUiState.Loaded,
    onItemClick: (String) -> Unit,
    onItemFocused: (MediaItem) -> Unit,
    onVisibleLetterChanged: (Char?) -> Unit,
    poster: Boolean = false,
) {
    StylishMediaList(state.items, state.focusedRatingKey, onItemClick, onItemFocused, state.selectedLetter, onVisibleLetterChanged, visibleRows = state.listRows, poster = poster)
}

@Composable
private fun StylishMediaList(
    items: List<MediaItem>,
    focusedKey: String?,
    onItemClick: (String) -> Unit,
    onItemFocused: (MediaItem) -> Unit,
    selectedLetter: Char? = null,
    onVisibleLetterChanged: ((Char?) -> Unit)? = null,
    showDescription: Boolean = true,
    visibleRows: Int = 7,
    poster: Boolean = false,
) {
    val initialFocusedKey = remember(items) { focusedKey }
    val restoreIndex = remember(items, initialFocusedKey) { items.indexOfFirst { it.ratingKey == initialFocusedKey }.coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = restoreIndex)
    val restoreRequester = remember { FocusRequester() }
    LaunchedEffect(selectedLetter, items) { selectedLetter?.let { l -> items.indexOfFirst { libraryIndexLetter(it.title) == l }.takeIf { it >= 0 }?.let { listState.scrollToItem(it) } } }
    LaunchedEffect(listState, items, onVisibleLetterChanged) {
        if (onVisibleLetterChanged != null) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .collect { index ->
                    onVisibleLetterChanged(items.getOrNull(index)?.let { libraryIndexLetter(it.title) })
                }
        }
    }
    LaunchedEffect(Unit) {
        if (initialFocusedKey != null && restoreIndex in items.indices && items[restoreIndex].ratingKey == initialFocusedKey) {
            runCatching { restoreRequester.requestFocus() }
        }
    }
    val uhdScale = rememberUhdScale()
    val rowScale = (7f / visibleRows.coerceIn(4, 10)).coerceIn(0.72f, 1.75f) * uhdScale
    LazyColumn(allowUpExit = true, state = listState, modifier = Modifier.overlayLazyScrollbar(listState, gutter = screenGutter(), enabled = !LocalAlphabetBarShown.current), contentPadding = PaddingValues(screenGutter(), 12.dp, screenGutter(), 36.dp), verticalArrangement = Arrangement.spacedBy(8.dp * rowScale)) {
        itemsIndexed(items, key = { _, it -> it.ratingKey }) { _, item ->
            var isFocused by remember(item.ratingKey) { mutableStateOf(false) }
            val focusModifier = if (item.ratingKey == initialFocusedKey) Modifier.focusRequester(restoreRequester) else Modifier
            val rowShape = RoundedCornerShape(10.dp)
            Surface(onClick = { onItemClick(item.ratingKey) }, colors = transparentSurfaceColors(), scale = noSurfaceScale(), shape = ClickableSurfaceDefaults.shape(shape = rowShape), modifier = focusModifier.mediaFocusMotion(isFocused, focusedScale = 1.012f).activateOnTvKey { onItemClick(item.ratingKey) }.bringFocusedContextIntoView().onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onItemFocused(item)
            }) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.58f), rowShape)
                        .border(if (isFocused) 1.5.dp else 1.dp, if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.06f), rowShape)
                        .padding(10.dp * rowScale),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp * rowScale),
                ) {
                    MediaArtworkWithStreamIcons(
                        item = item,
                        model = if (poster) item.posterArtUrl else item.landscapeArtUrl,
                        modifier = (if (poster) Modifier.width(deviceDp(76.dp, 52.dp) * rowScale).aspectRatio(2f / 3f) else Modifier.width(deviceDp(166.dp, 96.dp) * rowScale).aspectRatio(16f / 9f))
                            .clip(RoundedCornerShape(if (poster) 7.dp else 10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        MarqueeTitle(item.title, focused = isFocused, style = MaterialTheme.typography.titleMedium)
                        val meta = buildList { item.year?.let { add(it.toString()) }; formatDuration(item.durationMs)?.let(::add) }.joinToString(" · ")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            item.contentRating?.takeIf { it.isNotBlank() }?.let { ContentRatingBadge(it) }
                            if (item.rating != null || item.audienceRating != null || item.criticRating != null) PrimaryRatingScoreBadge(item)
                        }
                        if (showDescription) {
                            item.summary?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun PosterGrid(
    items: List<MediaItem>,
    focusedKey: String?,
    columns: Int,
    onItemClick: (String) -> Unit,
    onItemFocused: (MediaItem) -> Unit,
) {
    val restoreKey = remember(items) { focusedKey }
    var restorePending by remember(items) { mutableStateOf(true) }
    val restoreIndex = remember(items, restoreKey) { items.indexOfFirst { it.ratingKey == restoreKey }.coerceAtLeast(0) }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = restoreIndex)
    LazyVerticalGrid(
        state = gridState,
        modifier = Modifier.overlayLazyGridScrollbar(gridState, gutter = screenGutter()),
        columns = deviceDensityGridCells(columns.coerceIn(4, 12)),
        contentPadding = PaddingValues(screenGutter(), 12.dp, screenGutter(), 36.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(items, key = { _, it -> it.ratingKey }) { index, item ->
            MediaTile(
                fillCell = true,
                item = item,
                onClick = { onItemClick(item.ratingKey) },
                style = MediaTileStyle.POSTER,
                onFocused = { restorePending = false; onItemFocused(item) },
                requestInitialFocus = restorePending && restoreKey != null && index == restoreIndex,
            )
        }
    }
}

@Composable
private fun LandscapeGrid(
    items: List<MediaItem>,
    focusedKey: String?,
    columns: Int,
    onItemClick: (String) -> Unit,
    onItemFocused: (MediaItem) -> Unit,
    selectedLetter: Char? = null,
    onVisibleLetterChanged: ((Char?) -> Unit)? = null,
) {
    val restoreKey = remember(items) { focusedKey }
    var restorePending by remember(items) { mutableStateOf(true) }
    val restoreIndex = remember(items, restoreKey) { items.indexOfFirst { it.ratingKey == restoreKey }.coerceAtLeast(0) }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = restoreIndex)
    LaunchedEffect(selectedLetter, items) {
        selectedLetter?.let { letter ->
            items.indexOfFirst { libraryIndexLetter(it.title) == letter }
                .takeIf { it >= 0 }?.let { gridState.scrollToItem(it) }
        }
    }
    LaunchedEffect(gridState, items, onVisibleLetterChanged) {
        if (onVisibleLetterChanged != null) {
            snapshotFlow { gridState.firstVisibleItemIndex }.collect { index ->
                onVisibleLetterChanged(items.getOrNull(index)?.let { libraryIndexLetter(it.title) })
            }
        }
    }
    LazyVerticalGrid(
        state = gridState,
        modifier = Modifier.overlayLazyGridScrollbar(gridState, gutter = deviceDp(52.dp, 16.dp), enabled = !LocalAlphabetBarShown.current),
        columns = deviceDensityGridCells((columns / 2).coerceIn(2, 6), 144.dp, 4, landscapeTileWidth = 260.dp),
        contentPadding = PaddingValues(screenGutter(), 12.dp, deviceDp(52.dp, 16.dp), 36.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(items, key = { _, it -> it.ratingKey }) { index, item ->
            MediaTile(
                fillCell = true,
                item = item,
                onClick = { onItemClick(item.ratingKey) },
                style = MediaTileStyle.LANDSCAPE,
                showDescription = false,
                onFocused = { restorePending = false; onItemFocused(item) },
                requestInitialFocus = restorePending && restoreKey != null && index == restoreIndex,
            )
        }
    }
}

@Composable
private fun InfoList(
    items: List<MediaItem>,
    focusedKey: String?,
    visibleRows: Int,
    onItemClick: (String) -> Unit,
    onItemFocused: (MediaItem) -> Unit,
    selectedLetter: Char? = null,
    poster: Boolean = false,
    onVisibleLetterChanged: ((Char?) -> Unit)? = null,
) {
    if (LocalHandheldUi.current) {
        StylishMediaList(items, focusedKey, onItemClick, onItemFocused, selectedLetter, onVisibleLetterChanged, showDescription = true, visibleRows = visibleRows, poster = poster)
        return
    }

    if (items.isEmpty()) return
    val initialFocusedKey = remember(items) { focusedKey ?: items.firstOrNull()?.ratingKey }
    val restoreIndex = remember(items, initialFocusedKey) { items.indexOfFirst { it.ratingKey == initialFocusedKey }.coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = restoreIndex)
    val restoreRequester = remember { FocusRequester() }
    var preview by remember(items) { mutableStateOf(items.firstOrNull { it.ratingKey == initialFocusedKey } ?: items.first()) }
    val uhdScale = rememberUhdScale()
    val rowScale = (7f / visibleRows.coerceIn(4, 10)).coerceIn(0.72f, 1.75f) * uhdScale

    LaunchedEffect(Unit) { if (initialFocusedKey != null) runCatching { restoreRequester.requestFocus() } }
    LaunchedEffect(selectedLetter, items) {
        selectedLetter?.let { letter ->
            items.indexOfFirst { libraryIndexLetter(it.title) == letter }
                .takeIf { it >= 0 }?.let { listState.scrollToItem(it) }
        }
    }
    LaunchedEffect(listState, items, onVisibleLetterChanged) {
        if (onVisibleLetterChanged != null) snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            onVisibleLetterChanged(items.getOrNull(index)?.let { libraryIndexLetter(it.title) })
        }
    }

    Row(Modifier.fillMaxSize().padding(horizontal = screenGutter(), vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        LazyColumn(
            allowUpExit = true,
            state = listState,
            modifier = Modifier.width(470.dp * uhdScale).fillMaxHeight().overlayLazyScrollbar(listState),
            verticalArrangement = Arrangement.spacedBy(5.dp * rowScale),
            contentPadding = PaddingValues(bottom = 30.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.ratingKey }) { _, item ->
                var focused by remember(item.ratingKey) { mutableStateOf(false) }
                val restoreMod = if (item.ratingKey == initialFocusedKey) Modifier.focusRequester(restoreRequester) else Modifier
                val rowShape = RoundedCornerShape(10.dp)
                Surface(
                    onClick = { onItemClick(item.ratingKey) },
                    colors = transparentSurfaceColors(),
                    scale = noSurfaceScale(),
                    shape = ClickableSurfaceDefaults.shape(shape = rowShape),
                    modifier = restoreMod.fillMaxWidth().mediaFocusMotion(focused, focusedScale = 1.012f).activateOnTvKey { onItemClick(item.ratingKey) }.bringFocusedContextIntoView().onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused) { preview = item; onItemFocused(item) }
                    },
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent, rowShape)
                            .border(if (focused) 1.5.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.04f), rowShape)
                            .padding(horizontal = 12.dp * rowScale, vertical = 9.dp * rowScale),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MediaArtworkWithStreamIcons(
                            item = item,
                            model = if (poster) item.posterArtUrl else item.landscapeArtUrl,
                            modifier = if (poster) {
                                Modifier.width(40.dp * rowScale).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp))
                            } else {
                                Modifier.width(96.dp * rowScale).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                            },
                            maxItems = 2,
                        )
                        Column(Modifier.weight(1f)) {
                            MarqueeTitle(item.title, focused = focused, style = MaterialTheme.typography.titleSmall)
                            val meta = buildList { item.year?.let { add(it.toString()) }; formatDuration(item.durationMs)?.let(::add) }.joinToString(" · ")
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                item.contentRating?.takeIf { it.isNotBlank() }?.let { ContentRatingBadge(it) }
                            }
                        }
                    }
                }
            }
        }
        if (poster) {
            // Poster beside the details: a 2:3 cover on top would push the text below the fold.
            Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                MediaArtworkWithStreamIcons(
                    item = preview,
                    model = preview.posterArtUrl,
                    modifier = Modifier.fillMaxHeight(0.92f).aspectRatio(2f / 3f, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(10.dp)),
                    maxItems = 4,
                )
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoListPreviewDetails(preview, summaryLines = 10)
                }
            }
        } else {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MediaArtworkWithStreamIcons(
                    item = preview,
                    model = preview.landscapeBackdropUrl,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)),
                    maxItems = 4,
                )
                InfoListPreviewDetails(preview, summaryLines = 6)
            }
        }
    }
}

@Composable
private fun ColumnScope.InfoListPreviewDetails(preview: MediaItem, summaryLines: Int) {
    StreamPropertyAdaptiveRows(preview, maxItems = 5, itemsPerRow = 3)
    val meta = buildList { preview.year?.let { add(it.toString()) }; formatDuration(preview.durationMs)?.let(::add) }.joinToString("  ·  ")
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        preview.contentRating?.takeIf { it.isNotBlank() }?.let { ContentRatingBadge(it) }
        if (preview.rating != null || preview.audienceRating != null || preview.criticRating != null) PrimaryRatingScoreBadge(preview)
    }
    preview.summary?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = summaryLines, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Spacer(Modifier.weight(1f))
}

/** Cover walls pack more 2:3 tiles per row than the 16:9 wall so each cover stays a similar size. */
internal fun coverWallColumns(gridColumns: Int) = (gridColumns + 4).coerceIn(8, 16)

@Composable
private fun WallGrid(
    items: List<MediaItem>,
    focusedKey: String?,
    columns: Int,
    onItemClick: (String) -> Unit,
    onItemFocused: (MediaItem) -> Unit,
    selectedLetter: Char? = null,
    poster: Boolean = false,
    onVisibleLetterChanged: ((Char?) -> Unit)? = null,
) {
    val wallColumns = if (poster) coverWallColumns(columns) else (columns + 2).coerceIn(6, 14)
    val restoreKey = remember(items) { focusedKey }
    val restoreIndex = remember(items, restoreKey) { items.indexOfFirst { it.ratingKey == restoreKey }.coerceAtLeast(0) }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = restoreIndex)
    val restoreRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (restoreKey != null) runCatching { restoreRequester.requestFocus() } }
    LaunchedEffect(selectedLetter, items) { selectedLetter?.let { l -> items.indexOfFirst { libraryIndexLetter(it.title) == l }.takeIf { it >= 0 }?.let { gridState.scrollToItem(it) } } }
    LaunchedEffect(gridState, items, onVisibleLetterChanged) {
        if (onVisibleLetterChanged != null) snapshotFlow { gridState.firstVisibleItemIndex }.collect { index ->
            onVisibleLetterChanged(items.getOrNull(index)?.let { libraryIndexLetter(it.title) })
        }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = deviceGridCells(wallColumns, if (poster) 96.dp else 144.dp),
        contentPadding = PaddingValues(screenGutter(), 10.dp, deviceDp(52.dp, 16.dp), 34.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(items, key = { _, it -> it.ratingKey }) { _, item ->
            var focused by remember(item.ratingKey) { mutableStateOf(false) }
            val fm = if (item.ratingKey == restoreKey) Modifier.focusRequester(restoreRequester) else Modifier
            val cardShape = RoundedCornerShape(10.dp)
            Surface(
                onClick = { onItemClick(item.ratingKey) }, colors = transparentSurfaceColors(), scale = noSurfaceScale(),
                shape = ClickableSurfaceDefaults.shape(shape = cardShape),
                modifier = fm.mediaFocusMotion(focused).activateOnTvKey { onItemClick(item.ratingKey) }.bringFocusedContextIntoView().onFocusChanged { focused = it.isFocused; if (it.isFocused) onItemFocused(item) },
            ) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(if (poster) 2f / 3f else 16f / 9f)
                        .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.06f), cardShape),
                ) {
                    MediaArtworkWithStreamIcons(
                        item = item,
                        model = if (poster) item.posterArtUrl else item.landscapeArtUrl,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                        maxItems = 3,
                    )
                    if (focused) {
                        Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.72f)).padding(7.dp)) {
                            CenteredMarqueeTitle(item.title, focused = focused, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CinematicView(
    items: List<MediaItem>,
    focusedKey: String?,
    onItemClick: (String) -> Unit,
    poster: Boolean = false,
    onItemFocused: (MediaItem) -> Unit,
) {
    if (items.isEmpty()) return
    val restoreKey = remember(items) { focusedKey }
    var restorePending by remember(items) { mutableStateOf(true) }
    var hero by remember(items) { mutableStateOf(items.firstOrNull { it.ratingKey == restoreKey } ?: items.first()) }
    val restoreIndex = remember(items, restoreKey) { items.indexOfFirst { it.ratingKey == restoreKey }.coerceAtLeast(0) }
    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = (restoreIndex - 1).coerceAtLeast(0))
    val heroArtwork = hero.landscapeBackdropUrl
    val sampledAccent = rememberArtworkAccent(heroArtwork)
    val heroAccent = if (LocalAccentModeKey.current == "artwork") sampledAccent else MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize()) {
        CinematicHeroBackdrop(artworkUrl = heroArtwork, title = hero.title, accent = heroAccent)
        CinematicHeroContent(hero = hero, poster = poster, infoWidthFraction = 0.58f, summaryMaxLines = 4, showStreamProperties = true) {
            LazyRow(
                state = rowState,
                contentPadding = cinematicTileRowPadding(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.ratingKey }) { item ->
                    CinematicTile(
                        item = item,
                        poster = poster,
                        onClick = { onItemClick(item.ratingKey) },
                        onFocused = { restorePending = false; hero = item; onItemFocused(item) },
                        requestInitialFocus = restorePending && item.ratingKey == restoreKey,
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionGrid(state: SectionUiState.Loaded, onClick: (String, String) -> Unit, onFocused: (String) -> Unit) {
    val uhdScale = rememberUhdScale()
    val cardWidth = 160.dp * uhdScale
    // Captured once per list, see LibraryGrid: a live key would snap-scroll on every focus move.
    val restoreId = remember(state.collections) { state.focusedCollectionId }
    val restoreIndex = remember(state.collections, restoreId) { state.collections.indexOfFirst { it.id == restoreId }.coerceAtLeast(0) }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = restoreIndex)
    val restoreRequester = remember { FocusRequester() }
    LaunchedEffect(restoreId, state.collections) {
        if (restoreId != null && restoreIndex in state.collections.indices && state.collections[restoreIndex].id == restoreId) {
            gridState.scrollToItem(restoreIndex)
            runCatching { restoreRequester.requestFocus() }
        }
    }
    LazyVerticalGrid(
        state = gridState,
        modifier = Modifier.overlayLazyGridScrollbar(gridState, gutter = screenGutter()),
        columns = deviceDensityGridCells(state.gridColumns),
        contentPadding = PaddingValues(screenGutter(), 12.dp, screenGutter(), 36.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(state.collections, key = { _, it -> it.id }) { _, collection ->
            val focusModifier = if (collection.id == restoreId) Modifier.focusRequester(restoreRequester) else Modifier
            var isFocused by remember(collection.id) { mutableStateOf(false) }
            val cardShape = RoundedCornerShape(10.dp)
            Surface(onClick = { onClick(collection.id, collection.title) }, colors = transparentSurfaceColors(), scale = noSurfaceScale(), shape = ClickableSurfaceDefaults.shape(shape = cardShape), modifier = focusModifier.mediaFocusMotion(isFocused).activateOnTvKey { onClick(collection.id, collection.title) }.onFocusChanged { isFocused = it.isFocused; if (it.isFocused) onFocused(collection.id) }) {
                Column(
                    Modifier.width(cardWidth)
                        .background(if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.58f), cardShape)
                        .border(if (isFocused) 1.5.dp else 1.dp, if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.06f), cardShape)
                ) {
                    ArtworkImage(
                        model = collection.representative.thumbUrl ?: collection.representative.artUrl,
                        title = collection.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                    )
                    Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CenteredMarqueeTitle(collection.title, focused = isFocused, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.collection_item_count, collection.itemCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionList(state: SectionUiState.Loaded, onClick: (String, String) -> Unit, onFocused: (String) -> Unit) {
    val initialFocusedCollectionId = remember(state.collections) { state.focusedCollectionId }
    val restoreIndex = remember(state.collections, initialFocusedCollectionId) { state.collections.indexOfFirst { it.id == initialFocusedCollectionId }.coerceAtLeast(0) }
    val restoreRequester = remember { FocusRequester() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = restoreIndex)
    LaunchedEffect(Unit) {
        if (initialFocusedCollectionId != null && restoreIndex in state.collections.indices && state.collections[restoreIndex].id == initialFocusedCollectionId) {
            runCatching { restoreRequester.requestFocus() }
        }
    }
    val uhdScale = rememberUhdScale()
    val rowScale = (7f / state.listRows.coerceIn(4, 10)).coerceIn(0.72f, 1.75f) * uhdScale
    LazyColumn(allowUpExit = true, state = listState, modifier = Modifier.overlayLazyScrollbar(listState, gutter = screenGutter()), contentPadding = PaddingValues(screenGutter(), 12.dp, screenGutter(), 36.dp), verticalArrangement = Arrangement.spacedBy(8.dp * rowScale)) {
        itemsIndexed(state.collections, key = { _, it -> it.id }) { _, c ->
            val focusModifier = if (c.id == initialFocusedCollectionId) Modifier.focusRequester(restoreRequester) else Modifier
            var isFocused by remember(c.id) { mutableStateOf(false) }
            val rowShape = RoundedCornerShape(10.dp)
            Surface(onClick = { onClick(c.id, c.title) }, colors = transparentSurfaceColors(), scale = noSurfaceScale(), shape = ClickableSurfaceDefaults.shape(shape = rowShape), modifier = focusModifier.mediaFocusMotion(isFocused, focusedScale = 1.012f).activateOnTvKey { onClick(c.id, c.title) }.onFocusChanged { isFocused = it.isFocused; if (it.isFocused) onFocused(c.id) }) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.58f), rowShape)
                        .border(if (isFocused) 1.5.dp else 1.dp, if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.06f), rowShape)
                        .padding(10.dp * rowScale),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp * rowScale),
                ) {
                    ArtworkImage(model = c.representative.landscapeArtUrl, title = c.title, contentScale = ContentScale.Crop, modifier = Modifier.width(deviceDp(178.dp, 96.dp) * rowScale).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.collection_item_count, c.itemCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
