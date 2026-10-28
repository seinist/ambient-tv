package com.ambient.tv.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/** Part of the screen the video takes, as fractions of its width and height. */
data class VideoWindowFraction(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * A smaller video window inside the screen, e.g. the scaled broadcast picture an HbbTV application
 * shows in its menus. Null is full screen. Applied to the root video host, so every engine follows.
 */
object PlaybackVideoWindow {
    var window by mutableStateOf<VideoWindowFraction?>(null)

    /** Lays the video host out in [window]; read during layout, so a change only re-lays out. */
    val modifier: Modifier = Modifier.layout { measurable, constraints ->
        val fraction = window
        if (fraction == null || !constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        } else {
            val w = (constraints.maxWidth * fraction.width).roundToInt().coerceAtLeast(1)
            val h = (constraints.maxHeight * fraction.height).roundToInt().coerceAtLeast(1)
            val placeable = measurable.measure(Constraints.fixed(w, h))
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place((constraints.maxWidth * fraction.left).roundToInt(), (constraints.maxHeight * fraction.top).roundToInt())
            }
        }
    }
}

/**
 * "left,top,width,height" as fractions, or "full". A window covering (almost) the whole screen or
 * a degenerate one counts as full screen; values are clamped to the screen.
 */
fun parseVideoWindow(value: String): VideoWindowFraction? {
    val parts = value.split(',').mapNotNull { it.trim().toFloatOrNull() }
    if (parts.size != 4 || parts.any { !it.isFinite() }) return null
    val left = parts[0].coerceIn(0f, 1f)
    val top = parts[1].coerceIn(0f, 1f)
    val width = parts[2].coerceIn(0f, 1f - left)
    val height = parts[3].coerceIn(0f, 1f - top)
    if (width < 0.05f || height < 0.05f) return null
    if (width >= 0.95f && height >= 0.95f) return null
    return VideoWindowFraction(left, top, width, height)
}
