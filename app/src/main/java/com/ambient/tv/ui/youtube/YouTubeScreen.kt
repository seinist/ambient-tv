@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.ambient.tv.ui.youtube

import com.ambient.tv.ui.theme.screenGutter
import com.ambient.tv.ui.mobile.DeviceActionRow

import com.ambient.tv.ui.mobile.deviceVideoGridCells
import com.ambient.tv.ui.mobile.deviceDensityGridCells

import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.ui.mobile.deviceHeroHeight

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import com.ambient.tv.ui.common.pageContentUp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import com.ambient.tv.ui.common.TvLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import com.ambient.tv.ui.common.TvLazyVerticalGrid as LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ambient.tv.ui.common.skeletonPulseAlpha
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.*
import com.ambient.tv.R
import com.ambient.tv.data.local.MediaSelectionPreferences
import com.ambient.tv.ui.common.*
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeSignInLink
import com.ambient.tv.youtube.YouTubeRow
import kotlinx.coroutines.delay

@Composable fun YouTubeScreen(
    onVideoClick:(String)->Unit,
    initialFeedKey:String?=null,
    /** Feed root: focus sidebar like Files instead of popping to Home. */
    onBack:(()->Unit)?=null,
    /** Chip selection updates the nav route so the sidebar stays in sync. */
    onSelectFeed:((YouTubeFeed)->Unit)?=null,
    viewModel:YouTubeViewModel=hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val visibleRows = remember(state.rows, state.sort, state.filter) {
        applyYouTubeViewOptions(state.rows, state.sort, state.filter)
    }
    val populatedRows = remember(visibleRows) { visibleRows.filter { it.items.isNotEmpty() } }
    // Error/unavailable states still expose stable header actions. Only the transient loading
    // tree must hold the sidebar handoff.
    ReportSidebarContentReady(!state.loading)
    var showViewOptions by remember { mutableStateOf(false) }
    var viewOptionsOpened by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchOpened by remember { mutableStateOf(false) }
    val viewOptionsFocusRequester=remember { FocusRequester() }
    val searchActionFocusRequester=remember { FocusRequester() }
    val searchFieldFocusRequester=remember { FocusRequester() }
    val searchCloseFocusRequester=remember { FocusRequester() }
    val contentItemFocusRequester=remember { FocusRequester() }
    val contentFocusTarget = remember(state.feed, populatedRows) {
        resolveContentFocusTarget(
            savedItemId = viewModel.focusedVideoId(state.feed),
            savedRowId = viewModel.focusedRowId(state.feed),
            rows = populatedRows.mapIndexed { index, row ->
                youtubeRowId(index, row) to row.items.map { it.videoId }
            },
        )
    }
    // The search row is also shown for a restored query; Back closes it before leaving the page.
    val searchVisible = showSearch || state.query.isNotBlank()
    BackHandler(enabled=showViewOptions) { showViewOptions=false }
    BackHandler(enabled=searchVisible&&!showViewOptions) {
        viewModel.onQueryChanged("")
        showSearch=false
    }
    if (onBack != null) {
        BackHandler(enabled = !showViewOptions && !searchVisible) { viewOptionsOpened = false; onBack() }
    }
    var initialContentFocusPending by remember(initialFeedKey) { mutableStateOf(true) }
    var handledSignInId by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(state.completedSignInId) {
        if (state.completedSignInId <= handledSignInId) return@LaunchedEffect
        handledSignInId = state.completedSignInId
        initialContentFocusPending = true
        if (initialFeedKey != null) {
            (onSelectFeed ?: viewModel::selectFeed)(YouTubeFeed.HOME)
        }
    }
    LaunchedEffect(state.loading, visibleRows, state.feed, initialFeedKey, initialContentFocusPending, contentFocusTarget) {
        val requestedFeedIsReady = initialFeedKey == null || state.feed.key == initialFeedKey
        if (!showViewOptions && initialContentFocusPending && requestedFeedIsReady && !state.loading && visibleRows.any { it.items.isNotEmpty() }) {
            delay(120)
            repeat(4) {
                if (runCatching { contentItemFocusRequester.requestFocus() }.getOrDefault(false)) {
                    initialContentFocusPending = false
                    return@LaunchedEffect
                }
                delay(32)
            }
        }
    }
    LaunchedEffect(showViewOptions,viewOptionsOpened) {
        if(!showViewOptions&&viewOptionsOpened) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { viewOptionsFocusRequester.requestFocus() }
        }
    }
    LaunchedEffect(showSearch) {
        if(showSearch) {
            delay(80)
            runCatching { searchFieldFocusRequester.requestFocus() }
        } else if(searchOpened) {
            delay(120)
            runCatching { searchActionFocusRequester.requestFocus() }
        }
    }
    LaunchedEffect(initialFeedKey) { initialFeedKey?.let(viewModel::selectFeedKey) }
    LaunchedEffect(state.rows) { ScreensaverArtworkRegistry.offerAll(state.rows.flatMap { it.items }.take(24).map { it.imageUrl }) }
    val focusChain = com.ambient.tv.ui.common.rememberPageFocusChain()
    Box(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().focusProperties { onEnter = { if (showViewOptions) cancelFocusChange() } }.focusGroup()) {
        Header(
            focusChain = focusChain,
            state,
            viewModel,
            onSelectFeed=onSelectFeed ?: viewModel::selectFeed,
            showSearch=searchVisible,
            viewOptionsModifier=Modifier.focusRequester(viewOptionsFocusRequester),
            searchActionModifier=Modifier.focusRequester(searchActionFocusRequester),
            searchFieldModifier=Modifier.focusRequester(searchFieldFocusRequester).onPreviewKeyEvent { event ->
                val target=when(event.key) {
                    Key.DirectionUp->focusChain.contentUp
                    Key.DirectionDown->contentItemFocusRequester
                    Key.DirectionRight->searchCloseFocusRequester
                    else->null
                }
                if(target==null) false else {
                    if(event.type==KeyEventType.KeyDown) runCatching { target.requestFocus() }
                    true
                }
            },
            searchCloseModifier=Modifier.focusRequester(searchCloseFocusRequester),
            onOpenSearch={
                searchOpened=true
                showSearch=true
            },
            onCloseSearch={
                viewModel.onQueryChanged("")
                showSearch=false
            },
            onOpenViewOptions={
                viewOptionsOpened=true
                showViewOptions=true
            },
        )
        // Up from the feed: search field when open, then the feed chips, then the header actions.
        Box(Modifier.fillMaxSize().pageContentUp(focusChain) { if (searchVisible) searchFieldFocusRequester else focusChain.contentUp }.focusGroup()) {
            when {
                !state.available -> Message(state.error ?: stringResource(R.string.youtube_engine_missing))
                state.loading -> Loading(state.gridColumns)
                state.error != null -> Message(state.error!!)
                visibleRows.none { it.items.isNotEmpty() } -> Message(com.ambient.tv.ui.common.systemString(com.ambient.tv.ui.common.MediaSystem.YOUTUBE, R.string.youtube_empty))
                else -> SoftViewChange(state.viewMode, Modifier.fillMaxSize()) {
                    Content(
                        rows = visibleRows,
                        mode = state.viewMode,
                        gridColumns = state.gridColumns,
                        listRows = state.listRows,
                        click = onVideoClick,
                        initialFocusRequester = contentItemFocusRequester,
                        focusTarget = contentFocusTarget,
                        onItemFocused = { rowId, videoId -> viewModel.onItemFocused(state.feed, rowId, videoId) },
                        loadingRows = state.loadingRows,
                    )
                }
            }
        }
    }
        if (showViewOptions) Box(Modifier.fillWindowHeight().fillMaxSize().background(Color.Black.copy(alpha=.38f)))
        if (showViewOptions) Box(Modifier.align(Alignment.TopEnd)) {
            YouTubeViewOptionsPanel(
                state = state,
                onSelectSort = viewModel::selectSort,
                onSelectFilter = viewModel::selectFilter,
                onSelectView = viewModel::selectViewMode,
                onSetGridColumns = viewModel::setGridColumns,
                onSetListRows = viewModel::setListRows,
                onSetMediaSelection = viewModel::setMediaSelectionBehavior,
                onClose = { showViewOptions=false },
            )
        }
    }
    val signInCode = state.signInCode
    var dismissedSignInQr by remember { mutableStateOf<String?>(null) }
    if (signInCode != null && dismissedSignInQr != signInCode && com.ambient.tv.ui.mobile.LocalHandheldUi.current) {
        YouTubeSignInDialog(signInCode, onDismiss = { dismissedSignInQr = signInCode })
    } else if (signInCode != null && dismissedSignInQr != signInCode) {
        QrCodeDialog(
            title = stringResource(R.string.action_sign_in),
            hint = stringResource(
                if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) R.string.youtube_activate_handheld
                else R.string.youtube_activate,
            ),
            payload = YouTubeSignInLink.qrPayload(),
            code = signInCode,
            onDismiss = { dismissedSignInQr = signInCode },
            footer = {
                TvActionButton(
                    text = stringResource(R.string.action_close),
                    onClick = { dismissedSignInQr = signInCode },
                )
            },
        )
    }
}

