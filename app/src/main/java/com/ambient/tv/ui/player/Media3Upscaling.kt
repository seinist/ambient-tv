package com.ambient.tv.ui.player

/** Compare the fitted, display-corrected source size with the renderer surface. */
internal fun media3NeedsUpscaling(
    sourceWidth: Int,
    sourceHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
    pixelWidthHeightRatio: Float = 1f,
    rotationDegrees: Int = 0,
): Boolean {
    if (sourceWidth <= 0 || sourceHeight <= 0 || outputWidth <= 0 || outputHeight <= 0 ||
        !pixelWidthHeightRatio.isFinite() || pixelWidthHeightRatio <= 0f) return false
    val correctedWidth = sourceWidth.toDouble() * pixelWidthHeightRatio
    val correctedHeight = sourceHeight.toDouble()
    val rotated = rotationDegrees % 180 != 0
    val displayWidth = if (rotated) correctedHeight else correctedWidth
    val displayHeight = if (rotated) correctedWidth else correctedHeight
    return minOf(outputWidth / displayWidth, outputHeight / displayHeight) > 1.01
}
