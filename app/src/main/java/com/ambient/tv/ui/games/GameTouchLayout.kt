package com.ambient.tv.ui.games

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import com.ambient.tv.ui.gamepad.retro.TouchControlStyle
import com.ambient.tv.games.GameTouchMode
import com.ambient.tv.ui.gamepad.retro.RetroPadButton

/** Logical dp bounds inside the safe drawing area. Every button has at least a 48 dp target. */
internal data class GameTouchLayout(
    val dpad: Rect?,
    val leftStick: Rect?,
    val rightStick: Rect?,
    val buttons: Map<RetroPadButton, Rect>,
    val faces: List<RetroPadButton> = GameTouchPresentation.Generic.faces,
    val faceShape: GameTouchFaceShape = GameTouchFaceShape.DIAMOND,
)

private fun portraitClusterSize(width: Float): Float = ((width - 32f) / 2f).coerceIn(144f, 176f)

internal fun gameTouchDeckHeight(mode: GameTouchMode, width: Float = 320f,
    style: TouchControlStyle = TouchControlStyle(),
    presentation: GameTouchPresentation = GameTouchPresentation.Generic,
): Float {
    val scale = minOf(style.normalized().scalePercent / 100f, width / 320f).coerceAtLeast(1f)
    val base = width / scale
    val plan = portraitDeckPlan(mode, base, presentation)
    return scale * (portraitClusterSize(base) + plan.chrome + if (plan.secondary) PORTRAIT_SECONDARY_MAX else 0f)
}

private const val PORTRAIT_MARGIN = 12f
private const val PORTRAIT_SECONDARY_MIN = 96f
private const val PORTRAIT_SECONDARY_MAX = 120f
private const val UTILITY_MIN_WIDTH = 48f
private const val UTILITY_MAX_WIDTH = 64f
private const val UTILITY_GAP = 8f

/** Which row holds Start/Select in portrait: beside the shoulders, beside the second stick, or below. */
private enum class UtilityRow { SHOULDERS, SECONDARY, BOTTOM }

/**
 * The portrait deck's rows for one system. Start/Select sit in the first free row under the
 * thumb clusters, like on the real pads, instead of always taking a separate bottom row:
 * between the shoulder buttons when they fit (most pads), otherwise beside the second stick,
 * and only as the last resort in an extra row. Absent shoulders leave no holes.
 */
private class PortraitDeckPlan(
    /** Outer to inner, hugging the left / right edge. */
    val leftShoulders: List<RetroPadButton>,
    val rightShoulders: List<RetroPadButton>,
    val utility: List<RetroPadButton>,
    val utilityRow: UtilityRow,
    /** Left-stick click beside the shoulders (analog-only pads have no second-stick row). */
    val l3InShoulderRow: Boolean,
    val l3: Boolean,
    val r3: Boolean,
    val secondary: Boolean,
) {
    /** Deck height without the thumb clusters and without the second stick row's stick. */
    val chrome: Float get() = (if (secondary) 84f else 76f) + if (needsBottomRow) 52f else 0f
    val needsBottomRow: Boolean get() = (utility.isNotEmpty() && utilityRow == UtilityRow.BOTTOM) ||
        (l3 && !secondary && !l3InShoulderRow)
}

private fun portraitDeckPlan(mode: GameTouchMode, width: Float, presentation: GameTouchPresentation): PortraitDeckPlan {
    fun shown(button: RetroPadButton) = button in presentation.labels && button !in presentation.faces
    val left = listOf(RetroPadButton.L2, RetroPadButton.L).filter(::shown)
    val right = listOf(RetroPadButton.R2, RetroPadButton.R).filter(::shown)
    val utility = listOf(RetroPadButton.SELECT, RetroPadButton.START).filter(::shown)
    val secondary = mode == GameTouchMode.HYBRID || mode == GameTouchMode.DUAL
    val l3 = mode != GameTouchMode.DPAD && shown(RetroPadButton.L3)
    val r3 = mode == GameTouchMode.DUAL && shown(RetroPadButton.R3)
    val margin = PORTRAIT_MARGIN
    var from = shoulderRowStart(left.size)
    val to = shoulderRowEnd(width, right.size)
    // The analog pad's stick click joins the shoulder row's left side when there is space for
    // it, but never pushes Start/Select out of that row.
    val withL3 = from + UTILITY_MIN_WIDTH + UTILITY_GAP
    val l3InShoulderRow = l3 && !secondary && to - from >= UTILITY_MIN_WIDTH && (utility.isEmpty() ||
        utilityGroup(utility.size, withL3, to, width / 2f) != null ||
        utilityGroup(utility.size, from, to, width / 2f) == null)
    if (l3InShoulderRow) from = withL3
    val row = when {
        utility.isEmpty() || utilityGroup(utility.size, from, to, width / 2f) != null -> UtilityRow.SHOULDERS
        secondary && secondaryUtilitySpan(mode, width, PORTRAIT_SECONDARY_MAX, l3, r3, margin)
            .let { (a, b) -> utilityGroup(utility.size, a, b, width / 2f) != null } -> UtilityRow.SECONDARY
        else -> UtilityRow.BOTTOM
    }
    return PortraitDeckPlan(left, right, utility, row, l3InShoulderRow, l3, r3, secondary)
}

