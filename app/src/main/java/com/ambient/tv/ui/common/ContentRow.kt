package com.ambient.tv.ui.common

import com.ambient.tv.ui.theme.screenGutter
import com.ambient.tv.ui.mobile.deviceDp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.domain.model.MediaItem

data class ContentRowData(
    val title: String,
    val items: List<MediaItem>,
    val style: MediaTileStyle = MediaTileStyle.AUTO,
    val id: String = title,
    val showDescription: Boolean = true,
)

private class ContentRowFocusMemory {
    var hasFocus = false
    var lastFocusedKey: String? = null
}

@Composable
private fun ContentRowHeader(title: String, sectionKey: String, focused: androidx.compose.runtime.State<Boolean>) {
    ContentSectionHeader(
        title = title,
        sectionKey = sectionKey,
        focused = focused.value,
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ContentRow(
    row: ContentRowData,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    onItemFocused: (MediaItem) -> Unit = {},
    onRowFocused: () -> Unit = {},
    restoreRatingKey: String? = null,
    columnFocus: RowColumnFocus? = null,
    /** Works for touch/wheel scrolling as well as remote focus, without extra row controls. */
    onViewportNearEnd: ((Int) -> Unit)? = null,
) {
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    // A row recycled out of the list comes back scrolled to the tile it was left on.
    val restoreIndex = remember(row.items, restoreRatingKey) {
        val key = restoreRatingKey ?: columnFocus?.rememberedItem(row.id)
        row.items.indexOfFirst { it.ratingKey == key }.coerceAtLeast(0)
    }
    val rowState = rememberBrowseRowState(
        initialFirstVisibleItemIndex = (restoreIndex - 1).coerceAtLeast(0),
    )
    BrowseScrollBudget(rowState)
    if (onViewportNearEnd != null) {
        LaunchedEffect(rowState, row.id, row.items.size) {
            snapshotFlow { rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .distinctUntilChanged()
                .collect { last ->
                    if (last >= 0 && last >= row.items.size - 8) onViewportNearEnd(last)
                }
        }
    }
    RegisterRowColumnFocus(columnFocus, row.id, rowState, remember(row.items) { row.items.map { it.ratingKey } })
    // Focus changes are extremely frequent while moving vertically. Keep them out of the
    // ContentRow composition so the nested LazyRow and its visible MediaTiles are not recomposed
    // just to animate the section heading.
    val focusMemory = remember(row.id) { ContentRowFocusMemory() }
    val headerFocused = remember(row.id) { mutableStateOf(false) }
    val recoveryKey = remember(row.items) {
        val keys = row.items.map { it.ratingKey }
        if (focusMemory.hasFocus && focusMemory.lastFocusedKey != null &&
            focusMemory.lastFocusedKey !in keys
        ) keys.firstOrNull() else null
    }
    androidx.compose.runtime.LaunchedEffect(recoveryKey) {
        val index = row.items.indexOfFirst { it.ratingKey == recoveryKey }
        if (index >= 0 && rowState.layoutInfo.visibleItemsInfo.none { it.index == index }) rowState.scrollToItem(index)
    }

    val horizontalScrollSpec = if (handheld) LocalBringIntoViewSpec.current else TvBringIntoViewSpec
    val hasEpisodes = remember(row.items) { row.items.any { it.type == "episode" } }

    // Each MediaTile brings only itself into view on focus, which is enough while moving
    // within a row but not when focus first arrives in one from a row above/below: the target
    // tile could already be fully visible while this row's own title is still scrolled out of
    // view above it, since the title lives outside what the tile's own request accounts for.
    // Requesting the whole row (title + tiles) into view whenever focus arrives in it fixes
    // that; the tile's own request still applies for scrolling sideways within the row.
    Column(
        modifier = modifier
            .fillMaxWidth()
            // YouTube's grouped-row rhythm leaves 16 dp from one card surface to the next
            // heading: 8 dp below MediaTile's surface plus 8 dp above the following row.
            .padding(top = 8.dp)
            .onFocusChanged { focus ->
                val hadFocus = focusMemory.hasFocus
                focusMemory.hasFocus = focus.hasFocus
                if (focus.hasFocus && !hadFocus) onRowFocused()
                headerFocused.value = focus.hasFocus
            }
            .clipToBounds(),
    ) {
        ContentRowHeader(row.title, row.id, headerFocused)
        BrowseFocusRow(row.id) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalScrollSpec) {
                LazyRow(
                    state = rowState,
                    contentPadding = PaddingValues(horizontal = screenGutter()),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.containRowFocusExit(),
                ) {
                    items(row.items, key = { it.ratingKey }, contentType = {
                        resolveMediaTileStyle(row.style, it.type, handheld)
                    }) { item ->
                        MediaTile(
                            item = item,
                            onClick = { onItemClick(item) },
                            modifier = Modifier.rowColumnFocusTarget(columnFocus, row.id, item.ratingKey, soundsOnFocus = true),
                            onFocused = { focusMemory.lastFocusedKey = item.ratingKey; onItemFocused(item) },
                            requestInitialFocus = item.ratingKey == restoreRatingKey || item.ratingKey == recoveryKey,
                            style = row.style,
                            showDescription = row.showDescription,
                            reserveEpisodeLines = hasEpisodes,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SkeletonRow(title: String, modifier: Modifier = Modifier, style: MediaTileStyle = MediaTileStyle.POSTER, itemCount: Int = 6) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = screenGutter(), bottom = 10.dp),
            )
        } else {
            // Keep the loading layout spatially identical to a real content row. A blank
            // Text node collapses completely and makes the first loaded frame visibly jump.
            Box(
                Modifier
                    .padding(start = screenGutter(), bottom = 12.dp)
                    .width(168.dp)
                    .height(18.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f), RoundedCornerShape(999.dp)),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = screenGutter()),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(itemCount) {
                SkeletonTile(style = style)
            }
        }
    }
}
