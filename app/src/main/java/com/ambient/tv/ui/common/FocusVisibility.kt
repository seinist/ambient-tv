package com.ambient.tv.ui.common

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyLayoutScrollScope
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * The one glide every focus-driven scroll uses, so D-pad moves between rows feel identical in
 * the library grid, collections, lists, Home and settings. A critically damped, fairly soft
 * spring gives each row change a gentler acceleration and deceleration, without overshoot.
 */
val TvScrollAnimationSpec: AnimationSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 180f)

/**
 * App-wide relocation policy for TV focus (provided in the theme).
 *
 * The stock spec scrolls only once the focused item touches the viewport edge, so each row
 * change is a late, abrupt nudge with nothing of the next row in sight. This keeps a comfort
 * zone of [COMFORT_FRACTION] of the viewport on both ends: the list starts gliding one step
 * earlier and the next row/tile always peeks in. Items too large for the zone fall back to
 * being centered, then to the stock minimal-scroll behaviour.
 */
@OptIn(ExperimentalFoundationApi::class)
object TvBringIntoViewSpec : BringIntoViewSpec {
    private const val COMFORT_FRACTION = 0.14f

    @Suppress("OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float> = TvScrollAnimationSpec

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = min(containerSize * COMFORT_FRACTION, ((containerSize - size) / 2f).coerceAtLeast(0f))
        val leading = offset - margin
        val trailing = offset + size - (containerSize - margin)
        return when {
            leading >= 0f && trailing <= 0f -> 0f
            leading < 0f && trailing > 0f -> 0f
            abs(leading) < abs(trailing) -> leading
            else -> trailing
        }
    }
}

/** Only for vertical lists that explicitly center complete rows when focus enters them.
 * Nested horizontal rows must provide [TvBringIntoViewSpec] so sideways relocation still works. */
@OptIn(ExperimentalFoundationApi::class)
internal object ExplicitRowScrollSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/**
 * Use the TV glide for explicit lazy-list destinations too. Recalculate the remaining distance
 * as offscreen items are measured, inside one animation rather than a second corrective glide.
 * [scrollOffset] follows LazyListState.scrollToItem's sign convention.
 */
@OptIn(ExperimentalFoundationApi::class)
suspend fun LazyListState.animateTvScrollToItem(index: Int, scrollOffset: Int = 0) {
    animateTvScrollToItem(index) { scrollOffset }
}

@OptIn(ExperimentalFoundationApi::class)
private suspend fun LazyListState.animateTvScrollToItem(index: Int, scrollOffset: () -> Int) {
    if (index !in 0 until layoutInfo.totalItemsCount) return
    scroll {
        val layout = LazyLayoutScrollScope(this@animateTvScrollToItem, this)
        var previousProgress = 0f
        // Keep the spring's stopping threshold at pixel scale, not a percentage of the trip.
        val progressRange = layoutInfo.viewportSize.height.toFloat().coerceAtLeast(1f)
        animate(0f, progressRange, animationSpec = TvScrollAnimationSpec) { value, _ ->
            val progress = (value / progressRange).coerceIn(previousProgress, 1f)
            if (index in 0 until layout.itemCount && previousProgress < 1f) {
                val remaining = layout.calculateDistanceTo(index, scrollOffset()).toFloat()
                val fraction = (progress - previousProgress) / (1f - previousProgress)
                layout.scrollBy(remaining * fraction)
            }
            previousProgress = progress
        }
    }
}

/** Remember one instance per vertical list. Cancellation on a new row preserves velocity;
 * completing a move or hitting a list boundary clears it. No state is read by composition. */
@Composable
fun rememberRowCenteringState(listState: LazyListState): RowCenteringState =
    remember(listState) { RowCenteringState(listState) }

class RowCenteringState internal constructor(private val listState: LazyListState) {
    private var velocity = 0f
    private var lastFrameTimeNanos = Long.MIN_VALUE