private fun shoulderRowStart(count: Int) = PORTRAIT_MARGIN + count * 64f
private fun shoulderRowEnd(width: Float, count: Int) = width - PORTRAIT_MARGIN - count * 64f

/** The free span beside the second stick and its stick-click buttons. */
private fun secondaryUtilitySpan(mode: GameTouchMode, width: Float, secondarySize: Float,
    l3: Boolean, r3: Boolean, margin: Float,
): Pair<Float, Float> = if (mode == GameTouchMode.DUAL) {
    (margin + if (l3) UTILITY_MIN_WIDTH + UTILITY_GAP else 0f) to
        (width - margin - secondarySize - UTILITY_GAP - if (r3) UTILITY_MIN_WIDTH + UTILITY_GAP else 0f)
} else {
    (margin + secondarySize + UTILITY_GAP + if (l3) UTILITY_MIN_WIDTH + UTILITY_GAP else 0f) to (width - margin)
}

/**
 * Start/Select as one group inside [from, to]: equal widths of 48..64 dp, centred on the screen
 * when possible, otherwise on the free span. Null when even 48 dp targets do not fit.
 */
private fun utilityGroup(count: Int, from: Float, to: Float, center: Float): Pair<Float, Float>? {
    if (count == 0) return from to 0f
    val width = ((to - from - UTILITY_GAP * (count - 1)) / count).coerceAtMost(UTILITY_MAX_WIDTH)
    if (width < UTILITY_MIN_WIDTH) return null
    val total = width * count + UTILITY_GAP * (count - 1)
    val centred = center - total / 2f
    val start = if (centred >= from && centred + total <= to) centred else from + (to - from - total) / 2f
    return start to width
}

/**
 * Portrait uses a separate deck below the picture. In landscape the controls stay at its sides;
 * neither the menu's top-left target nor another control is allowed to cover a gameplay button.
 */
internal fun gameTouchLayout(mode: GameTouchMode, width: Float, height: Float, portrait: Boolean,
    style: TouchControlStyle = TouchControlStyle(),
    presentation: GameTouchPresentation = GameTouchPresentation.Generic,
): GameTouchLayout {
    val safeStyle = style.normalized()
    val minimumHeight = if (!portrait) 212f
        else if (mode == GameTouchMode.DUAL || mode == GameTouchMode.HYBRID) 380f else 272f
    val scale = minOf(safeStyle.scalePercent / 100f, width / if (portrait) 320f else 568f,
        height / minimumHeight).coerceAtLeast(1f)
    val base = baseGameTouchLayout(mode, width / scale, height / scale, portrait,
        if (portrait) safeStyle.portraitPosition else safeStyle.landscapePosition, presentation)
    fun Rect.scaled() = Rect(left * scale, top * scale, right * scale, bottom * scale)
    return if (scale == 1f) base else base.copy(dpad = base.dpad?.scaled(),
        leftStick = base.leftStick?.scaled(), rightStick = base.rightStick?.scaled(),
        buttons = base.buttons.mapValues { (_, bounds) -> bounds.scaled() })
}

