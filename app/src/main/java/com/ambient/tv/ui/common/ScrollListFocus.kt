package com.ambient.tv.ui.common

import androidx.compose.foundation.gestures.animateScrollBy
import android.view.View
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.performance.LocalPerformanceProfile

/** Modal bodies point directly to their own footer; never to the page behind the modal. */
internal val LocalScrollListActionFocus = staticCompositionLocalOf<FocusRequester?> { null }
internal val LocalScrollListActions = staticCompositionLocalOf<ScrollListActions?> { null }

/** Headers register for their lifetime and window. Disposing a page removes its focus target. */
internal class ScrollListActions {
    private class Target(val view: View, val actions: FocusRequester, val up: (() -> FocusRequester)?)
    private val targets = linkedMapOf<Any, Target>()
    fun register(owner: Any, view: View, requester: FocusRequester, up: (() -> FocusRequester)? = null) { targets[owner] = Target(view, requester, up) }
    fun unregister(owner: Any) { targets.remove(owner) }
    fun requestFocus(view: View): Boolean = request(view) { it.actions }
    /** Up out of the top of a page list: the page's next stage up (filter, chips), else its actions. */
    fun requestUpFocus(view: View): Boolean = request(view) { it.up?.invoke() } || requestFocus(view)
    private fun request(view: View, pick: (Target) -> FocusRequester?): Boolean = targets.values.toList().asReversed().any { target ->
        target.view === view && pick(target)?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true
    }
}

@Composable
internal fun RegisterScrollListActions(requester: FocusRequester, up: (() -> FocusRequester)? = null) {
    val actions = LocalScrollListActions.current
    val view = LocalView.current
    val owner = remember { Any() }
    val handheld = LocalHandheldUi.current
    DisposableEffect(actions, view, requester, up, handheld) {
        if (!handheld) actions?.register(owner, view, requester, up)
        onDispose { actions?.unregister(owner) }
    }
}

/** Scroll clipping uses layout bounds, while focus scale draws beyond them. Keep space
 * INSIDE both endpoints, including lists whose callers specify only bottom padding.
 * Larger page gutters and horizontal/RTL padding are preserved. */
@Composable
internal fun PaddingValues.withTvFocusInsets(): PaddingValues {
    if (LocalHandheldUi.current) return this
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        top = maxOf(calculateTopPadding(), 8.dp),
        end = calculateEndPadding(direction),
        bottom = maxOf(calculateBottomPadding(), 8.dp),
    )
}

/**
 * Shared TV list shell. Page content opts into [allowUpExit] to reach its header before wrapping.
 * Modal/settings lists keep local wrapping. Touch layout and scrolling remain Compose defaults.
 * Detail pages disable [wrapVerticalFocus] to stop at both vertical endpoints.
 */
@Composable
fun TvLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    allowUpExit: Boolean = false,
    wrapVerticalFocus: Boolean = true,
    focusActionsOnRight: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    BrowseScrollBudget(state)
    val animate = !LocalPerformanceProfile.current.reducedEffects
    LazyScrollbarFrame(modifier, state.canScrollForward || state.canScrollBackward,
        scrollbar = { bar, contentFocus -> OverlayLazyScrollbar(state, bar, contentFocus) }) { listModifier ->
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = listModifier.then(if (LocalHandheldUi.current || !userScrollEnabled) Modifier else Modifier.tvModalFocus(
                initialFocus = false,
                allowUpExit = allowUpExit,
                wrapVerticalFocus = wrapVerticalFocus,
                scrollToTop = {
                    if (state.layoutInfo.totalItemsCount > 0) state.glideToItem(animate, if (reverseLayout) state.layoutInfo.totalItemsCount - 1 else 0)
                },
                scrollToEnd = {
                    if (state.layoutInfo.totalItemsCount > 0) state.glideToItem(animate, if (reverseLayout) 0 else state.layoutInfo.totalItemsCount - 1)
                },
                focusActionsOnRight = focusActionsOnRight,
                reverseTraversal = reverseLayout,
                scrollPastEmptyEdge = { state.scrollPastUnfocusableEdge(it != reverseLayout) },
            )),
            state = state, contentPadding = contentPadding.withTvFocusInsets(), reverseLayout = reverseLayout,
            verticalArrangement = verticalArrangement, horizontalAlignment = horizontalAlignment,
            flingBehavior = flingBehavior, userScrollEnabled = userScrollEnabled, content = content,
        )
    }
}

