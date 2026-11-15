package com.ambient.tv.ui.player

import kotlin.math.roundToInt

/** UI follows the embedded render buffer; WINDOW preserves the original logical window. */
internal enum class VideoRenderTargetMode { UI, WINDOW, DISPLAY }

internal fun VideoRenderTargetMode.fixesBuffer(uiScaled: Boolean): Boolean = when (this) {
    VideoRenderTargetMode.UI -> false
    VideoRenderTargetMode.WINDOW -> uiScaled
    VideoRenderTargetMode.DISPLAY -> true
}

/** Map the video rectangle (including letterbox/zoom/miniplayer geometry) to output pixels.
 * Android TV may expose a 1080p logical desktop while its physical mode is 2160p. */
internal fun physicalVideoRenderSize(
    viewWidth: Int, viewHeight: Int,
    logicalWidth: Int, logicalHeight: Int,
    physicalWidth: Int, physicalHeight: Int,
    windowScaleX: Double = 1.0, windowScaleY: Double = 1.0,
): Pair<Int, Int>? {
    if (minOf(viewWidth, viewHeight, logicalWidth, logicalHeight, physicalWidth, physicalHeight) <= 0) return null
    if (!validWindowScale(windowScaleX, windowScaleY)) return null
    return (viewWidth.toDouble() * windowScaleX * physicalWidth / logicalWidth).roundToInt().coerceAtLeast(1) to
        (viewHeight.toDouble() * windowScaleY * physicalHeight / logicalHeight).roundToInt().coerceAtLeast(1)
}

/** Auto's non-panel path keeps its old video budget when only the UI render buffer changes. */
internal fun windowVideoRenderSize(
    viewWidth: Int, viewHeight: Int,
    windowScaleX: Double = 1.0, windowScaleY: Double = 1.0,
): Pair<Int, Int>? {
    if (minOf(viewWidth, viewHeight) <= 0 || !validWindowScale(windowScaleX, windowScaleY)) return null
    return (viewWidth * windowScaleX).roundToInt().coerceAtLeast(1) to
        (viewHeight * windowScaleY).roundToInt().coerceAtLeast(1)
}

private fun validWindowScale(x: Double, y: Double): Boolean =
    x.isFinite() && y.isFinite() && x > 0.0 && y > 0.0