private fun youtubeRowId(index: Int, row: YouTubeRow): String = row.id

@Composable private fun Header(
    focusChain:com.ambient.tv.ui.common.PageFocusChain,
    state:YouTubeUiState,
    vm:YouTubeViewModel,
    onSelectFeed:(YouTubeFeed)->Unit,
    showSearch:Boolean,
    viewOptionsModifier:Modifier,
    searchActionModifier:Modifier,
    searchFieldModifier:Modifier,
    searchCloseModifier:Modifier,
    onOpenSearch:()->Unit,
    onCloseSearch:()->Unit,
    onOpenViewOptions:()->Unit,
) = Column(Modifier.fillMaxWidth().padding(screenGutter(), 24.dp, screenGutter(), 10.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
    val channel = state.channel
    PageHeader(focusChain=focusChain, actions={
        if (channel == null) YouTubeAccountButton(state, vm::requestSignIn, vm::signOut)
        else if (state.signedIn) channel.subscribed?.let { subscribed ->
            TvChip(stringResource(if (subscribed) R.string.youtube_subscribed else R.string.youtube_subscribe), subscribed, icon=Icons.Filled.Subscriptions, onClick=vm::toggleSubscription)
        }
        TvChip(stringResource(R.string.action_search),modifier=searchActionModifier,icon=Icons.Filled.Search,iconOnly=true,onClick=onOpenSearch)
        TvChip(stringResource(R.string.action_view),modifier=viewOptionsModifier,icon=Icons.Filled.Tune,iconOnly=true,onClick=onOpenViewOptions)
    }) {
        if (channel == null) com.ambient.tv.ui.common.SystemPageTitle(com.ambient.tv.ui.common.MediaSystem.YOUTUBE)
        else YouTubeChannelTitle(channel)
    }
    if (channel == null) com.ambient.tv.ui.common.PageChipRow(focusChain) { items(state.visibleFeeds, key={it.key}) { feed ->
        val selected = state.feed==feed && state.query.isBlank()
        TvChip(feedLabel(feed), selected, modifier=if (state.feed==feed) Modifier.focusRequester(focusChain.selectedChip) else Modifier, icon=feedIcon(feed)) { onSelectFeed(feed) }
    } }
    if (showSearch) {
        DeviceActionRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            TvTextField(state.query,vm::onQueryChanged,
                if (channel != null) stringResource(R.string.youtube_channel_search)
                else com.ambient.tv.ui.common.systemString(com.ambient.tv.ui.common.MediaSystem.YOUTUBE, R.string.youtube_search),
                searchFieldModifier.weight(1f))
            PlayerIconButton(Icons.Filled.Close,stringResource(R.string.action_close),onCloseSearch,modifier=searchCloseModifier)
        }
    }
    state.signInCode?.let { code -> DeviceActionRow(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.5f), RoundedCornerShape(10.dp)).padding(16.dp), horizontalArrangement=Arrangement.spacedBy(12.dp)) { Text(stringResource(R.string.youtube_activate)); Text(code, color=MaterialTheme.colorScheme.primary); TvActionButton(stringResource(R.string.youtube_refresh), onClick=vm::refresh, loading=state.loading); if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) YouTubeSignInBrowserActions(code) } }
}