/** Page grids reach controls above before wrapping; Left/Right still traverse tiles. */
@Composable
fun TvLazyVerticalGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    content: LazyGridScope.() -> Unit,
) {
    BrowseScrollBudget(state)
    val animate = !LocalPerformanceProfile.current.reducedEffects
    LazyScrollbarFrame(modifier, state.canScrollForward || state.canScrollBackward,
        scrollbar = { bar, contentFocus -> OverlayLazyGridScrollbar(state, bar, contentFocus) }) { listModifier ->
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = columns,
            modifier = listModifier.then(if (LocalHandheldUi.current || !userScrollEnabled) Modifier else Modifier.tvModalFocus(
                initialFocus = false,
                allowUpExit = true,
                scrollToTop = {
                    if (state.layoutInfo.totalItemsCount > 0) state.glideToItem(animate, if (reverseLayout) state.layoutInfo.totalItemsCount - 1 else 0)
                },
                scrollToEnd = {
                    if (state.layoutInfo.totalItemsCount > 0) state.glideToItem(animate, if (reverseLayout) 0 else state.layoutInfo.totalItemsCount - 1)
                },
                focusActionsOnRight = true,
                reverseTraversal = reverseLayout,
                scrollPastEmptyEdge = { state.scrollPastUnfocusableEdge(it != reverseLayout) },
            )),
            state = state, contentPadding = contentPadding.withTvFocusInsets(), reverseLayout = reverseLayout,
            verticalArrangement = verticalArrangement, horizontalArrangement = horizontalArrangement,
            flingBehavior = flingBehavior, userScrollEnabled = userScrollEnabled, content = content,
        )
    }
}

/**
 * Wrap jumps (bottom → top and back): one continuous eased slide over the whole distance with a
 * fixed duration, so the list visibly travels back instead of cutting. The pixel distance is
 * estimated from the visible items' average size; a final [animateScrollToItem] corrects it.
 */
private const val GLIDE_MS = 520
private const val GLIDE_ROWS = 3

internal suspend fun LazyListState.glideToItem(animate: Boolean, index: Int) {
    if (!animate) return scrollToItem(index)
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isNotEmpty()) {
        val spacing = layoutInfo.mainAxisItemSpacing
        val average = visible.sumOf { it.size + spacing }.toFloat() / visible.size
        val distance = (index - firstVisibleItemIndex) * average - firstVisibleItemScrollOffset
        if (distance != 0f) {
            animateScrollBy(distance, androidx.compose.animation.core.tween(GLIDE_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    animateScrollToItem(index)
}

internal suspend fun LazyGridState.glideToItem(animate: Boolean, index: Int) {
    if (!animate) return scrollToItem(index)
    val current = firstVisibleItemIndex
    val span = (layoutInfo.maxSpan.coerceAtLeast(1)) * GLIDE_ROWS
    if (kotlin.math.abs(current - index) > span) scrollToItem(if (index < current) index + span else (index - span).coerceAtLeast(0))
    animateScrollToItem(index)
}

internal suspend fun LazyListState.scrollPastUnfocusableEdge(sourceEnd: Boolean): Boolean {
    val visible = layoutInfo.visibleItemsInfo
    val next = if (sourceEnd) (visible.minOfOrNull { it.index } ?: 0) - 1
        else (visible.maxOfOrNull { it.index } ?: layoutInfo.totalItemsCount) + 1
    if (next !in 0 until layoutInfo.totalItemsCount) return false
    scrollToItem(next)
    return true
}

internal suspend fun LazyGridState.scrollPastUnfocusableEdge(sourceEnd: Boolean): Boolean {
    val visible = layoutInfo.visibleItemsInfo
    val next = if (sourceEnd) (visible.minOfOrNull { it.index } ?: 0) - 1
        else (visible.maxOfOrNull { it.index } ?: layoutInfo.totalItemsCount) + 1
    if (next !in 0 until layoutInfo.totalItemsCount) return false
    scrollToItem(next)
    return true
}

fun Modifier.wrapVerticalScrollFocus(
    state: ScrollState,
    actionsFocus: FocusRequester? = null,
    focusActionsOnRight: Boolean = false,
    wrapOnRepeat: Boolean = true,
): Modifier = composed {
    if (LocalHandheldUi.current) Modifier else Modifier.tvModalFocus(
        initialFocus = false,
        scrollToTop = { state.scrollTo(0) },
        scrollToEnd = { state.scrollTo(state.maxValue) },
        footerFocus = actionsFocus,
        focusActionsOnRight = focusActionsOnRight,
        wrapOnRepeat = wrapOnRepeat,
    )
}
