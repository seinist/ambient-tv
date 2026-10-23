@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.media3.ui.AspectRatioFrameLayout
import kotlin.math.roundToInt

internal fun videoPresentationSize(
    width: Int, height: Int, presentation: VideoPresentation, sourceAspectRatio: Float?,
): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 0 to 0
    val effective = presentation.normalized()
    val viewportRatio = width.toFloat() / height
    val ratio = effective.aspectRatio
        ?: sourceAspectRatio?.takeIf { it.isFinite() && it > 0f } ?: viewportRatio
    var w = width.toFloat()
    var h = height.toFloat()
    when (effective.resizeMode) {
        AspectRatioFrameLayout.RESIZE_MODE_FIT -> if (ratio > viewportRatio) h = width / ratio else w = height * ratio
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> if (ratio > viewportRatio) w = height * ratio else h = width / ratio
    }
    return (w * effective.zoom).roundToInt().coerceAtLeast(1) to
        (h * effective.zoom).roundToInt().coerceAtLeast(1)
}
