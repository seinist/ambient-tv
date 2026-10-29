package com.ambient.tv.ui.player

import kotlin.math.abs
import kotlin.math.roundToInt

internal data class PlayerZoomChoices(
    val value: String,
    val selectedKey: String,
    val options: List<Pair<String, String>>,
)

/** Pinching may produce any percentage; never mark the 100% reset as selected for it. */
internal fun playerZoomChoices(zoom: Float): PlayerZoomChoices {
    val presets = listOf(1f, 1.1f, 1.25f, 1.5f)
    val safe = zoom.takeIf { it.isFinite() }?.coerceIn(VIDEO_MIN_ZOOM, VIDEO_MAX_ZOOM) ?: 1f
    val selected = presets.firstOrNull { abs(it - safe) < 0.0001f } ?: safe
    fun label(value: Float) = "${(value * 100).roundToInt()}%"
    return PlayerZoomChoices(
        value = label(selected),
        selectedKey = selected.toString(),
        options = (presets + selected).distinct().sorted().map { it.toString() to label(it) },
    )
}
