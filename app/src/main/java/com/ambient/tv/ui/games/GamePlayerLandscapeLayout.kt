package com.ambient.tv.ui.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import com.ambient.tv.games.GameTouchMode
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.gamepad.retro.TouchControlStyle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Local play reserves side space; the separate Companion pad keeps its existing layout. */
internal data class GamePlayerLandscapeLayout(
    val surface: Rect,
    val controls: GameTouchLayout,
    val menu: Rect,
    val showTouch: Rect,
)

internal fun gamePictureBounds(width: Float, height: Float, aspect: Float): Rect {
    val ratio = aspect.takeIf { it.isFinite() && it > 0f } ?: (4f / 3f)
    val w = minOf(width, height * ratio).coerceAtLeast(1f)
    val h = minOf(height, width / ratio).coerceAtLeast(1f)
    return Rect((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
}

/** Drawn source pixels of the core's picture (after a quarter-turn swap), the base of whole-number scaling. */
internal data class GameContentFrame(val width: Int, val height: Int) {
    companion object {
        /** LibretroDroid.getContentFrameSize(): width in the high, height in the low 32 bits. */
        fun unpack(packed: Long): GameContentFrame? {
            val width = (packed ushr 32).toInt()
            val height = (packed and 0xFFFFFFFFL).toInt()
            return if (width > 0 && height > 0) GameContentFrame(width, height) else null
        }
    }
}

/**
 * A changed source size is applied once two polls agree, so a core that mixes resolutions
 * frame by frame does not resize the native surface on every poll. Unknown keeps the last size.
 */
internal class GameContentFrameFilter(private var applied: GameContentFrame? = null) {
    private var candidate: GameContentFrame? = null

    /** The size to apply now, or null when nothing changes. */
    fun observe(frame: GameContentFrame?): GameContentFrame? {
        if (frame == null || frame == applied) {
            candidate = null
            return null
        }
        if (applied != null && frame != candidate) {
            candidate = frame
            return null
        }
        applied = frame
        candidate = null
        return frame
    }
}

/** In automatic mode whole-number scaling keeps at least this share of the fitted size (native rule). */
internal const val GAME_INTEGER_SCALING_AUTO_MIN_FILL = 0.9f

/**
 * The renderer's whole-number factor for [frame] in an area of [width] x [height] pixels, or 0
 * when it fits the picture normally instead (integerscaling.h in the LibretroDroid patches).
 */
internal fun gameIntegerScale(width: Int, height: Int, frame: GameContentFrame, automatic: Boolean): Int {
    if (width <= 0 || height <= 0 || frame.width <= 0 || frame.height <= 0) return 0
    val fit = minOf(width.toFloat() / frame.width, height.toFloat() / frame.height)
    val whole = floor(fit).toInt()
    if (whole < 1) return 0
    if (automatic && whole / fit < GAME_INTEGER_SCALING_AUTO_MIN_FILL) return 0
    return whole
}

/**
 * Exact pixel bounds of the picture the renderer draws in [area]: the whole-number picture, or
 * the aspect fit when it falls back. A surface of exactly this size draws the same picture
 * without a black border of its own, so everything beside it belongs to the ambient backdrop.
 */
internal fun gameIntegerPictureBounds(area: IntRect, frame: GameContentFrame, automatic: Boolean, aspect: Float): IntRect {
    val scale = gameIntegerScale(area.width, area.height, frame, automatic)
    if (scale >= 1) {
        val w = frame.width * scale
        val h = frame.height * scale
        val left = area.left + (area.width - w) / 2
        val top = area.top + (area.height - h) / 2
        return IntRect(left, top, left + w, top + h)
    }
    val fitted = gamePictureBounds(area.width.toFloat(), area.height.toFloat(), aspect)
    return IntRect(area.left + fitted.left.roundToInt(), area.top + fitted.top.roundToInt(),
        area.left + fitted.right.roundToInt(), area.top + fitted.bottom.roundToInt())
}

/**
 * Maximise the picture height, then fit the requested pad size in the available side space.
 * Menu and restore-pad positions stay stable when the OSD opens or the pad is hidden.
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
    val slots = maxOf(left.size + 1, right.size, 1)
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
    // Only visible while the pad is hidden; it can reuse the first right utility position.
    val showTouch = slot(1, 0, 48f, 48f)
    val scaled = if (compact) compactLandscapeThumbs(mode, width, height, choice.scale, presentation)
        else gameTouchLayout(mode, width, height, false,
            safeStyle.copy(scalePercent = (choice.scale * 100f).toInt(), landscapePosition = 100), presentation)
    val buttons = scaled.buttons.filterKeys { it in scaled.faces }.toMutableMap()
    left.forEachIndexed { index, button -> buttons[button] = slot(0, index + 1) }
    right.forEachIndexed { index, button -> buttons[button] = slot(1, index) }
    var controls = scaled.copy(buttons = buttons)
    // Position tuning can lift thumb areas only through genuinely empty space.
    // The face cluster also receives the gaps between its visible buttons for rolling chords.
    val moving = listOfNotNull(controls.dpad, controls.leftStick, controls.rightStick, controls.faceButtonsBounds)
    val stationary = controls.buttons.filterKeys { it !in controls.faces }.values + menu
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
    return GamePlayerLandscapeLayout(surface, controls, menu, showTouch)
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
