package com.ambient.tv.ui.youtube

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.ambient.tv.ui.common.BrowseFocusKey
import com.ambient.tv.ui.common.LocalBrowseFocusReturnState

/** A leading insertion is visible at the start; deeper views retain Compose's keyed anchor. */
internal fun youtubeRevealLeadingInsert(
    previousFirst: Any?,
    keys: List<Any>,
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    scrolling: Boolean,
    focusedIndex: Int? = null,
    lastFullyVisibleIndex: Int = Int.MAX_VALUE,
): Boolean = previousFirst != null && keys.isNotEmpty() && previousFirst != keys.first() &&
    previousFirst in keys && firstVisibleIndex == 0 && firstVisibleOffset == 0 && !scrolling &&
    (focusedIndex == null || focusedIndex in 0..lastFullyVisibleIndex)

private class YouTubeInsertHead(var key: Any?)

@Composable
internal fun KeepYouTubeLeadingInsertVisible(
    state: LazyListState,
    keys: List<Any>,
    focusIndex: (BrowseFocusKey) -> Int = { keys.indexOf(it.item) },
) {
    val previous = remember(state) { YouTubeInsertHead(keys.firstOrNull()) }
    val focus = LocalBrowseFocusReturnState.current
    SideEffect {
        val lastVisible = state.layoutInfo.visibleItemsInfo.lastOrNull {
            it.offset + it.size <= state.layoutInfo.viewportEndOffset
        }?.index ?: 0
        if (youtubeRevealLeadingInsert(previous.key, keys, state.firstVisibleItemIndex,
                state.firstVisibleItemScrollOffset, state.isScrollInProgress,
                focus?.focusedContentKey?.let(focusIndex), lastVisible)) {
            // Apply with the new data in its first measure, rather than visibly scrolling later.
            // Large batches retain the selected card's anchor instead of pushing it offscreen
            // and immediately fighting Compose's focus-driven bring-into-view scroll.
            state.requestScrollToItem(0)
        }
        previous.key = keys.firstOrNull()
    }
}

@Composable
internal fun KeepYouTubeLeadingInsertVisible(
    state: LazyGridState,
    keys: List<Any>,
    focusIndex: (BrowseFocusKey) -> Int = { keys.indexOf(it.item) },
) {
    val previous = remember(state) { YouTubeInsertHead(keys.firstOrNull()) }
    val focus = LocalBrowseFocusReturnState.current
    SideEffect {
        val lastVisible = state.layoutInfo.visibleItemsInfo.lastOrNull {
            it.offset.y + it.size.height <= state.layoutInfo.viewportEndOffset
        }?.index ?: 0
        if (youtubeRevealLeadingInsert(previous.key, keys, state.firstVisibleItemIndex,
                state.firstVisibleItemScrollOffset, state.isScrollInProgress,
                focus?.focusedContentKey?.let(focusIndex), lastVisible)) {
            state.requestScrollToItem(0)
        }
        previous.key = keys.firstOrNull()
    }
}

internal fun youtubeSectionMotionKeys(rows: List<com.ambient.tv.youtube.YouTubeRow>, prefix: String): List<String> =
    rows.flatMap { row -> listOf("$prefix-header:${row.id}") + row.items.map { "$prefix:${row.id}:${it.videoId}" } }
