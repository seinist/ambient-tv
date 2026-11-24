package com.ambient.tv.ui.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.ambient.tv.games.GameTouchMode
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.gamepad.retro.TouchControlStyle
import kotlin.math.ceil
import kotlin.math.floor

/** Local play reserves side space; the separate Companion pad keeps its existing layout. */
internal data class GamePlayerLandscapeLayout(
    val surface: Rect,
    val controls: GameTouchLayout,
    val menu: Rect,
    val rewind: Rect,
    val showTouch: Rect,
)

internal fun gamePictureBounds(width: Float, height: Float, aspect: Float): Rect {
    val ratio = aspect.takeIf { it.isFinite() && it > 0f } ?: (4f / 3f)
    val w = minOf(width, height * ratio).coerceAtLeast(1f)
    val h = minOf(height, width / ratio).coerceAtLeast(1f)
    return Rect((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
}

/**
 * Maximise the picture height, then fit the requested pad size in the available side space.
 * Menu/rewind slots always exist, so opening the OSD or hiding the pad never resizes the core.
 */
internal fun gamePlayerLandscapeLayout(
    mode: GameTouchMode,
    width: Float,
    height: Float,
    aspect: Float,
    touchEnabled: Boolean,
    style: TouchControlStyle = TouchControlStyle(),
    presentation: GameTouchPresentation = GameTouchPresentation.Generic,
    fitSurface: Boolean = true,
): GamePlayerLandscapeLayout {
    val safeStyle = style.normalized()
    val original = gameTouchLayout(mode, width, height, false,
        safeStyle.copy(scalePercent = 100, landscapePosition = 100), presentation)
    val utility = original.buttons.keys.filter { it !in original.faces }.toSet()
    val left = listOf(RetroPadButton.L2, RetroPadButton.L, RetroPadButton.L3, RetroPadButton.SELECT)
        .filter { touchEnabled && it in utility }
    val right = listOf(RetroPadButton.R2, RetroPadButton.R, RetroPadButton.R3, RetroPadButton.START)
        .filter { touchEnabled && it in utility }
    val slots = maxOf(left.size, right.size) + 1
    val requested = if (touchEnabled)
        minOf(safeStyle.scalePercent / 100f, width / 568f, height / 212f).coerceAtLeast(1f) else 1f
    data class Choice(val columns: Int, val scale: Float, val gutter: Float, val picture: Rect, val fits: Boolean)
    fun choices(thumbHeight: Float) = (1..3).mapNotNull { columns ->
        val rows = ceil(slots.toFloat() / columns).toInt()
        // Keep the utility grid above the thumb clusters. On short screens extra columns
        // need less height; on a wide phone they can retain a larger configured pad size.
        val maximum = if (touchEnabled) (height - 12f - (rows - 1) * 4f) / (rows * 48f + thumbHeight)
            else requested
        val scale = floor(minOf(requested, maximum.coerceAtLeast(1f)) * 100f) / 100f
        val cell = maxOf(56f, 48f * scale)
        val gutter = 16f + columns * cell + (columns - 1) * 4f
        if (gutter * 2f >= width) return@mapNotNull null
        val area = gamePictureBounds(width - gutter * 2f, height, aspect).translate(Offset(gutter, 0f))
        Choice(columns, scale, gutter, area, maximum >= 1f)
    }
    val ordinary = choices(156f)
    // A very short multi-window/landscape area cannot stack the full diamond and two
    // utility rows. Keep every key at 48 dp and use a compact local thumb row instead.
    // The normal phone layout and the Companion's separate controller are unaffected.
    val compact = touchEnabled && ordinary.none { it.fits }
    val candidates = if (compact) choices(100f) else ordinary
    val fitted = candidates.filter { it.fits }.ifEmpty { candidates }
    val choice = fitted.maxWithOrNull(compareBy<Choice> { it.picture.height }
        .thenBy { it.scale }.thenBy { -it.gutter })
        ?: Choice(1, 1f, minOf(72f, (width - 1f) / 2f),
            gamePictureBounds(1f, height, aspect).translate(Offset((width - 1f) / 2f, 0f)), false)
    val cellWidth = maxOf(56f, 48f * choice.scale)
    val cellHeight = 48f * choice.scale
    fun slot(side: Int, index: Int, w: Float = cellHeight, h: Float = cellHeight): Rect {
        val column = index % choice.columns
        val row = index / choice.columns
        val cellX = 8f + column * (cellWidth + 4f)
        val x = if (side == 0) cellX + (cellWidth - w) / 2f
            else width - cellX - (cellWidth + w) / 2f
        val y = 8f + row * (cellHeight + 4f) + (cellHeight - h) / 2f
        return Rect(x, y, x + w, y + h)
    }
    val menu = slot(0, 0, 48f, 48f)
    val rewind = slot(1, 0, 56f, 48f)
    val showTouch = slot(1, 1, 48f, 48f)
    val scaled = if (compact) compactLandscapeThumbs(mode, width, height, choice.scale, presentation)
        else gameTouchLayout(mode, width, height, false,
            safeStyle.copy(scalePercent = (choice.scale * 100f).toInt(), landscapePosition = 100), presentation)
    val buttons = scaled.buttons.filterKeys { it in scaled.faces }.toMutableMap()
    left.forEachIndexed { index, button -> buttons[button] = slot(0, index + 1) }
    right.forEachIndexed { index, button -> buttons[button] = slot(1, index + 1) }
    var controls = scaled.copy(buttons = buttons)
    // Position tuning can lift thumb areas only through genuinely empty space.
    // The face cluster also receives the gaps between its visible buttons for rolling chords.
    val moving = listOfNotNull(controls.dpad, controls.leftStick, controls.rightStick, controls.faceButtonsBounds)
    val stationary = controls.buttons.filterKeys { it !in controls.faces }.values + menu + rewind
    val lift = moving.minOfOrNull { area ->
        var free = (area.top - 8f).coerceAtLeast(0f)
        stationary.forEach { fixed ->
            if (area.left < fixed.right && area.right > fixed.left && fixed.bottom <= area.top)
                free = minOf(free, (area.top - fixed.bottom - 4f).coerceAtLeast(0f))
        }
        free
    } ?: 0f
    val delta = Offset(0f, -lift * (100 - safeStyle.landscapePosition) / 100f)
    controls = controls.copy(dpad = controls.dpad?.translate(delta),
        leftStick = controls.leftStick?.translate(delta), rightStick = controls.rightStick?.translate(delta),
        buttons = controls.buttons.mapValues { (button, bounds) ->
            if (button in controls.faces) bounds.translate(delta) else bounds
        })
    // Whole-number scaling owns its source-pixel fit. Do not constrain it with a nominal
    // aspect ratio which can differ from the source resolution (e.g. SNES high resolution).
    val surface = if (fitSurface) choice.picture
        else Rect(choice.gutter, 0f, width - choice.gutter, height)
    return GamePlayerLandscapeLayout(surface, controls, menu, rewind, showTouch)
}

/** Only used when the usual thumb clusters cannot fit below the side utility grid. */
private fun compactLandscapeThumbs(mode: GameTouchMode, width: Float, height: Float, scale: Float,
    presentation: GameTouchPresentation,
): GameTouchLayout {
    val key = 48f * scale
    val gap = 4f * scale
    val thumb = 96f * scale
    val bottom = height - gap
    val order = if (presentation.shape == GameTouchFaceShape.DIAMOND)
        listOf(RetroPadButton.Y, RetroPadButton.X, RetroPadButton.B, RetroPadButton.A)
            .filter { it in presentation.faces } else presentation.faces
    val columns = when (order.size) { 4 -> 2; else -> minOf(3, order.size).coerceAtLeast(1) }
    val rows = ceil(order.size.toFloat() / columns).toInt()
    val facesLeft = width - gap - columns * key
    val buttons = order.mapIndexed { index, button ->
        val x = facesLeft + index % columns * key
        val y = bottom - rows * key + index / columns * key
        button to Rect(x, y, x + key, y + key)
    }.toMap()
    val primary = Rect(gap, bottom - thumb, gap + thumb, bottom)
    val secondaryLeft = if (mode == GameTouchMode.DUAL) facesLeft - gap - thumb else gap * 2f + thumb
    val secondary = Rect(secondaryLeft, bottom - thumb, secondaryLeft + thumb, bottom)
    return GameTouchLayout(
        dpad = primary.takeIf { mode == GameTouchMode.DPAD || mode == GameTouchMode.HYBRID },
        leftStick = when (mode) {
            GameTouchMode.DPAD -> null
            GameTouchMode.HYBRID -> secondary
            else -> primary
        },
        rightStick = secondary.takeIf { mode == GameTouchMode.DUAL },
        buttons = buttons,
        faces = presentation.faces,
        // Polar diamond sectors apply only to the original diamond, not a compact grid.
        faceShape = if (presentation.shape == GameTouchFaceShape.DIAMOND) GameTouchFaceShape.ROW
            else presentation.shape,
    )
}