private fun baseGameTouchLayout(mode: GameTouchMode, width: Float, height: Float, portrait: Boolean,
    verticalPosition: Int, presentation: GameTouchPresentation,
): GameTouchLayout {
    val margin = 12f
    val key = 48f
    val buttons = linkedMapOf<RetroPadButton, Rect>()
    fun rect(x: Float, y: Float, w: Float = key, h: Float = key) = Rect(x, y, x + w, y + h)
    fun button(button: RetroPadButton, x: Float, y: Float, w: Float = key) {
        if (button !in presentation.faces) buttons[button] = rect(x, y, w)
    }
    fun faces(x: Float, y: Float, size: Float = key) {
        when (presentation.shape) {
            GameTouchFaceShape.DIAMOND -> {
                buttons[RetroPadButton.X] = rect(x + size, y, size, size)
                buttons[RetroPadButton.Y] = rect(x, y + size, size, size)
                buttons[RetroPadButton.A] = rect(x + size * 2f, y + size, size, size)
                buttons[RetroPadButton.B] = rect(x + size, y + size * 2f, size, size)
            }
            GameTouchFaceShape.SIX -> presentation.faces.forEachIndexed { index, face ->
                buttons[face] = rect(x + index % 3 * size, y + (index / 3 + .5f) * size, size, size)
            }
            GameTouchFaceShape.ROW -> presentation.faces.forEachIndexed { index, face ->
                buttons[face] = rect(x + index * size, y + (1.4f - index * .2f) * size, size, size)
            }
            GameTouchFaceShape.PAIR -> presentation.faces.forEachIndexed { index, face ->
                buttons[face] = rect(x + (.3f + index * 1.4f) * size,
                    y + (1.5f - index * .6f) * size, size * 1.2f, size * 1.2f)
            }
            GameTouchFaceShape.SINGLE -> presentation.faces.firstOrNull()?.let { face ->
                buttons[face] = rect(x + size * .75f, y + size * .75f, size * 1.5f, size * 1.5f)
            }
        }
    }
    fun visibleButtons() = buttons.filterKeys { it in presentation.labels }
    fun finish(dpad: Rect?, left: Rect?, right: Rect?): GameTouchLayout {
        val layout = GameTouchLayout(dpad, left, right, visibleButtons(), presentation.faces, presentation.shape)
        if (portrait || verticalPosition == 100) return layout
        // Move the thumb areas only into genuinely free space; utility/menu targets stay fixed.
        val moving = listOfNotNull(dpad, left, right) + layout.buttons.filterKeys { it in layout.faces }.values
        val stationary = layout.buttons.filterKeys { it !in layout.faces }.values + Rect(12f, 12f, 60f, 60f)
        val lift = moving.minOfOrNull { area ->
            var free = (area.top - 8f).coerceAtLeast(0f)
            stationary.forEach { fixed ->
                if (area.left < fixed.right && area.right > fixed.left && fixed.bottom <= area.top) {
                    free = minOf(free, (area.top - fixed.bottom - 8f).coerceAtLeast(0f))
                }
            }
            free
        } ?: 0f
        val delta = Offset(0f, -lift * (100 - verticalPosition) / 100f)
        return layout.copy(dpad = dpad?.translate(delta), leftStick = left?.translate(delta),
            rightStick = right?.translate(delta), buttons = layout.buttons.mapValues { (button, bounds) ->
                if (button in layout.faces) bounds.translate(delta) else bounds
            })
    }
    val analog = mode != GameTouchMode.DPAD
    if (portrait) {
        // Neo-RetroPad's two main thumb areas share a row. Shoulders hug the edges below them;
        // Start/Select join the first row under the clusters that has room for them.
        val plan = portraitDeckPlan(mode, width, presentation)
        val hasSecondary = plan.secondary
        val cluster = (height - plan.chrome - if (hasSecondary) PORTRAIT_SECONDARY_MIN else 0f)
            .coerceIn(144f, portraitClusterSize(width))
        val secondarySize = if (hasSecondary) {
            (height - cluster - plan.chrome).coerceIn(PORTRAIT_SECONDARY_MIN, PORTRAIT_SECONDARY_MAX)
        } else 0f
        val deckHeight = cluster + plan.chrome + secondarySize
        val top = (height - deckHeight).coerceAtLeast(0f) * verticalPosition / 100f
        faces(width - margin - cluster, top + 8f, cluster / 3f)
        val primary = rect(margin, top + 8f, cluster, cluster)
        val shoulderY = top + cluster + 20f
        plan.leftShoulders.forEachIndexed { index, shoulder -> button(shoulder, margin + index * 64f, shoulderY, 56f) }
        plan.rightShoulders.forEachIndexed { index, shoulder ->
            button(shoulder, width - margin - 56f - index * 64f, shoulderY, 56f)
        }
        val secondaryY = shoulderY + 56f
        val secondary = rect(
            if (mode == GameTouchMode.DUAL) width - margin - secondarySize else margin,
            secondaryY, secondarySize, secondarySize,
        )
        val bottomY = top + deckHeight - 56f
        fun placeUtility(from: Float, to: Float, y: Float) {
            val (start, size) = utilityGroup(plan.utility.size, from, to, width / 2f) ?: return
            plan.utility.forEachIndexed { index, utility ->
                button(utility, start + index * (size + UTILITY_GAP), y, size)
            }
        }
        var shoulderFrom = shoulderRowStart(plan.leftShoulders.size)
        val shoulderTo = shoulderRowEnd(width, plan.rightShoulders.size)
        if (plan.l3InShoulderRow) {
            button(RetroPadButton.L3, shoulderFrom, shoulderY)
            shoulderFrom += key + UTILITY_GAP
        } else if (plan.l3 && !hasSecondary) {
            button(RetroPadButton.L3, margin, bottomY)
        }
        // Stick clicks sit beside their own stick in the second row.
        val stickRowY = secondaryY + (secondarySize - key) / 2f
        if (hasSecondary && plan.l3) {
            button(RetroPadButton.L3,
                if (mode == GameTouchMode.DUAL) margin else margin + secondarySize + UTILITY_GAP, stickRowY)
        }
        if (plan.r3) button(RetroPadButton.R3, width - margin - secondarySize - UTILITY_GAP - key, stickRowY)
        when (plan.utilityRow) {
            UtilityRow.SHOULDERS -> placeUtility(shoulderFrom, shoulderTo, shoulderY)
            UtilityRow.SECONDARY -> secondaryUtilitySpan(mode, width, secondarySize, plan.l3, plan.r3, margin)
                .let { (from, to) -> placeUtility(from, to, stickRowY) }
            UtilityRow.BOTTOM -> placeUtility(margin, width - margin, bottomY)
        }
        return finish(
            dpad = primary.takeIf { mode == GameTouchMode.DPAD || mode == GameTouchMode.HYBRID },
            left = when (mode) {
                GameTouchMode.DPAD -> null
                GameTouchMode.HYBRID -> secondary
                else -> primary
            },
            right = secondary.takeIf { mode == GameTouchMode.DUAL },
        )
    }

    // Leave 12..60 dp at top left to the player's permanent menu target.
    button(RetroPadButton.L2, 72f, 8f, 56f)
    button(RetroPadButton.L, 136f, 8f, 56f)
    button(RetroPadButton.R, width - margin - 120f, 8f, 56f)
    button(RetroPadButton.R2, width - margin - 56f, 8f, 56f)
    faces(width - margin - key * 3f, height - margin - key * 3f)
    val primary = rect(margin, height - margin - 136f, 136f, 136f)
    // The remote page also needs a header. Keep its shorter landscape deck usable without
    // putting the utility buttons on top of a stick (212 dp of safe height is sufficient).
    val secondarySize = (height - 116f).coerceIn(96f, 112f)
    val secondary = rect(
        if (mode == GameTouchMode.DUAL) width - margin - 272f else 160f,
        height - margin - secondarySize, secondarySize, secondarySize,
    )
    // Centre utility buttons are a separate row above the thumb clusters.
    button(RetroPadButton.SELECT, width / 2f - 72f, 56f, 64f)
    button(RetroPadButton.START, width / 2f + 8f, 56f, 64f)
    if (analog) button(RetroPadButton.L3, 208f, 8f)
    if (mode == GameTouchMode.DUAL) button(RetroPadButton.R3, width - margin - 176f, 8f)
    return finish(
        dpad = primary.takeIf { mode == GameTouchMode.DPAD || mode == GameTouchMode.HYBRID },
        left = when (mode) {
            GameTouchMode.DPAD -> null
            GameTouchMode.HYBRID -> secondary
            else -> primary
        },
        right = secondary.takeIf { mode == GameTouchMode.DUAL },
    )
}
