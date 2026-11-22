package com.ambient.tv.ui.games

import androidx.compose.ui.geometry.Rect
import com.ambient.tv.games.GameTouchMode
import com.ambient.tv.ui.gamepad.retro.RetroPadButton

/** Logical dp bounds inside the safe drawing area. Every button has at least a 48 dp target. */
internal data class GameTouchLayout(
    val dpad: Rect?,
    val leftStick: Rect?,
    val rightStick: Rect?,
    val buttons: Map<RetroPadButton, Rect>,
)

private fun portraitClusterSize(width: Float): Float = ((width - 32f) / 2f).coerceIn(144f, 176f)

internal fun gameTouchDeckHeight(mode: GameTouchMode, width: Float = 320f): Float =
    portraitClusterSize(width) + when (mode) {
        GameTouchMode.HYBRID, GameTouchMode.DUAL -> 256f
        else -> 128f
    }

/**
 * Portrait uses a separate deck below the picture. In landscape the controls stay at its sides;
 * neither the menu's top-left target nor another control is allowed to cover a gameplay button.
 */
internal fun gameTouchLayout(mode: GameTouchMode, width: Float, height: Float, portrait: Boolean): GameTouchLayout {
    val margin = 12f
    val key = 48f
    val buttons = linkedMapOf<RetroPadButton, Rect>()
    fun rect(x: Float, y: Float, w: Float = key, h: Float = key) = Rect(x, y, x + w, y + h)
    fun button(button: RetroPadButton, x: Float, y: Float, w: Float = key) {
        buttons[button] = rect(x, y, w)
    }
    fun faces(x: Float, y: Float, size: Float = key) {
        buttons[RetroPadButton.X] = rect(x + size, y, size, size)
        buttons[RetroPadButton.Y] = rect(x, y + size, size, size)
        buttons[RetroPadButton.A] = rect(x + size * 2f, y + size, size, size)
        buttons[RetroPadButton.B] = rect(x + size, y + size * 2f, size, size)
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
        val top = (height - deckHeight).coerceAtLeast(0f)
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
        return GameTouchLayout(
            dpad = primary.takeIf { mode == GameTouchMode.DPAD || mode == GameTouchMode.HYBRID },
            leftStick = when (mode) {
                GameTouchMode.DPAD -> null
                GameTouchMode.HYBRID -> secondary
                else -> primary
            },
            rightStick = secondary.takeIf { mode == GameTouchMode.DUAL },
            buttons = buttons,
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
    return GameTouchLayout(
        dpad = primary.takeIf { mode == GameTouchMode.DPAD || mode == GameTouchMode.HYBRID },
        leftStick = when (mode) {
            GameTouchMode.DPAD -> null
            GameTouchMode.HYBRID -> secondary
            else -> primary
        },
        rightStick = secondary.takeIf { mode == GameTouchMode.DUAL },
        buttons = buttons,
    )
}