/** Channel page title: round avatar and channel name in place of the system title. */
@Composable
private fun YouTubeChannelTitle(channel: com.ambient.tv.youtube.YouTubeChannelInfo) = Row(
    horizontalArrangement = Arrangement.spacedBy(14.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    channel.avatarUrl?.let { ArtworkImage(it, channel.title.orEmpty(), Modifier.size(deviceDp(48.dp, 36.dp)).clip(CircleShape), ContentScale.Crop) }
    Text(
        channel.title ?: stringResource(R.string.youtube_channel_open),
        style = MaterialTheme.typography.headlineMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 16:9 artwork of a tile. Channel avatars are square: they stay whole, round and centered on the
 * tile, with SmartTube's new-content dot for subscribed channels that uploaded since.
 */
@Composable
private fun YouTubeItemArtwork(item: YouTubeItem, modifier: Modifier) {
    if (!item.isChannel) {
        ArtworkImage(item.imageUrl, item.title, modifier, ContentScale.Crop)
        return
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f)), contentAlignment = Alignment.Center) {
        ArtworkImage(item.imageUrl, item.title, Modifier.fillMaxHeight(.82f).aspectRatio(1f).clip(CircleShape), ContentScale.Crop)
        if (item.hasNewContent) {
            val label = stringResource(R.string.youtube_channel_new_content)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(12.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .semantics { contentDescription = label },
            )
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable private fun YouTubeViewOptionsPanel(
    state: YouTubeUiState,
    onSelectSort: (YouTubeSort) -> Unit,
    onSelectFilter: (YouTubeFilter) -> Unit,
    onSelectView: (YouTubeViewMode) -> Unit,
    onSetGridColumns: (Int) -> Unit,
    onSetListRows: (Int) -> Unit,
    onSetMediaSelection: (String) -> Unit,
    onClose: () -> Unit,
) {
    com.ambient.tv.ui.common.ViewOptionsPanel(
        views = YouTubeViewMode.entries.map { com.ambient.tv.ui.common.ViewOption(it.name, modeLabel(it), com.ambient.tv.ui.common.ViewPreview.valueOf(it.name)) },
        selectedView = state.viewMode.name, onView = { onSelectView(YouTubeViewMode.valueOf(it)) },
        gridColumns = state.gridColumns, listRows = state.listRows,
        onGridColumns = onSetGridColumns, onListRows = onSetListRows, onClose = onClose,
        showDensity = state.viewMode != YouTubeViewMode.CINEMATIC,
        sorts = YouTubeSort.entries.map { com.ambient.tv.ui.common.PanelOption(it.name, sortLabel(it)) },
        selectedSort = state.sort.name, onSort = { onSelectSort(YouTubeSort.valueOf(it)) },
        filters = YouTubeFilter.entries.map { com.ambient.tv.ui.common.PanelOption(it.name, filterLabel(it)) },
        selectedFilter = state.filter.name, onFilter = { onSelectFilter(YouTubeFilter.valueOf(it)) },
        extraContent = {
            RightPanelDivider()
            RightPanelSectionTitle(
                stringResource(R.string.youtube_media_selection_title),
                stringResource(R.string.youtube_media_selection_hint),
            )
            listOf(
                MediaSelectionPreferences.PLAY,
                MediaSelectionPreferences.DETAILS,
                MediaSelectionPreferences.ASK,
            ).forEach { behavior ->
                RightPanelChoice(
                    text = stringResource(when (behavior) {
                        MediaSelectionPreferences.DETAILS -> R.string.settings_media_selection_details
                        MediaSelectionPreferences.ASK -> R.string.settings_media_selection_ask
                        else -> R.string.settings_media_selection_play
                    }),
                    selected = state.mediaSelectionBehaviorKey == behavior,
                    onClick = { onSetMediaSelection(behavior) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

@Composable
private fun Content(
    rows: List<YouTubeRow>,
    mode: YouTubeViewMode,
    gridColumns: Int,
    listRows: Int,
    click: (String) -> Unit,
    initialFocusRequester: FocusRequester,
    focusTarget: ContentFocusTarget,
    onItemFocused: (String?, String) -> Unit,
    loadingRows: Set<String> = emptySet(),
) {
    val populatedRows = rows.filter { it.items.isNotEmpty() }
    if (populatedRows.hasSubsections()) {
        SectionedYouTubeContent(populatedRows, mode, gridColumns, listRows, click, initialFocusRequester, focusTarget, onItemFocused, loadingRows)
        return
    }

    val videos = populatedRows.flatMap { it.items }.distinctBy { it.videoId }
    when (mode) {
        YouTubeViewMode.LANDSCAPE -> LandscapeGrid(videos, gridColumns, click, initialFocusRequester, focusTarget, onItemFocused)
        YouTubeViewMode.LIST -> ListMode(videos, listRows, click, false, initialFocusRequester, focusTarget, onItemFocused)
        YouTubeViewMode.INFO_LIST -> ListMode(videos, listRows, click, true, initialFocusRequester, focusTarget, onItemFocused)
        YouTubeViewMode.WALL -> {
            val targetIndex = videos.indexOfFirst { it.videoId == focusTarget.itemId }.coerceAtLeast(0)
            val gridState = com.ambient.tv.ui.common.rememberBrowseGridState(initialFirstVisibleItemIndex = targetIndex)
            LazyVerticalGrid(
                deviceVideoGridCells((gridColumns + 2).coerceIn(6, 14)),
                Modifier.fillMaxSize(),
                state = gridState,
                contentPadding = PaddingValues(screenGutter(), 10.dp, screenGutter(), 36.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                gridItemsIndexed(videos, key = { _, it -> it.videoId }) { _, item ->
                    Card(
                        item, click, Modifier.fillMaxWidth(), false,
                    focusRequester = if (item.videoId == focusTarget.itemId) initialFocusRequester else null,
                        onFocused = { onItemFocused(null, item.videoId) },
                    )
                }
            }
        }
        YouTubeViewMode.CINEMATIC -> Cinematic(videos, click, initialFocusRequester, focusTarget, onItemFocused)
    }
}

/** The feed's section structure is independent of the chosen item presentation. */
@Composable
private fun SectionedYouTubeContent(
    rows: List<YouTubeRow>,
    mode: YouTubeViewMode,
    gridColumns: Int,
    listRows: Int,
    click: (String) -> Unit,
    initialFocusRequester: FocusRequester,
    focusTarget: ContentFocusTarget,
    onItemFocused: (String?, String) -> Unit,
    loadingRows: Set<String>,
) {
    when (mode) {
        YouTubeViewMode.LANDSCAPE -> LazyColumn(
            allowUpExit = true,
            modifier = Modifier.fillMaxSize(),
            state = com.ambient.tv.ui.common.rememberBrowseListState(
                initialFirstVisibleItemIndex = rows.withIndex().firstOrNull { (index, row) ->
                    youtubeRowId(index, row) == focusTarget.rowId
                }?.index ?: 0,
            ),
            contentPadding = PaddingValues(bottom = 36.dp),
        ) {
            itemsIndexed(rows, key = { index, row -> "youtube-section:${row.id}" }) { index, row ->
                val rowId = youtubeRowId(index, row)
                RowMode(row, rowId, gridColumns, click, initialFocusRequester, focusTarget, onItemFocused, rowId in loadingRows)
            }
        }
        YouTubeViewMode.LIST -> SectionedYouTubeList(rows, listRows, click, detailed = false, initialFocusRequester, focusTarget, onItemFocused)
        YouTubeViewMode.INFO_LIST -> SectionedYouTubeList(rows, listRows, click, detailed = true, initialFocusRequester, focusTarget, onItemFocused)
        YouTubeViewMode.WALL -> SectionedYouTubeWall(rows, gridColumns, click, initialFocusRequester, focusTarget, onItemFocused)
        YouTubeViewMode.CINEMATIC -> SectionedYouTubeCinematic(rows, click, initialFocusRequester, focusTarget, onItemFocused, loadingRows)
    }
}

private fun List<YouTubeRow>.hasSubsections(): Boolean =
    hasMultipleNamedContentSections(map { it.title to it.items.size })

@Composable
private fun RowMode(
    row: YouTubeRow,
    rowId: String,
    gridColumns: Int,
    click: (String) -> Unit,
    initialFocusRequester: FocusRequester,
    focusTarget: ContentFocusTarget,
    onItemFocused: (String?, String) -> Unit,
    loadingMore: Boolean,
) = Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
    ContentSectionHeader(title = row.title)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val horizontalPadding = screenGutter()
        val spacing = 16.dp
        val visibleColumns = landscapeGridColumns(gridColumns)
        val cardWidth = if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) landscapeCardWidth() else
            ((maxWidth - horizontalPadding * 2 - spacing * (visibleColumns - 1)) / visibleColumns).coerceAtLeast(160.dp)
        LazyRow(
            state = com.ambient.tv.ui.common.rememberBrowseListState(
                initialFirstVisibleItemIndex = row.items.indexOfFirst { it.videoId == focusTarget.itemId }.coerceAtLeast(0),
            ),
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            itemsIndexed(row.items, key = { _, it -> it.videoId }) { _, item ->
                Card(
                    item, click, Modifier.width(cardWidth), true,
                    focusRequester = if (rowId == focusTarget.rowId && item.videoId == focusTarget.itemId) initialFocusRequester else null,
                    onFocused = { onItemFocused(rowId, item.videoId) },
                )
            }
            if (loadingMore) rowEndPlaceholders(rowId, Modifier.width(cardWidth), showText = true)
        }
    }
}

@Composable
private fun LandscapeGrid(
    items: List<YouTubeItem>,
    gridColumns: Int,
    click: (String) -> Unit,
    initialFocusRequester: FocusRequester,
    focusTarget: ContentFocusTarget,
    onItemFocused: (String?, String) -> Unit,
) = LazyVerticalGrid(
    columns = deviceDensityGridCells(landscapeGridColumns(gridColumns), 260.dp, 4),
    modifier = Modifier.fillMaxSize(),
    state = com.ambient.tv.ui.common.rememberBrowseGridState(
        initialFirstVisibleItemIndex = items.indexOfFirst { it.videoId == focusTarget.itemId }.coerceAtLeast(0),
    ),
    contentPadding = PaddingValues(screenGutter(), 10.dp, screenGutter(), 36.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
) {
    gridItemsIndexed(items, key = { _, it -> it.videoId }) { _, item ->
        Card(
            item, click, Modifier.fillMaxWidth(), true,
            focusRequester = if (item.videoId == focusTarget.itemId) initialFocusRequester else null,
            onFocused = { onItemFocused(null, item.videoId) },
        )
    }
}

@Composable
private fun ListMode(
    items: List<YouTubeItem>,
    listRows: Int,
    click: (String) -> Unit,
    detailed: Boolean,
    initialFocusRequester: FocusRequester,
    focusTarget: ContentFocusTarget,
    onItemFocused: (String?, String) -> Unit,
) {
    val rowScale = (7f / listRows.coerceIn(4, 10)).coerceIn(.72f, 1.35f)
    LazyColumn(
        allowUpExit = true,
        modifier = Modifier.fillMaxSize(),
        state = com.ambient.tv.ui.common.rememberBrowseListState(
            initialFirstVisibleItemIndex = items.indexOfFirst { it.videoId == focusTarget.itemId }.coerceAtLeast(0),
        ),
        contentPadding = PaddingValues(screenGutter(), 10.dp, screenGutter(), 36.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp * rowScale),
    ) {
        itemsIndexed(items, key = { _, it -> it.videoId }) { _, item ->
            YouTubeListItem(
                item, rowScale, click, detailed,
                if (item.videoId == focusTarget.itemId) initialFocusRequester else null,
                onFocused = { onItemFocused(null, item.videoId) },
            )
        }
    }
}

@Composable
private fun SectionedYouTubeList(
    rows: List<YouTubeRow>,
    listRows: Int,
    click: (String) -> Unit,
    detailed: Boolean,
    initialFocusRequester: FocusRequester,
    focusTarget: ContentFocusTarget,
    onItemFocused: (String?, String) -> Unit,
) {
    val rowScale = (7f / listRows.coerceIn(4, 10)).coerceIn(.72f, 1.35f)
    val targetRowIndex = rows.withIndex().firstOrNull { (index, row) ->
        youtubeRowId(index, row) == focusTarget.rowId
    }?.index ?: 0
    val targetItemIndex = rows.getOrNull(targetRowIndex)?.items
        ?.indexOfFirst { it.videoId == focusTarget.itemId }?.coerceAtLeast(0) ?: 0
    val targetListIndex = rows.take(targetRowIndex).sumOf { it.items.size + 1 } + 1 + targetItemIndex
    LazyColumn(
        allowUpExit = true,
        modifier = Modifier.fillMaxSize(),
        state = com.ambient.tv.ui.common.rememberBrowseListState(initialFirstVisibleItemIndex = targetListIndex),
        contentPadding = PaddingValues(screenGutter(), 10.dp, screenGutter(), 36.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp * rowScale),
    ) {
        rows.forEachIndexed { sectionIndex, row ->
            item(key = "youtube-list-header:$sectionIndex:${row.title}") {
                ContentSectionHeader(
                    title = row.title,
                    modifier = Modifier.padding(top = if (sectionIndex == 0) 0.dp else 12.dp),
                    startPadding = 0.dp,
                    bottomPadding = 2.dp,
                )
            }
            itemsIndexed(row.items, key = { itemIndex, item -> "youtube-list:$sectionIndex:$itemIndex:${item.videoId}" }) { itemIndex, item ->
                val rowId = youtubeRowId(sectionIndex, row)
                YouTubeListItem(
                    item = item,
                    rowScale = rowScale,
                    click = click,
                    detailed = detailed,
                    focusRequester = if (rowId == focusTarget.rowId && item.videoId == focusTarget.itemId) initialFocusRequester else null,
                    onFocused = { onItemFocused(rowId, item.videoId) },
                )
            }
        }
    }
}

@Composable
private fun YouTubeListItem(
    item: YouTubeItem,
    rowScale: Float,
    click: (String) -> Unit,
    detailed: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
) {
    var focused by remember(item.videoId) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = { click(item.videoId) },
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .mediaFocusMotion(focused, focusedScale = 1.012f)
            .activateOnTvKey { click(item.videoId) }
            .bringFocusedContextIntoView()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            },
    ) {
        Row(
            Modifier
                .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent, shape)
                .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .06f), shape)
                .padding(10.dp * rowScale),
            horizontalArrangement = Arrangement.spacedBy(14.dp * rowScale),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            YouTubeItemArtwork(
                item,
                Modifier.width(deviceDp(if (detailed) 190.dp else 128.dp, 104.dp) * rowScale).aspectRatio(16f / 9f).clip(RoundedCornerShape(7.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                com.ambient.tv.companion.PlaybackBadge(com.ambient.tv.companion.playbackIndicator(com.ambient.tv.companion.CompanionMedia("youtube", item.videoId)))
                MarqueeTitle(item.title, focused = focused, style = MaterialTheme.typography.titleMedium)
                MarqueeTitle(
                    meta(item),
                    focused = focused,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (detailed) youtubeInfoSubtitle(item)?.let {
                    MarqueeTitle(
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

@Composable
private fun SectionedYouTubeWall(
    rows: List<YouTubeRow>,
    gridColumns: Int,
    click: (String) -> Unit,
    initialFocusRequester: FocusRequester,
    focusTarget: ContentFocusTarget,
    onItemFocused: (String?, String) -> Unit,
) {
    val targetRowIndex = rows.withIndex().firstOrNull { (index, row) ->
        youtubeRowId(index, row) == focusTarget.rowId
    }?.index ?: 0
    val targetItemIndex = rows.getOrNull(targetRowIndex)?.items
        ?.indexOfFirst { it.videoId == focusTarget.itemId }?.coerceAtLeast(0) ?: 0
    val targetGridIndex = rows.take(targetRowIndex).sumOf { it.items.size + 1 } + 1 + targetItemIndex
    LazyVerticalGrid(
        columns = deviceVideoGridCells((gridColumns + 2).coerceIn(6, 14)),
        modifier = Modifier.fillMaxSize(),
        state = com.ambient.tv.ui.common.rememberBrowseGridState(initialFirstVisibleItemIndex = targetGridIndex),
        contentPadding = PaddingValues(screenGutter(), 10.dp, screenGutter(), 36.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEachIndexed { sectionIndex, row ->
            item(
                key = "youtube-wall-header:$sectionIndex:${row.title}",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                ContentSectionHeader(
                    title = row.title,
                    modifier = Modifier.padding(top = if (sectionIndex == 0) 0.dp else 14.dp),
                    startPadding = 0.dp,
                    bottomPadding = 4.dp,
                )
            }
            gridItemsIndexed(row.items, key = { itemIndex, item -> "youtube-wall:$sectionIndex:$itemIndex:${item.videoId}" }) { itemIndex, item ->
                val rowId = youtubeRowId(sectionIndex, row)
                Card(
                    item,
                    click,
                    Modifier.fillMaxWidth(),
                    false,
                    focusRequester = if (rowId == focusTarget.rowId && item.videoId == focusTarget.itemId) initialFocusRequester else null,
                    onFocused = { onItemFocused(rowId, item.videoId) },
                )
            }
        }
    }
}

@Composable
private fun Cinematic(
    items: List<YouTubeItem>,
    click: (String) -> Unit,
    initialFocusRequester: FocusRequester,
    focusTarget: ContentFocusTarget,
    onItemFocused: (String?, String) -> Unit,
) {
    if (items.isEmpty()) return
    var hero by remember(items) { mutableStateOf(items.first()) }
    Box(Modifier.fillMaxSize()) {
        if (!com.ambient.tv.ui.common.LocalHomeBackgroundVisible.current && com.ambient.tv.ui.common.LocalFocusArtworkBackdrop.current) {
            CrossfadeBackdropImage(hero.backgroundUrl ?: hero.imageUrl, hero.title, Modifier.fillMaxSize().alpha(.45f), ContentScale.Crop, focusCrop = true)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = .96f)))))
        }
        Column(Modifier.fillMaxSize().padding(top = 30.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.padding(horizontal = screenGutter()).fillMaxWidth(.62f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hero.title, style = MaterialTheme.typography.displaySmall, maxLines = 2)
                Text(meta(hero), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                youtubeInfoSubtitle(hero)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            }
            LazyRow(
                state = com.ambient.tv.ui.common.rememberBrowseListState(
                    initialFirstVisibleItemIndex = items.indexOfFirst { it.videoId == focusTarget.itemId }.coerceAtLeast(0),
                ),
                contentPadding = PaddingValues(horizontal = screenGutter(), vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(items, key = { _, it -> it.videoId }) { _, item ->
                    Card(
                        item, click, Modifier.width(landscapeCardWidth()), false,
                        focusRequester = if (item.videoId == focusTarget.itemId) initialFocusRequester else null,
                        onFocused = {
                            hero = item
                            onItemFocused(null, item.videoId)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionedYouTubeCinematic(
    rows: List<YouTubeRow>,
    click: (String) -> Unit,
    initialFocusRequester: FocusRequester,
    focusTarget: ContentFocusTarget,
    onItemFocused: (String?, String) -> Unit,
    loadingRows: Set<String>,
) {
    LazyColumn(
        allowUpExit = true,
        modifier = Modifier.fillMaxSize(),
        state = com.ambient.tv.ui.common.rememberBrowseListState(
            initialFirstVisibleItemIndex = rows.withIndex().firstOrNull { (index, row) ->
                youtubeRowId(index, row) == focusTarget.rowId
            }?.index ?: 0,
        ),
        contentPadding = PaddingValues(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(rows, key = { index, row -> "youtube-cinematic:$index:${row.title}" }) { sectionIndex, row ->
            YouTubeCinematicSection(
                row = row,
                click = click,
                rowId = youtubeRowId(sectionIndex, row),
                initialFocusRequester = initialFocusRequester,
                focusTarget = focusTarget,
                onItemFocused = onItemFocused,
                loadingMore = youtubeRowId(sectionIndex, row) in loadingRows,
            )
        }
    }
}

@Composable
private fun YouTubeCinematicSection(
    row: YouTubeRow,
    click: (String) -> Unit,
    rowId: String,
    initialFocusRequester: FocusRequester,
    focusTarget: ContentFocusTarget,
    onItemFocused: (String?, String) -> Unit,
    loadingMore: Boolean,
) {
    if (row.items.isEmpty()) return
    var hero by remember(row.title, row.items) { mutableStateOf(row.items.first()) }
    val placeholderModifier = Modifier.width(landscapeCardWidth())
    Column(Modifier.fillMaxWidth()) {
        ContentSectionHeader(title = row.title)
        Box(Modifier.fillMaxWidth().height(deviceHeroHeight(500.dp, 390.dp))) {
            if (com.ambient.tv.ui.common.LocalFocusArtworkBackdrop.current) CrossfadeBackdropImage(hero.backgroundUrl ?: hero.imageUrl, hero.title, Modifier.fillMaxSize().alpha(.45f), ContentScale.Crop, focusCrop = true)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = .96f)))))
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.padding(horizontal = screenGutter(), vertical = 18.dp).fillMaxWidth(.72f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(hero.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2)
                    Text(meta(hero), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    youtubeInfoSubtitle(hero)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
                LazyRow(
                    state = com.ambient.tv.ui.common.rememberBrowseListState(
                        initialFirstVisibleItemIndex = row.items.indexOfFirst { it.videoId == focusTarget.itemId }.coerceAtLeast(0),
                    ),
                    contentPadding = PaddingValues(horizontal = screenGutter(), vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(row.items, key = { _, it -> it.videoId }) { _, item ->
                        Card(
                            item,
                            click,
                            Modifier.width(landscapeCardWidth()),
                            false,
                            focusRequester = if (rowId == focusTarget.rowId && item.videoId == focusTarget.itemId) initialFocusRequester else null,
                            onFocused = {
                                hero = item
                                onItemFocused(rowId, item.videoId)
                            },
                        )
                    }
                    if (loadingMore) rowEndPlaceholders(rowId, placeholderModifier, showText = false)
                }
            }
        }
    }
}

/** Number of static-sized, unfocusable cards shown after a row while its next page loads. */
internal const val YOUTUBE_ROW_END_PLACEHOLDERS = 2

/** Stable keys that never collide with videoId keys, so appending replaces them in one step. */
internal fun youtubeRowEndPlaceholderKey(rowId: String, index: Int) = "youtube-row-more:$rowId:$index"

private fun androidx.compose.foundation.lazy.LazyListScope.rowEndPlaceholders(rowId: String, modifier: Modifier, showText: Boolean) {
    items(YOUTUBE_ROW_END_PLACEHOLDERS, key = { youtubeRowEndPlaceholderKey(rowId, it) }) {
        RowEndPlaceholder(modifier, showText)
    }
}

/** Card-shaped placeholder on the shared damped pulse (read at draw time); Sparsam stays static. */
@Composable
private fun RowEndPlaceholder(modifier: Modifier, showText: Boolean) {
    val pulse = if (com.ambient.tv.ui.performance.LocalPerformanceProfile.current.reducedEffects) null
        else com.ambient.tv.ui.common.rememberSharedPulse(com.ambient.tv.ui.common.SharedArtworkPulse)
    val fill = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .58f), RoundedCornerShape(10.dp))
            .padding(if (showText) 8.dp else 3.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).skeletonPulseAlpha(pulse).background(fill, RoundedCornerShape(7.dp)))
        if (showText) {
            Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth(.8f).height(13.dp).skeletonPulseAlpha(pulse).background(fill, RoundedCornerShape(3.dp)))
            }
            Box(Modifier.fillMaxWidth(.5f).height(10.dp).align(Alignment.CenterHorizontally).skeletonPulseAlpha(pulse).background(fill, RoundedCornerShape(3.dp)))
        }
    }
}

/** Browse tile for other YouTube pages (the detail page's related videos). */
@Composable
internal fun YouTubeItemCard(item: YouTubeItem, onClick: (String) -> Unit, modifier: Modifier) = Card(item, onClick, modifier, true)

@Composable
private fun Card(
    item: YouTubeItem,
    click: (String) -> Unit,
    modifier: Modifier,
    showText: Boolean,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
) {
    var focused by remember(item.videoId) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = { click(item.videoId) },
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape),
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .mediaFocusMotion(focused)
            .activateOnTvKey { click(item.videoId) }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            },
    ) {
        Column(
            Modifier
                .background(
                    if (focused) MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                    else MaterialTheme.colorScheme.surface.copy(alpha = .58f),
                    shape,
                )
                .border(
                    if (focused) 2.dp else 1.dp,
                    if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .06f),
                    shape,
                )
                .padding(if (showText) 8.dp else 3.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(7.dp))) {
                YouTubeItemArtwork(item, Modifier.fillMaxSize())
                com.ambient.tv.companion.PlaybackBadge(com.ambient.tv.companion.playbackIndicator(com.ambient.tv.companion.CompanionMedia("youtube", item.videoId)),
                    Modifier.align(Alignment.TopStart).padding(5.dp))
                if (item.percentWatched > 0) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(item.percentWatched.coerceIn(0, 100) / 100f)
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            if (showText) {
                Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                    CenteredMarqueeTitle(
                        text = item.title,
                        focused = focused,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                CenteredMarqueeTitle(
                    text = meta(item),
                    focused = focused,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
@Composable
private fun Loading(gridColumns: Int) {
    // Shared skeleton clock (RESUMED only, read at draw time); Sparsam stays static.
    YouTubeLoadingRows(gridColumns, com.ambient.tv.ui.common.rememberSkeletonPulseOrNull())
}

@Composable
private fun YouTubeLoadingRows(gridColumns: Int, pulse: androidx.compose.runtime.State<Float>?) {
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    fun Modifier.placeholder(radius: androidx.compose.ui.unit.Dp) =
        skeletonPulseAlpha(pulse).background(placeholderColor, RoundedCornerShape(radius))
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontalPadding = screenGutter()
        val spacing = 16.dp
        val visibleColumns = landscapeGridColumns(gridColumns)
        val cardWidth = if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) landscapeCardWidth() else
            ((maxWidth - horizontalPadding * 2 - spacing * (visibleColumns - 1)) / visibleColumns).coerceAtLeast(160.dp)
        LazyColumn(
            allowUpExit = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            userScrollEnabled = false,
        ) {
            items(3) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.padding(start = screenGutter()).width(168.dp).height(18.dp).placeholder(9.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = horizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(spacing),
                        userScrollEnabled = false,
                    ) {
                        items(visibleColumns) {
                            Column(
                                modifier = Modifier
                                    .width(cardWidth)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.58f), RoundedCornerShape(10.dp))
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).placeholder(7.dp))
                                Column(Modifier.height(44.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(Modifier.fillMaxWidth(0.9f).height(13.dp).placeholder(3.dp))
                                    Box(Modifier.fillMaxWidth(0.65f).height(13.dp).placeholder(3.dp))
                                }
                                Box(Modifier.fillMaxWidth(0.5f).height(10.dp).placeholder(3.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
/** Cinematic strips keep a stable card width; regular horizontal rows use the density setting. */
@Composable private fun landscapeCardWidth()=deviceDp(300.dp, 260.dp)
@Composable private fun Message(text:String)=Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(text,color=MaterialTheme.colorScheme.onSurfaceVariant)}
@Composable
private fun meta(i: YouTubeItem) = listOfNotNull(
    i.author ?: i.subtitle,
    when {
        i.isUpcoming -> i.scheduledStartMs?.let { start ->
            stringResource(R.string.youtube_live_start_at,
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(start)))
        } ?: stringResource(R.string.youtube_live_planned)
        i.isLive -> stringResource(R.string.youtube_live_badge)
        else -> i.durationMs.takeIf { it > 0 }?.let(::duration)
    },
    i.subtitle?.takeIf { i.isUpcoming && i.scheduledStartMs == null && i.author != null && it != i.author },
).joinToString("  ·  ")
/** Second gray line for info list / cinematic. Skips channel text already shown in [meta]. */
internal fun youtubeInfoSubtitle(item: YouTubeItem): String? {
    val extra = item.subtitle?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val primary = item.author?.trim()?.takeIf { it.isNotEmpty() } ?: extra
    return extra.takeUnless { it.equals(primary, ignoreCase = true) }
}
private fun duration(ms: Long): String = com.ambient.tv.ui.common.formatPlaybackClock(ms)
@Composable private fun modeLabel(m:YouTubeViewMode)=stringResource(when(m){YouTubeViewMode.LANDSCAPE->R.string.library_view_landscape;YouTubeViewMode.LIST->R.string.library_view_list;YouTubeViewMode.INFO_LIST->R.string.library_view_info_list;YouTubeViewMode.WALL->R.string.library_view_wall;YouTubeViewMode.CINEMATIC->R.string.library_view_cinematic})
@Composable private fun sortLabel(sort:YouTubeSort)=com.ambient.tv.ui.common.systemString(com.ambient.tv.ui.common.MediaSystem.YOUTUBE,when(sort){YouTubeSort.DEFAULT->R.string.youtube_sort_default;YouTubeSort.TITLE->R.string.library_sort_title;YouTubeSort.DURATION->R.string.youtube_sort_duration})
@Composable private fun filterLabel(filter:YouTubeFilter)=stringResource(when(filter){YouTubeFilter.ALL->R.string.library_filter_all;YouTubeFilter.UNWATCHED->R.string.library_filter_unwatched;YouTubeFilter.IN_PROGRESS->R.string.library_filter_in_progress;YouTubeFilter.LIVE->R.string.youtube_filter_live;YouTubeFilter.SHORTS->R.string.youtube_filter_shorts})
@Composable private fun feedLabel(f:YouTubeFeed)=stringResource(when(f){YouTubeFeed.HOME->R.string.nav_youtube_home;YouTubeFeed.SUBSCRIPTIONS->R.string.nav_youtube_subscriptions;YouTubeFeed.CHANNELS->R.string.nav_youtube_channels;YouTubeFeed.HISTORY->R.string.nav_youtube_history;YouTubeFeed.PLAYLISTS->R.string.nav_youtube_playlists;YouTubeFeed.SHORTS->R.string.nav_youtube_shorts;YouTubeFeed.MUSIC->R.string.nav_youtube_music;YouTubeFeed.NEWS->R.string.nav_youtube_news;YouTubeFeed.GAMING->R.string.nav_youtube_gaming;YouTubeFeed.LIVE->R.string.nav_youtube_live;YouTubeFeed.KIDS->R.string.nav_youtube_kids})
private fun feedIcon(f:YouTubeFeed)=when(f){YouTubeFeed.HOME->Icons.Filled.Home;YouTubeFeed.SUBSCRIPTIONS->Icons.Filled.Subscriptions;YouTubeFeed.CHANNELS->Icons.Filled.AccountCircle;YouTubeFeed.HISTORY->Icons.Filled.History;YouTubeFeed.PLAYLISTS->Icons.AutoMirrored.Filled.PlaylistPlay;YouTubeFeed.SHORTS->Icons.Filled.SmartDisplay;YouTubeFeed.MUSIC->Icons.Filled.MusicNote;YouTubeFeed.NEWS->Icons.Filled.Newspaper;YouTubeFeed.GAMING->Icons.Filled.SportsEsports;YouTubeFeed.LIVE->Icons.Filled.LiveTv;YouTubeFeed.KIDS->Icons.Filled.ChildCare}
