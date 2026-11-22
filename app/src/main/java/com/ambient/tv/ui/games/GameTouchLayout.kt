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
    style: TouchControlStyle = TouchControlStyle()): Float {
    val scale = minOf(style.normalized().scalePercent / 100f, width / 320f).coerceAtLeast(1f)
    return scale * (portraitClusterSize(width / scale) + when (mode) {
        GameTouchMode.HYBRID, GameTouchMode.DUAL -> 256f
        else -> 128f
    })
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
        // Neo-RetroPad's two main thumb areas share a row. Shoulders and utility buttons
        // sit below them, wholly inside the screen instead of clipping at either edge.
        val hasSecondary = mode == GameTouchMode.HYBRID || mode == GameTouchMode.DUAL
        val cluster = (height - if (hasSecondary) 232f else 128f)
            .coerceIn(144f, portraitClusterSize(width))
        val secondarySize = (height - cluster - 136f).coerceIn(96f, 120f)
        val deckHeight = cluster + if (hasSecondary) 136f + secondarySize else 128f
        val top = (height - deckHeight).coerceAtLeast(0f) * verticalPosition / 100f
        faces(width - margin - cluster, top + 8f, cluster / 3f)
        val primary = rect(margin, top + 8f, cluster, cluster)
        val shoulderY = top + cluster + 20f
        button(RetroPadButton.L2, margin, shoulderY, 56f)
        button(RetroPadButton.L, margin + 64f, shoulderY, 56f)
        button(RetroPadButton.R, width - margin - 120f, shoulderY, 56f)
        button(RetroPadButton.R2, width - margin - 56f, shoulderY, 56f)
        val secondary = rect(
            if (mode == GameTouchMode.DUAL) width - margin - secondarySize else margin,
            shoulderY + 56f, secondarySize, secondarySize,
        )
        val utilityY = top + deckHeight - 56f
        button(RetroPadButton.SELECT, width / 2f - 72f, utilityY, 64f)
        button(RetroPadButton.START, width / 2f + 8f, utilityY, 64f)
        if (analog) button(RetroPadButton.L3, margin, utilityY)
        if (mode == GameTouchMode.DUAL) button(RetroPadButton.R3, width - margin - key, utilityY)
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
