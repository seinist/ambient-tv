package com.ambient.tv.ui.youtube

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshotFlow
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.youtube.YouTubeRow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Source row, last visible item index, and whether it is a grid. */
internal val LocalYouTubeViewportContinuation =
    staticCompositionLocalOf<((String?, Int, Boolean) -> Unit)?> { null }

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
    if (!LocalHandheldUi.current || request == null) return
    LaunchedEffect(state, rowId, itemKeys, request) {
        snapshotFlow {
            state.layoutInfo.visibleItemsInfo.lastOrNull()?.let { item ->
                if (itemKeys == null) rowId to item.index else itemKeys[item.key.toString()]
            }
        }.distinctUntilChanged().collect { target ->
            if (target != null) request(target.first, target.second, false)
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
    if (!LocalHandheldUi.current || request == null) return
    LaunchedEffect(state, rowId, itemKeys, request) {
        snapshotFlow {
            state.layoutInfo.visibleItemsInfo.lastOrNull()?.let { item ->
                if (itemKeys == null) rowId to item.index else itemKeys[item.key.toString()]
            }
        }.distinctUntilChanged().collect { target ->
            if (target != null) request(target.first, target.second, true)
        }
    }
}
