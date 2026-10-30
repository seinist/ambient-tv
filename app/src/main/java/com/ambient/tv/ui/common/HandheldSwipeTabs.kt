package com.ambient.tv.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
 import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.mobile.LocalHandheldUi
import kotlin.math.abs

private const val HORIZONTAL_DOMINANCE = 1.25f

/**
 * Passive phone-only tab swipe.
 *
 * Observe raw gesture displacement before a child consumes its movement. A vertically scrolling
 * grid can otherwise swallow an entirely horizontal page swipe. Real horizontal LazyRows/chip
 * strips retain their own gestures; this detector never consumes pointer changes.
 */
fun <T> Modifier.handheldSwipeTabs(
    tabs: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
    threshold: Dp = 60.dp,
): Modifier = composed {
    val handheld = LocalHandheldUi.current
    val layoutDirection = LocalLayoutDirection.current
    val thresholdPx = with(LocalDensity.current) { threshold.toPx() }
    val latestSelect by rememberUpdatedState(onSelect)

    if (!handheld || !enabled || tabs.size < 2) {
        this
    } else {
        pointerInput(tabs, selected, thresholdPx, layoutDirection) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )
                var horizontal = 0f
                var vertical = 0f
                var consumedHorizontal = 0f
                var consumedByChild = false
                val touchSlop = viewConfiguration.touchSlop

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    // Pinch/zoom or any other multi-touch gesture is never a tab swipe.
                    if (event.changes.count { it.pressed } > 1) consumedByChild = true
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        consumedByChild = true
                        break
                    }
                    // Capture raw movement before the child's Main pass.
                    val delta = change.position - change.previousPosition
                    horizontal += delta.x
                    vertical += delta.y
                    val final = awaitPointerEvent(PointerEventPass.Final)
                    if (final.changes.firstOrNull { it.id == down.id }?.isConsumed == true) {
                        consumedHorizontal += abs(delta.x)
                        if (childConsumesHorizontalSwipe(consumedHorizontal, horizontal, vertical, touchSlop)) {
                            consumedByChild = true
                        }
                    }
                    if (!change.pressed) break
                }

                if (!consumedByChild) {
                    val selectedIndex = tabs.indexOf(selected)
                    val target = swipeTabTargetIndex(
                        selectedIndex = selectedIndex,
                        tabCount = tabs.size,
                        horizontalDistance = horizontal,
                        verticalDistance = vertical,
                        threshold = thresholdPx,
                        rtl = layoutDirection == LayoutDirection.Rtl,
                    )
                    if (target != null) latestSelect(tabs[target])
                }
            }
        }
    }
}

internal fun swipeTabTargetIndex(
    selectedIndex: Int,
    tabCount: Int,
    horizontalDistance: Float,
    verticalDistance: Float,
    threshold: Float,
    rtl: Boolean,
): Int? {
    if (tabCount < 2 || selectedIndex !in 0 until tabCount || threshold <= 0f) return null
    val horizontal = abs(horizontalDistance)
    if (horizontal < threshold || horizontal <= abs(verticalDistance) * HORIZONTAL_DOMINANCE) return null

    val logicalDistance = if (rtl) -horizontalDistance else horizontalDistance
    val step = if (logicalDistance < 0f) 1 else -1
    return (selectedIndex + step).takeIf { it in 0 until tabCount }
}

/** Let an inner horizontal scroller handle its drag rather than switching provider pages. */
internal fun childConsumesHorizontalSwipe(
    consumedDx: Float,
    totalDx: Float,
    totalDy: Float,
    touchSlop: Float,
): Boolean = touchSlop > 0f && consumedDx >= touchSlop &&
    abs(totalDx) > abs(totalDy) * HORIZONTAL_DOMINANCE
