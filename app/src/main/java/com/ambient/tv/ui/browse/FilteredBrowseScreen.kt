package com.ambient.tv.ui.browse

import com.ambient.tv.ui.theme.screenGutter
import com.ambient.tv.ui.mobile.deviceDp
import androidx.activity.compose.BackHandler

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.ambient.tv.ui.common.TvLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import com.ambient.tv.ui.common.TvLazyVerticalGrid as LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.common.SoftViewChange
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.ContentRatingBadge
import com.ambient.tv.ui.common.MarqueeTitle
import com.ambient.tv.ui.common.MediaTile
import com.ambient.tv.ui.common.MediaArtworkWithStreamIcons
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.pageContentUp
import androidx.compose.foundation.focusGroup
import com.ambient.tv.ui.common.FilterBar
import com.ambient.tv.ui.common.LocalFilterBarSetting
import com.ambient.tv.ui.common.LocalFilterFocusState
import com.ambient.tv.ui.common.PanelOption
import com.ambient.tv.ui.common.ViewOption
import com.ambient.tv.ui.common.ViewOptionsChip
import com.ambient.tv.ui.common.ViewOptionsPanel
import com.ambient.tv.ui.common.ViewPreview
import com.ambient.tv.ui.common.fillWindowHeight
import com.ambient.tv.ui.common.leftKeyAction
import com.ambient.tv.ui.common.pageHeaderTop
import com.ambient.tv.ui.common.rememberFilterFocusState
import com.ambient.tv.ui.common.SkeletonTile
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.sortDirectionOptions
import com.ambient.tv.ui.common.overlayLazyGridScrollbar
import com.ambient.tv.ui.common.overlayLazyScrollbar
import com.ambient.tv.ui.common.formatDuration
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.rememberUhdScale
import com.ambient.tv.ui.common.transparentSurfaceColors
import com.ambient.tv.ui.common.bringFocusedContextIntoView
import com.ambient.tv.ui.common.mediaFocusMotion
import com.ambient.tv.ui.common.BrowseFocusContentIdentity
import com.ambient.tv.ui.common.BrowseFocusInitialTarget
import com.ambient.tv.ui.common.RegisterBrowseFocusGrid
import com.ambient.tv.ui.common.RegisterBrowseFocusList
import com.ambient.tv.ui.common.browseFocusTarget
import com.ambient.tv.ui.common.LocalBrowseFocusReturnState

