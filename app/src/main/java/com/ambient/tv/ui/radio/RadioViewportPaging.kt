package com.ambient.tv.ui.radio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** End placeholders and section headers are not stations and must never drive paging. */
internal fun radioLastVisibleStationIndex(visibleIndices: List<Int>, stationCount: Int): Int =
    visibleIndices.lastOrNull { it in 0 until stationCount } ?: -1

/**
 * D-pad focus already pages Radio Browser. This observes the same visible item tail on
 * handheld and TV for touch, wheel, scrollbar drag and programmatic scrolling.
 * A fetch is only requested if actual station cards (not pagination placeholders) are visible.
 */
@Composable
internal fun ObserveRadioViewportPaging(
    scope: RadioFocusScope,
    mode: RadioViewMode,
    gridState: LazyGridState,
    listState: LazyListState,
    stationCount: Int,
    hasMore: Boolean,
    loading: Boolean,
    loadingMore: Boolean,
    enabled: Boolean,
    onNearEnd: (Int) -> Unit,
) {
    val request by rememberUpdatedState(onNearEnd)
    LaunchedEffect(scope, mode, stationCount, hasMore, loading, loadingMore, enabled, gridState, listState) {
        if (!enabled || stationCount == 0 || !hasMore || loading || loadingMore) return@LaunchedEffect
        snapshotFlow {
            val visible = if (mode == RadioViewMode.GRID) {
                gridState.layoutInfo.visibleItemsInfo.map { it.index }
            } else {
                listState.layoutInfo.visibleItemsInfo.map { it.index }
            }
            radioLastVisibleStationIndex(visible, stationCount)
        }.distinctUntilChanged().collectLatest { last ->
            if (radioShouldLoadOnFocus(last, stationCount, hasMore, loading, loadingMore)) {
                // A fast wheel/fling must settle before the continuation alters layout.
                delay(160L)
                request(last)
            }
        }
    }
}

/** A Lazy item at the actual end is a second paging signal when no item receives TV focus.
 * It also works if a touch scroll or a scrollbar does not move the focused card. */
internal fun radioPagingTailReady(hasMore: Boolean, loading: Boolean, loadingMore: Boolean): Boolean =
    hasMore && !loading && !loadingMore

@Composable
internal fun RadioPagingTail(
    scope: RadioFocusScope,
    nextOffset: Int,
    hasMore: Boolean,
    loading: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    val request by rememberUpdatedState(onLoadMore)
    val ready = radioPagingTailReady(hasMore, loading, loadingMore)
    LaunchedEffect(scope, nextOffset, ready) {
        if (ready) {
            // The tail can be composed just ahead of the viewport. Allow a fast fling
            // to settle, then let the ViewModel atomically claim the next page.
            delay(180L)
            request()
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp))
}
