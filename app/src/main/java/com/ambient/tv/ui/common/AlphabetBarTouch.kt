package com.ambient.tv.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.abs
import kotlin.math.roundToInt

/** The full index remains touchable even when a short rail shows some letters as dots. */
internal fun alphabetTouchIndex(y: Float, height: Int, count: Int): Int? {
    if (count <= 0 || height <= 0 || !y.isFinite()) return null
    return ((y / height).coerceIn(0f, 1f) * count).toInt().coerceAtMost(count - 1)
}

/** Leave enough space for the current letter and the last label in a compressed rail. */
internal fun alphabetRailLabelVisible(index: Int, count: Int, step: Int, activeIndex: Int): Boolean {
    if (step <= 1) return true
    if (activeIndex >= 0 && abs(index - activeIndex) < step) return false
    if (index == 0 || index == count - 1) return true
    return index % step == 0 && count - 1 - index >= step
}

internal fun Modifier.alphabetTouchScrub(
    letters: List<Char>,
    layoutKey: Any,
    onActiveLetter: (Char?) -> Unit,
    onSelect: (Char) -> Unit,
): Modifier = pointerInput(letters, layoutKey) {
    if (letters.isEmpty()) return@pointerInput
    awaitEachGesture {
        // Take ownership before child click handlers: a tap selects once, and a drag does not
        // become a page scroll or an extra click on whichever entry receives the up event.
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var previous: Char? = null
        fun select(y: Float) {
            val index = alphabetTouchIndex(y, size.height, letters.size) ?: return
            val letter = letters[index]
            if (letter != previous) {
                previous = letter
                onActiveLetter(letter)
                onSelect(letter)
            }
        }
        try {
            down.consume()
            select(down.position.y)
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes
                    .firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
                select(change.position.y)
            }
        } finally {
            // Includes ACTION_CANCEL, a changed index, rotation, and leaving the page.
            onActiveLetter(null)
        }
    }
}

/** A separate, non-focusable popup avoids clipping by the rail's narrow parent or grid. */
internal class AlphabetPreviewPositionProvider(
    private val letterFraction: Float,
    private val gapPx: Int,
    private val edgePaddingPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val marginX = edgePaddingPx.coerceIn(0, maxX / 2)
        val marginY = edgePaddingPx.coerceIn(0, maxY / 2)
        val left = anchorBounds.left - gapPx - popupContentSize.width
        val right = anchorBounds.right + gapPx
        val preferred = if (layoutDirection == LayoutDirection.Ltr) left else right
        val alternative = if (layoutDirection == LayoutDirection.Ltr) right else left
        val x = when {
            preferred in marginX..(maxX - marginX) -> preferred
            alternative in marginX..(maxX - marginX) -> alternative
            else -> preferred.coerceIn(marginX, maxX - marginX)
        }
        val y = (anchorBounds.top + anchorBounds.height * letterFraction.coerceIn(0f, 1f)
            - popupContentSize.height / 2f).roundToInt().coerceIn(marginY, maxY - marginY)
        return IntOffset(x, y)
    }
}