@Composable
fun FilteredBrowseScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onItemClick: (String, BrowseSort) -> Unit,
    leftNavigatesBack: Boolean = true,
    viewModel: FilteredBrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val refreshError by viewModel.refreshError.collectAsState()
    com.ambient.tv.ui.common.RefreshOnEnterAndReconnect(viewModel.reconnectSources, viewModel::load)
    val uhdScale = rememberUhdScale()
    val focusChain = com.ambient.tv.ui.common.rememberPageFocusChain()
    val filterFocusState = rememberFilterFocusState(viewModel)
    val filterFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val optionsFocus = remember { FocusRequester() }
    var showOptions by remember(viewModel) { mutableStateOf(false) }
    var restoreOptionsFocus by remember(viewModel) { mutableStateOf(false) }
    var restoreSettingsFocus by rememberSaveable { mutableStateOf(false) }
    val filterBarShown = LocalFilterBarSetting.current.shown
    val loaded = state as? FilteredBrowseUiState.Loaded
    LaunchedEffect(showOptions, restoreOptionsFocus) {
        if (!showOptions && restoreOptionsFocus) {
            repeat(3) {
                withFrameNanos { }
                if (runCatching { optionsFocus.requestFocus() }.getOrDefault(false)) {
                    restoreOptionsFocus = false
                    return@LaunchedEffect
                }
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (restoreSettingsFocus) {
                repeat(3) {
                    withFrameNanos { }
                    if (runCatching { settingsFocus.requestFocus() }.getOrDefault(false)) {
                        restoreSettingsFocus = false
                        return@repeatOnLifecycle
                    }
                }
            }
        }
    }
    val sorts = listOf(
        PanelOption(BrowseSort.TITLE.name, stringResource(R.string.browse_sort_title)),
        PanelOption(BrowseSort.YEAR.name, stringResource(R.string.browse_sort_year)),
    )
    val selectSort: (String) -> Unit = {
        filterFocusState.keepControls()
        viewModel.setSort(BrowseSort.valueOf(it))
    }
    val selectSortOrder: (String) -> Unit = {
        filterFocusState.keepControls()
        viewModel.setSortDescending(it == "DESCENDING")
    }
    CompositionLocalProvider(LocalFilterFocusState provides filterFocusState) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                PageHeader(
                    focusChain = focusChain,
                    modifier = Modifier.padding(start = screenGutter(), end = screenGutter(), top = pageHeaderTop(24.dp), bottom = 10.dp),
                    actions = {
                        TvChip(
                            text = stringResource(R.string.back),
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            iconOnly = true,
                            modifier = if (leftNavigatesBack) Modifier.leftKeyAction(onBack) else Modifier,
                            onClick = onBack,
                        )
                        TvChip(
                            text = stringResource(R.string.nav_settings),
                            icon = Icons.Filled.Settings,
                            iconOnly = true,
                            modifier = Modifier.focusRequester(settingsFocus),
                            onClick = { filterFocusState.keepControls(); restoreSettingsFocus = true; onOpenSettings() },
                        )
                        TvChip(
                            text = stringResource(R.string.action_reload),
                            icon = Icons.Filled.Refresh,
                            iconOnly = true,
                            loading = refreshing || state is FilteredBrowseUiState.Loading,
                            onClick = { filterFocusState.keepControls(); viewModel.refresh() },
                        )
                        if (loaded != null) ViewOptionsChip(
                            activeCount = 0,
                            modifier = Modifier.focusRequester(optionsFocus),
                            onClick = {
                                filterFocusState.keepControls()
                                restoreOptionsFocus = true
                                showOptions = true
                            },
                        )
                    },
                ) {
                    Text(loaded?.title ?: viewModel.title, style = MaterialTheme.typography.headlineMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (loaded != null) Text(stringResource(R.string.library_item_count, loaded.items.size),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (refreshError != null && loaded != null) Text(
                    stringResource(R.string.library_load_failed),
                    modifier = Modifier.padding(horizontal = screenGutter(), vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (loaded != null && filterBarShown) FilterBar(
                    groups = emptyList(),
                    onSelect = { _, _ -> },
                    sorts = sorts,
                    selectedSort = loaded.sort.name,
                    onSort = selectSort,
                    sortOrders = browseSortOrders(loaded.sort),
                    selectedSortOrder = if (loaded.sortDescending) "DESCENDING" else "ASCENDING",
                    onSortOrder = selectSortOrder,
                    modifier = Modifier.padding(horizontal = screenGutter()),
                    focusRequester = filterFocus,
                    up = focusChain.actions,
                    focusChain = focusChain,
                )
                when (val value = state) {
                    FilteredBrowseUiState.Loading -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(deviceDp(184.dp, 144.dp) * uhdScale),
                        contentPadding = PaddingValues(start = screenGutter(), end = screenGutter(), top = 14.dp, bottom = 36.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) { items(18) { SkeletonTile() } }
                    is FilteredBrowseUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        com.ambient.tv.ui.common.BrowseStatusPanel(
                            title = stringResource(R.string.library_load_failed),
                            kind = com.ambient.tv.ui.common.BrowseStatusKind.ERROR,
                            body = value.message.ifBlank { stringResource(R.string.error_generic) },
                            actionLabel = stringResource(R.string.home_retry),
                            onAction = viewModel::load,
                        )
                    }
                    is FilteredBrowseUiState.Loaded -> {
                        BrowseFocusContentIdentity(listOf(viewModel, value.viewMode, value.sort, value.sortDescending))
                        BrowseFocusInitialTarget(value.focusedRatingKey?.takeIf { key -> value.items.any { it.ratingKey == key } }
                            ?: value.items.firstOrNull()?.ratingKey)
                        if (value.items.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            com.ambient.tv.ui.common.BrowseStatusPanel(stringResource(R.string.library_empty_title), stringResource(R.string.library_empty_body))
                        } else Box(Modifier.fillMaxSize()
                            .focusRestorer()
                            .pageContentUp(focusChain) { if (filterBarShown) filterFocus else focusChain.actions }
                            .focusGroup()) {
                            val onFocused: (MediaItem) -> Unit = {
                                if (!showOptions) filterFocusState.enterContent()
                                viewModel.onItemFocused(it)
                            }
                            SoftViewChange(value.viewMode, Modifier.fillMaxSize()) {
                                if (value.viewMode == BrowseViewMode.GRID) BrowseGrid(value, { onItemClick(it, value.sort) }, onFocused)
                                else BrowseList(value, { onItemClick(it, value.sort) }, onFocused)
                            }
                        }
                    }
                }
            }
            if (showOptions && loaded != null) {
                com.ambient.tv.ui.common.RightPanelOverlay(onOutsideTap = { showOptions = false }) {
                Box(Modifier.align(Alignment.TopEnd)) {
                    ViewOptionsPanel(
                        views = listOf(
                            ViewOption(BrowseViewMode.GRID.name, stringResource(R.string.browse_view_grid), ViewPreview.GRID),
                            ViewOption(BrowseViewMode.LIST.name, stringResource(R.string.browse_view_list), ViewPreview.LIST),
                        ),
                        selectedView = loaded.viewMode.name,
                        onView = { filterFocusState.keepControls(); viewModel.setViewMode(BrowseViewMode.valueOf(it)) },
                        showDensity = false,
                        gridColumns = 6, listRows = 6,
                        onGridColumns = {}, onListRows = {},
                        sorts = sorts,
                        selectedSort = loaded.sort.name,
                        onSort = selectSort,
                        sortOrders = browseSortOrders(loaded.sort),
                        selectedSortOrder = if (loaded.sortDescending) "DESCENDING" else "ASCENDING",
                        onSortOrder = selectSortOrder,
                        onClose = { showOptions = false },
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun browseSortOrders(sort: BrowseSort) = when (sort) {
    BrowseSort.TITLE -> sortDirectionOptions(stringResource(R.string.library_sort_az), stringResource(R.string.library_sort_za))
    BrowseSort.YEAR -> sortDirectionOptions(stringResource(R.string.library_sort_oldest), stringResource(R.string.library_sort_newest))
}

@Composable
private fun BrowseGrid(state: FilteredBrowseUiState.Loaded, onClick: (String) -> Unit, onFocused: (MediaItem) -> Unit) {
    // Captured once per list; a live focusedRatingKey would snap-scroll on every focus move.
    val restoreKey = remember(state.items) { state.focusedRatingKey }
    val index = remember(state.items, restoreKey) { state.items.indexOfFirst { it.ratingKey == restoreKey }.coerceAtLeast(0) }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = index)
    RegisterBrowseFocusGrid(gridState, remember(state.items) { state.items.map { it.ratingKey } })
    val filterFocus = LocalFilterFocusState.current
    LaunchedEffect(restoreKey, state.items) {
        if (filterFocus?.allowContentAutoFocus != false && restoreKey != null && index in state.items.indices && state.items[index].ratingKey == restoreKey) {
            gridState.scrollToItem(index)
        }
    }
    val uhdScale = rememberUhdScale()
    LazyVerticalGrid(
        state = gridState, modifier = Modifier.overlayLazyGridScrollbar(gridState, gutter = screenGutter()), columns = GridCells.Adaptive(deviceDp(184.dp, 144.dp) * uhdScale), contentPadding = PaddingValues(screenGutter(), 14.dp, screenGutter(), 36.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(state.items, key = { _, it -> it.ratingKey }) { itemIndex, item ->
            MediaTile(
                item = item,
                style = com.ambient.tv.ui.common.MediaTileStyle.POSTER,
                onClick = { onClick(item.ratingKey) },
                onFocused = { onFocused(item) },
                requestInitialFocus = itemIndex == index,
            )
        }
    }
}

@Composable
private fun BrowseList(state: FilteredBrowseUiState.Loaded, onClick: (String) -> Unit, onFocused: (MediaItem) -> Unit) {
    val initialFocusedKey = remember(state.items) {
        state.focusedRatingKey?.takeIf { key -> state.items.any { it.ratingKey == key } }
            ?: state.items.firstOrNull()?.ratingKey
    }
    val index = remember(state.items, initialFocusedKey) { state.items.indexOfFirst { it.ratingKey == initialFocusedKey }.coerceAtLeast(0) }
    val requester = remember { FocusRequester() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = index)
    RegisterBrowseFocusList(listState, remember(state.items) { state.items.map { it.ratingKey } })
    val sharedFocusOwner = LocalBrowseFocusReturnState.current != null
    val filterFocus = LocalFilterFocusState.current
    LaunchedEffect(sharedFocusOwner) {
        if (sharedFocusOwner) return@LaunchedEffect
        if (filterFocus?.allowContentAutoFocus != false && initialFocusedKey != null && index in state.items.indices && state.items[index].ratingKey == initialFocusedKey) {
            runCatching { requester.requestFocus() }
        }
    }
    LazyColumn(allowUpExit = true, state = listState, modifier = Modifier.overlayLazyScrollbar(listState, gutter = screenGutter()), contentPadding = PaddingValues(screenGutter(), 14.dp, screenGutter(), 36.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(state.items, key = { _, it -> it.ratingKey }) { _, item ->
            var isFocused by remember(item.ratingKey) { mutableStateOf(false) }
            val focus = (if (item.ratingKey == initialFocusedKey) Modifier.focusRequester(requester) else Modifier)
                .browseFocusTarget(item.ratingKey)
            val rowShape = RoundedCornerShape(10.dp)
            Surface(onClick = { onClick(item.ratingKey) }, colors = transparentSurfaceColors(), scale = noSurfaceScale(), shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = rowShape), modifier = focus.mediaFocusMotion(isFocused, focusedScale = 1.012f).activateOnTvKey { onClick(item.ratingKey) }.bringFocusedContextIntoView().onFocusChanged { isFocused = it.isFocused; if (it.isFocused) onFocused(item) }) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.58f), rowShape)
                        .border(if (isFocused) 1.5.dp else 1.dp, if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.06f), rowShape)
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    MediaArtworkWithStreamIcons(
                        item = item,
                        model = item.landscapeArtUrl,
                        modifier = Modifier.width(166.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        MarqueeTitle(item.title, focused = isFocused, style = MaterialTheme.typography.titleMedium)
                        val meta = buildList { item.year?.let { add(it.toString()) }; formatDuration(item.durationMs)?.let(::add) }.joinToString(" · ")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            item.contentRating?.takeIf { it.isNotBlank() }?.let { ContentRatingBadge(it) }
                        }
                        item.summary?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}
