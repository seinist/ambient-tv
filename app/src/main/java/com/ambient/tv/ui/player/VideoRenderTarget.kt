package com.ambient.tv.ui.player

import kotlin.math.roundToInt

/** Map the video rectangle (including letterbox/zoom/miniplayer geometry) to output pixels.
 * Android TV may expose a 1080p logical desktop while its physical mode is 2160p. */
internal fun physicalVideoRenderSize(
    viewWidth: Int, viewHeight: Int,
    logicalWidth: Int, logicalHeight: Int,
    physicalWidth: Int, physicalHeight: Int,
): Pair<Int, Int>? {
    if (minOf(viewWidth, viewHeight, logicalWidth, logicalHeight, physicalWidth, physicalHeight) <= 0) return null
    return (viewWidth.toDouble() * physicalWidth / logicalWidth).roundToInt().coerceAtLeast(1) to
        (viewHeight.toDouble() * physicalHeight / logicalHeight).roundToInt().coerceAtLeast(1)
}
