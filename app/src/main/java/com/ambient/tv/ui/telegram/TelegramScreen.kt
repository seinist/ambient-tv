package com.ambient.tv.ui.telegram

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.telegram.TelegramMediaKind
import com.ambient.tv.data.telegram.TelegramChannel
import com.ambient.tv.data.telegram.MonogramLoginStep
import com.ambient.tv.data.telegram.telegramMediaIds
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.BrowseFocusContentIdentity
import com.ambient.tv.ui.common.BrowseFocusInitialTarget
import com.ambient.tv.ui.common.BrowseStatusKind
import com.ambient.tv.ui.common.BrowseStatusPanel
import com.ambient.tv.ui.common.ContentRow
import com.ambient.tv.ui.common.FilterBar
import com.ambient.tv.ui.common.FilterBarModel
import com.ambient.tv.ui.common.LocalFilterBarSetting
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.MediaTile
import com.ambient.tv.ui.common.LocalMonogramRowCardWidth
import com.ambient.tv.ui.common.GridDensityProfile
import com.ambient.tv.ui.common.visibleGridColumns
import com.ambient.tv.ui.common.MonogramArtwork
import com.ambient.tv.ui.common.MarqueeTitle
import com.ambient.tv.ui.common.monogramCardMeta
import com.ambient.tv.ui.common.bringFocusedContextIntoView
import com.ambient.tv.ui.common.PageChipRow
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.PanelOption
import com.ambient.tv.ui.common.PanelOptionGroup
import com.ambient.tv.ui.common.RegisterBrowseFocusGrid
import com.ambient.tv.ui.common.RegisterBrowseFocusList
import com.ambient.tv.ui.common.RegisterBrowseFocusRows
import com.ambient.tv.ui.common.RightPanelOverlay
import com.ambient.tv.ui.common.SystemPageTitle
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.TvLazyVerticalGrid
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.ViewOption
import com.ambient.tv.ui.common.ViewOptionsChip
import com.ambient.tv.ui.common.ViewOptionsPanel
import com.ambient.tv.ui.common.ViewPreview
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.browseFocusTarget
import com.ambient.tv.ui.common.handheldSwipeTabs
import com.ambient.tv.ui.common.mediaFocusMotion
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.pageContentUp
import com.ambient.tv.ui.common.pageHeaderTop
import com.ambient.tv.ui.common.rememberBrowseGridState
import com.ambient.tv.ui.common.rememberBrowseListState
import com.ambient.tv.ui.common.rememberPageFocusChain
import com.ambient.tv.ui.common.rememberRowColumnFocus
import com.ambient.tv.ui.common.sortDirectionOptions
import com.ambient.tv.ui.common.transparentSurfaceColors
import com.ambient.tv.ui.mobile.deviceDensityGridCells
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.ui.theme.screenGutter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

private val sections = listOf(
    "recent" to R.string.telegram_recent,
    "channels" to R.string.telegram_channels,
    "videos" to R.string.telegram_videos,
    "youtube" to R.string.telegram_youtube,
    "audio" to R.string.telegram_audio,
    "photos" to R.string.telegram_photos,
)

