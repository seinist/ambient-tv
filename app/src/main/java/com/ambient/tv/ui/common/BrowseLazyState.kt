package com.ambient.tv.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.performance.LocalPerformanceProfile

/** A bounded viewport window prepares new cards before D-pad focus reaches the visible edge.
 * Keeping a smaller window behind the viewport also makes quick reversals reuse their layouts.
 * The amount scales with the actual viewport, not library size or the number of rows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberBrowseCacheWindow(): LazyLayoutCacheWindow {
    val reduced = LocalPerformanceProfile.current.reducedEffects
    return remember(reduced) {
        LazyLayoutCacheWindow(
            aheadFraction = if (reduced) 0.25f else 0.75f,
            behindFraction = if (reduced) 0.25f else 0.5f,
        )
    }
}

/**
 * Window for horizontal rows nested in a vertical browse list: about one tile ahead
 * (poster 160 dp + gap, or part of a 300 dp landscape tile) and a little behind. The viewport
 * fractions of [rememberBrowseCacheWindow] meant ~0.75 screen widths of extra tiles per row,
 * all composed in the frame a row slides in during the vertical glide.
 */
internal val BrowseRowCacheAhead = 200.dp
internal val BrowseRowCacheBehind = 100.dp

/** [rememberBrowseListState] for a horizontal row inside a browse column; see [BrowseRowCacheAhead]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberBrowseRowState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
): LazyListState = (if (LocalHandheldUi.current) {
    rememberLazyListState(initialFirstVisibleItemIndex, initialFirstVisibleItemScrollOffset)
} else {
    rememberLazyListState(
        remember { LazyLayoutCacheWindow(ahead = BrowseRowCacheAhead, behind = BrowseRowCacheBehind) },
        initialFirstVisibleItemIndex, initialFirstVisibleItemScrollOffset)
}).also { state -> ScrollToTopOnReselect { state.scrollToItem(0) } }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberBrowseListState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
): LazyListState = (if (LocalHandheldUi.current) {
    rememberLazyListState(initialFirstVisibleItemIndex, initialFirstVisibleItemScrollOffset)
} else {
    rememberLazyListState(rememberBrowseCacheWindow(), initialFirstVisibleItemIndex, initialFirstVisibleItemScrollOffset)
}).also { state -> ScrollToTopOnReselect { state.scrollToItem(0) } }

/**
 * Selecting the menu entry of the page that is already open scrolls its lists back to the top.
 * Only the current destination is composed, so only its lists react.
 */
object NavReselect {
    val signal = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Set until focus next enters the page, which then starts at its top. */
    val restoreTop = androidx.compose.runtime.mutableStateOf(false)
    fun fire() {
        restoreTop.value = true
        signal.value = signal.value + 1
    }
}

@Composable
private fun ScrollToTopOnReselect(scroll: suspend () -> Unit) {
    val start = androidx.compose.runtime.remember { NavReselect.signal.value }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        NavReselect.signal.collect { if (it != start) runCatching { scroll() } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberBrowseGridState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
): LazyGridState = (if (LocalHandheldUi.current) {
    rememberLazyGridState(initialFirstVisibleItemIndex, initialFirstVisibleItemScrollOffset)
} else {
    rememberLazyGridState(rememberBrowseCacheWindow(), initialFirstVisibleItemIndex, initialFirstVisibleItemScrollOffset)
}).also { state -> ScrollToTopOnReselect { state.scrollToItem(0) } }
