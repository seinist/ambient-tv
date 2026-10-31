package com.ambient.tv.ui.youtube

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshotFlow
import com.ambient.tv.youtube.YouTubeRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** Source row, last visible item index, and whether it is a grid. */
internal val LocalYouTubeViewportContinuation =
    staticCompositionLocalOf<((String?, Int, Boolean) -> Unit)?> { null }

/** Preserve each video tile's originating row even in a combined flat feed. */
internal val LocalYouTubeFlatViewportKeys =
    staticCompositionLocalOf<Map<String, Pair<String, Int>>?> { null }

/** First occurrence wins: flat YouTube grids deduplicate videos shared by several rows. */
internal fun youtubeFlatViewportItemKeys(rows: List<YouTubeRow>): Map<String, Pair<String, Int>> =
    buildMap {
        rows.forEach { row ->
            row.items.forEachIndexed { index, item ->
                putIfAbsent(item.videoId, row.id to index)
            }
        }
    }

/** If the last visible slot is a section header, use the last actual video card before it. */
internal fun youtubeLastVisibleSourceItem(
    visibleKeys: List<String>,
    sourceIndices: Map<String, Pair<String, Int>>,
): Pair<String, Int>? = visibleKeys.asReversed().firstNotNullOfOrNull(sourceIndices::get)

/** Translate mixed headers/cards into source-relative positions for sectioned list and wall. */
internal fun youtubeViewportItemKeys(rows: List<YouTubeRow>, prefix: String): Map<String, Pair<String, Int>> =
    buildMap {
        rows.forEach { row ->
            row.items.forEachIndexed { index, item ->
                put("$prefix:${row.id}:${item.videoId}", row.id to index)
            }
        }
    }

@Composable
internal fun ObserveYouTubeListViewport(
    rowId: String?,
    state: LazyListState,
    itemKeys: Map<String, Pair<String, Int>>? = null,
) {
    val request = LocalYouTubeViewportContinuation.current
    val keys = itemKeys ?: if (rowId == null) LocalYouTubeFlatViewportKeys.current else null
    if (request == null) return
    LaunchedEffect(state, rowId, keys, request) {
        snapshotFlow {
            if (keys == null) {
                state.layoutInfo.visibleItemsInfo.lastOrNull()?.let { rowId to it.index }
            } else {
                youtubeLastVisibleSourceItem(
                    state.layoutInfo.visibleItemsInfo.map { it.key.toString() }, keys,
                )
            }
        }.distinctUntilChanged().collectLatest { target ->
            if (target != null) {
                delay(160L) // Do not issue pages mid-fling or during remote scroll animations.
                request(target.first, target.second, false)
            }
        }
    }
}

@Composable
internal fun ObserveYouTubeGridViewport(
    rowId: String?,
    state: LazyGridState,
    itemKeys: Map<String, Pair<String, Int>>? = null,
) {
    val request = LocalYouTubeViewportContinuation.current
    val keys = itemKeys ?: if (rowId == null) LocalYouTubeFlatViewportKeys.current else null
    if (request == null) return
    LaunchedEffect(state, rowId, keys, request) {
        snapshotFlow {
            if (keys == null) {
                state.layoutInfo.visibleItemsInfo.lastOrNull()?.let { rowId to it.index }
            } else {
                youtubeLastVisibleSourceItem(
                    state.layoutInfo.visibleItemsInfo.map { it.key.toString() }, keys,
                )
            }
        }.distinctUntilChanged().collectLatest { target ->
            if (target != null) {
                delay(160L)
                request(target.first, target.second, true)
            }
        }
    }
}