    @OptIn(ExperimentalFoundationApi::class)
    suspend fun centerItem(index: Int, topInset: Int = 0, alignTop: Boolean = false, parkInset: Int = 0) {
        if (index !in 0 until listState.layoutInfo.totalItemsCount) return
        listState.scroll {
            // Continuing only velocity still inserts a zero-progress first frame at every
            // retarget. Carry the frame clock too, unless the preceding move is stale.
            if (lastFrameTimeNanos != Long.MIN_VALUE &&
                System.nanoTime() - lastFrameTimeNanos > 250_000_000L
            ) {
                velocity = 0f
                lastFrameTimeNanos = Long.MIN_VALUE
            }
            val layout = LazyLayoutScrollScope(listState, this)
            fun remainingDistance(): Float {
                // calculateDistanceTo adds its offset to the item's viewport position, so zero
                // parks the row on the top edge. Home passes [parkInset] to hold a row below the
                // hero, on the same line as the first row. Other screens leave it at zero.
                if (alignTop) return layout.calculateDistanceTo(index, -parkInset).toFloat()
                val info = listState.layoutInfo
                val itemSize = info.visibleItemsInfo.firstOrNull { it.index == index }?.size
                    ?: info.visibleItemsInfo.firstOrNull()?.size ?: return 0f
                val center = (info.viewportStartOffset + topInset + info.viewportEndOffset) / 2
                return layout.calculateDistanceTo(index, -(center - itemSize / 2)).toFloat()
            }
            var remaining = remainingDistance()
            while (abs(remaining) > 1f) {
                val animation = AnimationState(
                    initialValue = 0f,
                    initialVelocity = velocity,
                    lastFrameTimeNanos = lastFrameTimeNanos,
                )
                val destination = remaining
                var previous = 0f
                var boundary = false
                animation.animateTo(destination, animationSpec = TvScrollAnimationSpec, sequentialAnimation = true) {
                    val delta = value - previous
                    val consumed = scrollBy(delta)
                    previous = value
                    this@RowCenteringState.velocity = this.velocity
                    this@RowCenteringState.lastFrameTimeNanos = this.lastFrameTimeNanos
                    // Do not retain momentum against the beginning/end of the list.
                    if (abs(delta - consumed) > 0.5f) {
                        boundary = true
                        cancelAnimation()
                    } else if (abs(value + remainingDistance() - destination) > 2f) {
                        // An offscreen row now has its real height. Retarget without a snap
                        // or a second zero-velocity correction after the animation finishes.
                        cancelAnimation()
                    }
                }
                if (boundary) break
                remaining = remainingDistance()
            }
            velocity = 0f
            lastFrameTimeNanos = Long.MIN_VALUE
        }
    }
}

/** One-shot centering for callers without an ongoing focus stream. */
suspend fun LazyListState.centerItemInViewport(index: Int, topInset: Int = 0) =
    RowCenteringState(this).centerItem(index, topInset)

/**
 * Keeps the complete semantic block around a focused TV control visible.
 *
 * Important: this deliberately runs only when focus ENTERS the block. A parent that stays an
 * ActiveParent while focus moves between children must not issue a second relocation for every
 * child focus change; doing that at the same time as a LazyRow/MediaTile relocation creates the
 * visible "twitch"/bounce that used to be most obvious in Continue Watching / Recently Added.
 */
@Composable
fun Modifier.bringFocusedContextIntoView(skipFirstFocus: Boolean = false): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    var hadFocus by remember { mutableStateOf(false) }
    var skipNextFocus by remember(skipFirstFocus) { mutableStateOf(skipFirstFocus) }
    var relocationJob by remember { mutableStateOf<Job?>(null) }

    return this
        .bringIntoViewRequester(requester)
        .onFocusChanged { state ->
            val entered = state.hasFocus && !hadFocus
            hadFocus = state.hasFocus
            if (entered) {
                if (skipNextFocus) {
                    skipNextFocus = false
                    return@onFocusChanged
                }
                relocationJob?.cancel()
                relocationJob = scope.launch {
                    // Let the child's own LazyRow/LazyColumn relocation settle first, then make
                    // one contextual correction for the heading/row container.
                    withFrameNanos { }
                    withFrameNanos { }
                    requester.bringIntoView()
                }
            } else if (!state.hasFocus) {
                relocationJob?.cancel()
                relocationJob = null
            }
        }
}
