package com.ambient.tv.ui.telegram

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.ambient.tv.ui.theme.screenGutter
import kotlinx.coroutines.flow.distinctUntilChanged

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
    onOpenMedia: (Long, Int) -> Unit,
    onOpenYouTube: (String) -> Unit,
    viewModel: TelegramViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
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
        MonogramBrowseLayout.LIST -> RegisterBrowseFocusList(flatList, flat.map { it.ratingKey })
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
        val media = viewModel.selected(item.ratingKey) ?: return
        if (media.kind == TelegramMediaKind.YOUTUBE) {
            media.youtubeVideoId?.let(onOpenYouTube)
        } else if (media.playableOnTelegram) {
            viewModel.rememberPlaybackQueue(origin.map(MediaItem::ratingKey), item.ratingKey)
            telegramMediaIds(item.ratingKey)?.let { (chat, message) -> onOpenMedia(chat, message) }
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
                },
                title = { SystemPageTitle(MediaSystem.TELEGRAM) },
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
                    title = stringResource(R.string.telegram_error),
                    body = stringResource(R.string.settings_telegram_native_missing),
                    kind = BrowseStatusKind.INFO,
                    actionLabel = stringResource(R.string.nav_settings),
                    onAction = onOpenSettings, modifier = Modifier.weight(1f),
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
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(currentRows, key = { it.id }) { row ->
                            ContentRow(
                                row = row, columnFocus = focus,
                                restoreRatingKey = lastKey?.takeIf { key -> row.items.any { it.ratingKey == key } },
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
                layout == MonogramBrowseLayout.GRID ->
                    TvLazyVerticalGrid(
                        columns = deviceDensityGridCells(gridColumns, 160.dp, 8),
                        state = grid,
                        modifier = Modifier.weight(1f).pageContentUp(chain).focusGroup(),
                        contentPadding = PaddingValues(horizontal = screenGutter(), vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        gridItems(flat, key = { it.ratingKey }) { item ->
                            MediaTile(
                                item = item,
                                style = if (sectionKey == "channels") com.ambient.tv.ui.common.MediaTileStyle.POSTER
                                    else com.ambient.tv.ui.common.MediaTileStyle.LANDSCAPE,
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
                        contentPadding = PaddingValues(horizontal = screenGutter(), vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(flat, key = { it.ratingKey }) { item ->
                            MonogramListItem(item, listRows,
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
                            ViewOption("GRID", stringResource(R.string.library_view_grid), ViewPreview.GRID),
                            ViewOption("LIST", stringResource(R.string.library_view_list), ViewPreview.LIST),
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

/** Consistent list-row card with independently selectable image+metadata, no new button skin. */
@Composable
private fun MonogramListItem(
    item: MediaItem,
    rowsPerScreen: Int,
    onClick: () -> Unit,
    onFocused: () -> Unit,
) {
    var focused by remember(item.ratingKey) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val height = (520f / rowsPerScreen.coerceIn(4, 10)).dp.coerceIn(52.dp, 100.dp)
    Surface(
        onClick = onClick,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape),
        modifier = Modifier.fillMaxWidth().browseFocusTarget(item.ratingKey)
            .activateOnTvKey(onClick = onClick)
            .mediaFocusMotion(focused, focusedScale = 1.012f)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            },
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(
                    if (focused) MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                    else MaterialTheme.colorScheme.surface.copy(alpha = .64f), shape,
                )
                .border(
                    if (focused) 2.dp else 1.dp,
                    if (focused) MaterialTheme.colorScheme.primary
                    else Color.White.copy(alpha = .08f), shape,
                )
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ArtworkImage(
                item.landscapeArtUrl ?: item.thumbUrl,
                item.title,
                modifier = Modifier.height(height).width(height * 16f / 9f),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, maxLines = 2, style = MaterialTheme.typography.titleMedium)
                item.parentTitle?.takeIf(String::isNotBlank)?.let {
                    Text(it, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