@Composable
fun TelegramScreen(
    sectionKey: String,
    onSelectSection: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChannel: (Long) -> Unit,
    onOpenPost: (Long, Int) -> Unit,
    viewModel: TelegramViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val login by viewModel.login.collectAsState()
    val activeChannelId = remember(sectionKey) {
        sectionKey.takeIf { it.startsWith("channel:") }?.substringAfter(':')?.toLongOrNull()
    }
    var activeChannel by remember(sectionKey) { mutableStateOf<TelegramChannel?>(null) }
    var joiningChannel by remember(sectionKey) { mutableStateOf(false) }
    var channelError by remember(sectionKey) { mutableStateOf<String?>(null) }
    val channelScope = rememberCoroutineScope()
    LaunchedEffect(activeChannelId) {
        activeChannel = activeChannelId?.let { viewModel.channel(it) }
    }
    val chain = rememberPageFocusChain()
    val focus = rememberRowColumnFocus()
    val rowList = rememberBrowseListState()
    val flatList = rememberBrowseListState()
    val grid = rememberBrowseGridState()
    val shownTabs = sections.filter { it.first in viewModel.visibleSections || it.first == sectionKey }
        .ifEmpty { sections.take(1) }
    val canonical = sectionKey.takeIf { key -> shownTabs.any { it.first == key } }
        ?: shownTabs.first().first
    var lastKey by rememberSaveable(sectionKey) { mutableStateOf<String?>(null) }
    var layoutKey by rememberSaveable(sectionKey) { mutableStateOf(viewModel.viewMode(sectionKey).name) }
    val layout = MonogramBrowseLayout.valueOf(layoutKey)
    var gridColumns by remember { mutableIntStateOf(viewModel.gridColumns) }
    var listRows by remember { mutableIntStateOf(viewModel.listRows) }
    var sortKey by rememberSaveable(sectionKey) { mutableStateOf(MonogramBrowseSort.DATE.name) }
    val sort = MonogramBrowseSort.valueOf(sortKey)
    var descending by rememberSaveable(sectionKey) { mutableStateOf(true) }
    var channelFilter by rememberSaveable(sectionKey) { mutableStateOf("all") }
    var searchVisible by rememberSaveable(sectionKey) { mutableStateOf(false) }
    var query by rememberSaveable(sectionKey) { mutableStateOf("") }
    var showViewOptions by remember { mutableStateOf(false) }
    var restorePanelFocus by remember { mutableStateOf(false) }
    val viewOptionsFocus = remember { FocusRequester() }
    val currentRows = remember(state.rows, query, channelFilter, sort, descending) {
        visibleMonogramRows(state.rows, query, channelFilter.takeUnless { it == "all" }, sort, descending)
    }
    val flat = remember(currentRows, sort, descending) { flatMonogramItems(currentRows, sort, descending) }
    val availableChannels = remember(state.rows) {
        state.rows.map { it.id to it.title }.distinctBy { it.first }
    }
    val sortOptions = listOf(
        PanelOption("DATE", stringResource(R.string.library_sort_recent)),
        PanelOption("TITLE", stringResource(R.string.library_sort_title)),
        PanelOption("DURATION", stringResource(R.string.library_sort_duration)),
    )
    val sortOrders = if (sort == MonogramBrowseSort.TITLE) sortDirectionOptions(
        stringResource(R.string.library_sort_az), stringResource(R.string.library_sort_za),
    ) else sortDirectionOptions(
        stringResource(R.string.library_sort_oldest), stringResource(R.string.library_sort_newest),
    )
    val channelFilters = if (availableChannels.size > 1) listOf(PanelOptionGroup(
        id = "channel",
        title = stringResource(R.string.telegram_channels),
        options = listOf(PanelOption("all", stringResource(R.string.library_filter_all))) +
            availableChannels.map { (id, name) -> PanelOption(id, name) },
        selectedKey = channelFilter,
    )) else emptyList()
    val filtersVisible = LocalFilterBarSetting.current.shown
    val filterModel = FilterBarModel(
        groups = channelFilters,
        onSelect = { _, key -> channelFilter = key },
        sorts = sortOptions,
        selectedSort = sortKey,
        onSort = { sortKey = it },
        sortOrders = sortOrders,
        selectedSortOrder = if (descending) "DESCENDING" else "ASCENDING",
        onSortOrder = { descending = it == "DESCENDING" },
        onReset = if (channelFilter != "all" || query.isNotBlank()) ({
            channelFilter = "all"
            query = ""
        }) else null,
    )
    LaunchedEffect(sectionKey) { viewModel.open(sectionKey) }
    LaunchedEffect(showViewOptions, restorePanelFocus) {
        if (!showViewOptions && restorePanelFocus) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { viewOptionsFocus.requestFocus() }
            restorePanelFocus = false
        }
    }
    BackHandler(enabled = searchVisible && !showViewOptions) {
        if (query.isNotBlank()) query = "" else searchVisible = false
    }
    BrowseFocusContentIdentity(listOf(sectionKey, layoutKey, sortKey, descending, channelFilter, query))
    BrowseFocusInitialTarget(
        lastKey ?: currentRows.firstOrNull()?.items?.firstOrNull()?.ratingKey,
        if (layout == MonogramBrowseLayout.ROWS) currentRows.firstOrNull()?.id else null,
    )
    when (layout) {
        MonogramBrowseLayout.ROWS -> RegisterBrowseFocusRows(rowList,
            currentRows.map { row -> row.id to row.items.map { it.ratingKey } })
        MonogramBrowseLayout.GRID -> RegisterBrowseFocusGrid(grid, flat.map { it.ratingKey })
        MonogramBrowseLayout.LIST, MonogramBrowseLayout.INFO_LIST ->
            RegisterBrowseFocusList(flatList, flat.map { it.ratingKey })
    }
    LaunchedEffect(layout, grid, flatList, flat.size) {
        if (layout == MonogramBrowseLayout.ROWS) return@LaunchedEffect
        snapshotFlow {
            if (layout == MonogramBrowseLayout.GRID)
                grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            else flatList.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.distinctUntilChanged().collect { last ->
            if (last >= 0 && last >= flat.size - 10) viewModel.loadMore()
        }
    }
    com.ambient.tv.ui.navigation.ReportSidebarContentReady(
        state.section == sectionKey && (!state.loading || state.rows.isNotEmpty()),
    )
    fun choose(item: MediaItem, origin: List<MediaItem>) {
        if (item.ratingKey.startsWith(TELEGRAM_CHANNEL_ITEM_PREFIX)) {
            item.ratingKey.removePrefix(TELEGRAM_CHANNEL_ITEM_PREFIX)
                .toLongOrNull()?.let(onOpenChannel)
            return
        }
        // A short click opens the post detail, where its full caption, linked YouTube
        // videos and (when allowed) the local media player are separate actions.
        val selected = viewModel.selected(item.ratingKey)
        if (selected?.playableOnTelegram == true) {
            viewModel.rememberPlaybackQueue(origin.map(MediaItem::ratingKey), item.ratingKey)
        }
        com.ambient.tv.data.telegram.telegramPostIds(item.ratingKey)?.let { (chat, message) ->
            onOpenPost(chat, message)
        }
    }
    val activeSortCount = (if (channelFilter != "all") 1 else 0) +
        (if (query.isNotBlank()) 1 else 0)
    val closeOptions = {
        showViewOptions = false
        restorePanelFocus = true
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().handheldSwipeTabs(
                tabs = shownTabs.map { it.first }, selected = canonical,
                onSelect = onSelectSection, enabled = !sectionKey.startsWith("channel:") &&
                    !showViewOptions && !searchVisible,
            ),
        ) {
            PageHeader(
                Modifier.fillMaxWidth().padding(
                    start = screenGutter(), end = screenGutter(), top = pageHeaderTop(12.dp)),
                focusChain = chain,
                actions = {
                    TvChip(
                        text = stringResource(R.string.action_search),
                        icon = Icons.Filled.Search, selected = searchVisible, iconOnly = true,
                        onClick = { searchVisible = !searchVisible },
                    )
                    TvChip(
                        text = stringResource(R.string.tvh_refresh),
                        selected = false, icon = Icons.Filled.Refresh, iconOnly = true,
                        loading = state.loading || state.loadingMore,
                        onClick = { viewModel.open(sectionKey, refresh = true) },
                    )
                    ViewOptionsChip(activeSortCount, onClick = { showViewOptions = true },
                        modifier = Modifier.focusRequester(viewOptionsFocus))
                    if (activeChannel?.subscribed != null) {
                        TvChip(
                            text = stringResource(
                                if (activeChannel?.subscribed == true) R.string.monogram_leave_channel
                                else R.string.monogram_join_channel),
                            selected = activeChannel?.subscribed == true,
                            icon = Icons.Filled.Subscriptions,
                            iconOnly = true,
                            loading = joiningChannel,
                            onClick = {
                                val id = activeChannelId
                                if (id != null && !joiningChannel) {
                                    joiningChannel = true
                                    channelError = null
                                    channelScope.launch {
                                        try {
                                            val wasJoined = activeChannel?.subscribed == true
                                            val accepted = if (wasJoined) viewModel.leaveChannel(id)
                                                else viewModel.joinChannel(id)
                                            if (accepted) {
                                                activeChannel = activeChannel?.copy(subscribed = !wasJoined)
                                                if (wasJoined) onSelectSection("channels")
                                            } else channelError = if (wasJoined)
                                                "Monogram channel could not be left"
                                            else "Monogram channel join not accepted"
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (error: Exception) {
                                            channelError = error.message ?: "Monogram channel join failed"
                                        } finally { joiningChannel = false }
                                    }
                                }
                            },
                        )
                    }
                },
                title = {
                    if (activeChannelId == null) {
                        SystemPageTitle(MediaSystem.TELEGRAM)
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val label = activeChannel?.title ?: state.rows.firstOrNull()?.title ?: "Monogram"
                            activeChannel?.thumbnailUrl?.let { url ->
                                ArtworkImage(url, label, Modifier.size(48.dp).clip(CircleShape))
                            } ?: Icon(
                                Icons.Filled.AccountCircle, label,
                                Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column {
                                Text(label, style = MaterialTheme.typography.headlineMedium,
                                    maxLines = 1)
                                if (activeChannel?.subscribed == true) {
                                    Text(stringResource(R.string.monogram_joined_channel),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                                channelError?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                },
            )
            PageChipRow(chain, contentPadding = PaddingValues(horizontal = screenGutter()),
                selectedIndex = shownTabs.indexOfFirst { it.first == canonical }.coerceAtLeast(0)) {
                items(shownTabs, key = { it.first }) { (key, label) ->
                    TvChip(
                        text = stringResource(label),
                        selected = key == canonical && !sectionKey.startsWith("channel:"),
                        modifier = if (key == canonical) Modifier.focusRequester(chain.selectedChip) else Modifier,
                        onClick = { onSelectSection(key) },
                    )
                }
            }
            if (searchVisible) {
                TvTextField(
                    value = query, onValueChange = { query = it },
                    label = stringResource(R.string.monogram_search_loaded),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = screenGutter(), vertical = 8.dp),
                )
            }
            if (filtersVisible && state.available) {
                FilterBar(filterModel, focusChain = chain,
                    modifier = Modifier.padding(horizontal = screenGutter(), vertical = 6.dp))
            }
            when {
                !state.available -> BrowseStatusPanel(
                    title = stringResource(
                        if (login.step == MonogramLoginStep.UNAVAILABLE) R.string.telegram_error
                        else R.string.monogram_login_signed_out,
                    ),
                    body = stringResource(
                        when {
                            login.step == MonogramLoginStep.UNAVAILABLE ->
                                R.string.settings_telegram_native_missing
                            !viewModel.apiCredentialsReady -> R.string.monogram_api_required
                            else -> R.string.monogram_login_signed_out
                        },
                    ),
                    kind = BrowseStatusKind.INFO,
                    actionLabel = stringResource(
                        if (login.step == MonogramLoginStep.UNAVAILABLE || !viewModel.apiCredentialsReady)
                            R.string.nav_settings else R.string.action_sign_in,
                    ),
                    onAction = onOpenSettings,
                    modifier = Modifier.weight(1f),
                )
                state.loading && state.rows.isEmpty() -> BrowseStatusPanel(
                    title = stringResource(R.string.telegram_recent),
                    body = stringResource(R.string.settings_telegram_notice),
                    kind = BrowseStatusKind.INFO, modifier = Modifier.weight(1f),
                )
                state.error != null && state.rows.isEmpty() -> BrowseStatusPanel(
                    title = stringResource(R.string.telegram_error),
                    body = state.error, kind = BrowseStatusKind.ERROR,
                    actionLabel = stringResource(R.string.tvh_refresh),
                    onAction = { viewModel.open(sectionKey, refresh = true) },
                    modifier = Modifier.weight(1f),
                )
                currentRows.isEmpty() -> BrowseStatusPanel(
                    title = stringResource(R.string.telegram_no_results),
                    kind = BrowseStatusKind.EMPTY,
                    actionLabel = if (state.nextCursor != null) stringResource(R.string.action_reload) else null,
                    onAction = if (state.nextCursor != null) viewModel::loadMore else null,
                    modifier = Modifier.weight(1f),
                )
                layout == MonogramBrowseLayout.ROWS ->
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = rowList, modifier = Modifier.weight(1f).pageContentUp(chain),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(currentRows, key = { it.id }) { row ->
                            // SmartTube computes row-card widths from the viewport and the same
                            // density setting, rather than using a fixed TV poster width on phones.
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                                val handheld = LocalHandheldUi.current
                                val count = visibleGridColumns(
                                    gridColumns, ViewPreview.LANDSCAPE, GridDensityProfile.SMARTTUBE,
                                    handheld, configuration.screenWidthDp, configuration.screenHeightDp,
                                ).coerceAtLeast(1)
                                val gap = 16.dp
                                val cardWidth = ((maxWidth - screenGutter() * 2 - gap * (count - 1)) / count)
                                    .coerceAtLeast(if (handheld) 104.dp else 160.dp)
                                CompositionLocalProvider(LocalMonogramRowCardWidth provides cardWidth) {
                                    ContentRow(
                                        row = row, columnFocus = focus,
                                        restoreRatingKey = lastKey?.takeIf { key ->
                                            row.items.any { it.ratingKey == key }
                                        },
                                        onItemFocused = { focused ->
                                            lastKey = focused.ratingKey
                                            val index = row.items.indexOfFirst { it.ratingKey == focused.ratingKey }
                                            if (index >= row.items.size - 8) viewModel.loadMore()
                                        },
                                        onViewportNearEnd = { viewModel.loadMore() },
                                        onItemClick = { choose(it, row.items) },
                                    )
                                }
                            }
                        }
                    }
                layout == MonogramBrowseLayout.GRID ->
                    TvLazyVerticalGrid(
                        columns = deviceDensityGridCells(
                            com.ambient.tv.ui.common.landscapeGridColumns(gridColumns), 260.dp, 4),

                        state = grid,
                        modifier = Modifier.weight(1f).pageContentUp(chain).focusGroup(),
                        contentPadding = PaddingValues(screenGutter(), 10.dp, screenGutter(), 36.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        gridItems(flat, key = { it.ratingKey }) { item ->
                            MediaTile(
                                item = item,
                                style = com.ambient.tv.ui.common.MediaTileStyle.LANDSCAPE,
                                fillCell = true,
                                modifier = Modifier.browseFocusTarget(item.ratingKey),
                                onFocused = {
                                    lastKey = item.ratingKey
                                    if (flat.indexOfFirst { it.ratingKey == item.ratingKey } >= flat.size - 10)
                                        viewModel.loadMore()
                                },
                                onClick = { choose(item, flat) },
                            )
                        }
                    }
                else ->
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = flatList, modifier = Modifier.weight(1f).pageContentUp(chain).focusGroup(),
                        contentPadding = PaddingValues(screenGutter(), 10.dp, screenGutter(), 36.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(flat, key = { it.ratingKey }) { item ->
                            MonogramListItem(item, listRows,
                                detailed = layout == MonogramBrowseLayout.INFO_LIST,
                                onClick = { choose(item, flat) },
                                onFocused = {
                                    lastKey = item.ratingKey
                                    if (flat.indexOfFirst { it.ratingKey == item.ratingKey } >= flat.size - 10)
                                        viewModel.loadMore()
                                })
                        }
                    }
            }
            if (state.loadingMore) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.padding(start = screenGutter(), bottom = 10.dp),
                )
            }
            if (state.pageError != null) {
                TvActionButton(
                    text = stringResource(R.string.action_reload),
                    onClick = viewModel::loadMore,
                )
            }
        }
        if (showViewOptions) {
            RightPanelOverlay(onOutsideTap = closeOptions) {
                Box(Modifier.align(Alignment.TopEnd)) {
                    ViewOptionsPanel(
                        views = listOf(
                            ViewOption("ROWS", stringResource(R.string.library_view_rows), ViewPreview.ROWS),
                            ViewOption("GRID", stringResource(R.string.library_view_grid), ViewPreview.LANDSCAPE),
                            ViewOption("LIST", stringResource(R.string.library_view_list), ViewPreview.LIST),
                            ViewOption("INFO_LIST", stringResource(R.string.library_view_info_list), ViewPreview.INFO_LIST),
                        ),
                        selectedView = layout.name,
                        onView = {
                            layoutKey = it
                            viewModel.changeViewMode(sectionKey, MonogramBrowseLayout.valueOf(it))
                        },
                        gridColumns = gridColumns, listRows = listRows,
                        onGridColumns = {
                            gridColumns = it
                            viewModel.setGridColumns(it)
                        },
                        onListRows = {
                            listRows = it
                            viewModel.setListRows(it)
                        },
                        sorts = sortOptions, selectedSort = sortKey,
                        onSort = { sortKey = it },
                        sortOrders = sortOrders,
                        selectedSortOrder = if (descending) "DESCENDING" else "ASCENDING",
                        onSortOrder = { descending = it == "DESCENDING" },
                        filterGroups = channelFilters,
                        onFilterGroup = { _, key -> channelFilter = key },
                        filtersActive = activeSortCount > 0,
                        onResetFilters = {
                            channelFilter = "all"
                            query = ""
                        },
                        onClose = closeOptions,
                    )
                }
            }
        }
    }
}

/** Same SmartTube-like focused surface, marquee labels and avatar treatment as Monogram cards. */
@Composable
private fun MonogramListItem(
    item: MediaItem,
    rowsPerScreen: Int,
    detailed: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
) {
    var focused by remember(item.ratingKey) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val rowScale = (7f / rowsPerScreen.coerceIn(4, 10)).coerceIn(.72f, 1.35f)
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val info = remember(item, locale) {
        monogramCardMeta(
            item,
            dateLabel = { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it)) },
            durationLabel = { com.ambient.tv.ui.common.formatPlaybackClock(it) },
        )
    }
    Surface(
        onClick = onClick,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape),
        modifier = Modifier.fillMaxWidth().browseFocusTarget(item.ratingKey)
            .activateOnTvKey(onClick = onClick)
            .mediaFocusMotion(focused, focusedScale = 1.012f)
            .bringFocusedContextIntoView()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            },
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(
                    if (focused) MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                    else Color.Transparent,
                    shape,
                )
                .border(
                    if (focused) 2.dp else 1.dp,
                    if (focused) MaterialTheme.colorScheme.primary
                    else Color.White.copy(alpha = .06f),
                    shape,
                )
                .padding(10.dp * rowScale),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp * rowScale),
        ) {
            MonogramArtwork(
                item = item,
                style = com.ambient.tv.ui.common.MediaTileStyle.LANDSCAPE,
                modifier = Modifier.width(deviceDp(if (detailed) 190.dp else 128.dp, 104.dp) * rowScale)
                    .aspectRatio(16f / 9f).clip(RoundedCornerShape(7.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MarqueeTitle(item.title, focused = focused,
                    style = MaterialTheme.typography.titleMedium)
                MarqueeTitle(
                    info.ifBlank {
                        if (item.ratingKey.startsWith(TELEGRAM_CHANNEL_ITEM_PREFIX))
                            "Monogram" else ""
                    },
                    focused = focused,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (detailed) item.summary?.takeIf(String::isNotBlank)?.let { caption ->
                    MarqueeTitle(
                        caption,
                        focused = focused,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
