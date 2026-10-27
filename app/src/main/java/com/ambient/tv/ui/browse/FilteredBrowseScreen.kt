package com.ambient.tv.ui.browse

import com.ambient.tv.ui.theme.screenGutter
import com.ambient.tv.ui.mobile.deviceDp

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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.ContentRatingBadge
import com.ambient.tv.ui.common.MarqueeTitle
import com.ambient.tv.ui.common.MediaTile
import com.ambient.tv.ui.common.MediaArtworkWithStreamIcons
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.pageContentUp
import androidx.compose.foundation.focusGroup
import com.ambient.tv.ui.common.PageUtilityBar
import com.ambient.tv.ui.common.SkeletonTile
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.overlayLazyGridScrollbar
import com.ambient.tv.ui.common.overlayLazyScrollbar
import com.ambient.tv.ui.common.formatDuration
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.rememberUhdScale
import com.ambient.tv.ui.common.transparentSurfaceColors
import com.ambient.tv.ui.common.bringFocusedContextIntoView
import com.ambient.tv.ui.common.mediaFocusMotion

@Composable
fun FilteredBrowseScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onItemClick: (String, BrowseSort) -> Unit,
    leftNavigatesBack: Boolean = true,
    viewModel: FilteredBrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val uhdScale = rememberUhdScale()
    Box(Modifier.fillMaxSize()) {
        when (val value = state) {
            FilteredBrowseUiState.Loading -> LazyVerticalGrid(
                columns = GridCells.Adaptive(deviceDp(184.dp, 144.dp) * uhdScale),
                contentPadding = PaddingValues(start = screenGutter(), end = screenGutter(), top = screenGutter() + 56.dp, bottom = 36.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { items(18) { SkeletonTile() } }
            is FilteredBrowseUiState.Error -> Box(Modifier.fillMaxSize().padding(top = screenGutter() + 38.dp), contentAlignment = Alignment.Center) {
                StatePanel(
                    title = stringResource(R.string.library_load_failed),
                    body = value.message.ifBlank { stringResource(R.string.error_generic) },
                    actionLabel = stringResource(R.string.home_retry),
                    onAction = viewModel::load,
                )
            }
            is FilteredBrowseUiState.Loaded -> Column(Modifier.fillMaxSize().padding(top = screenGutter() + 44.dp)) {
                val focusChain = com.ambient.tv.ui.common.rememberPageFocusChain()
                PageHeader(
                    focusChain = focusChain,
                    modifier = Modifier.padding(horizontal = screenGutter(), vertical = 8.dp),
                    actions = {
                        val grid = value.viewMode == BrowseViewMode.GRID
                        TvChip(if (value.sort == BrowseSort.TITLE) stringResource(R.string.browse_sort_title) else stringResource(R.string.browse_sort_year), icon = Icons.Filled.SwapVert, iconOnly = true, onClick = viewModel::cycleSort)
                        TvChip(if (grid) stringResource(R.string.browse_view_list) else stringResource(R.string.browse_view_grid), icon = if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView, iconOnly = true, onClick = viewModel::toggleViewMode)
                    },
                ) {
                    Text(value.title, style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.library_item_count, value.items.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (value.items.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    StatePanel(stringResource(R.string.library_empty_title), stringResource(R.string.library_empty_body))
                } else Box(Modifier.fillMaxSize().pageContentUp(focusChain).focusGroup()) {
                    if (value.viewMode == BrowseViewMode.GRID) BrowseGrid(value, { onItemClick(it, value.sort) }, viewModel::onItemFocused)
                    else BrowseList(value, { onItemClick(it, value.sort) }, viewModel::onItemFocused)
                }
            }
        }
        PageUtilityBar(
            onBack = onBack,
            onSettings = onOpenSettings,
            leftNavigatesBack = leftNavigatesBack,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun BrowseGrid(state: FilteredBrowseUiState.Loaded, onClick: (String) -> Unit, onFocused: (MediaItem) -> Unit) {
    // Captured once per list; a live focusedRatingKey would snap-scroll on every focus move.
    val restoreKey = remember(state.items) { state.focusedRatingKey }
    val index = remember(state.items, restoreKey) { state.items.indexOfFirst { it.ratingKey == restoreKey }.coerceAtLeast(0) }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = index)
    LaunchedEffect(restoreKey, state.items) {
        if (restoreKey != null && index in state.items.indices && state.items[index].ratingKey == restoreKey) {
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
    LaunchedEffect(Unit) {
        if (initialFocusedKey != null && index in state.items.indices && state.items[index].ratingKey == initialFocusedKey) {
            runCatching { requester.requestFocus() }
        }
    }
    LazyColumn(allowUpExit = true, state = listState, modifier = Modifier.overlayLazyScrollbar(listState, gutter = screenGutter()), contentPadding = PaddingValues(screenGutter(), 14.dp, screenGutter(), 36.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(state.items, key = { _, it -> it.ratingKey }) { _, item ->
            var isFocused by remember(item.ratingKey) { mutableStateOf(false) }
            val focus = if (item.ratingKey == initialFocusedKey) Modifier.focusRequester(requester) else Modifier
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
