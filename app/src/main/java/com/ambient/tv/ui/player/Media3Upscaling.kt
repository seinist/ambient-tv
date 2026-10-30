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

/** Geometry for an explicit downsampler, preserving aspect ratio and never enlarging frames. */
internal fun media3DownscaleSize(
    inputWidth: Int, inputHeight: Int, targetWidth: Int, targetHeight: Int,
): Pair<Int, Int> {
    if (inputWidth <= 0 || inputHeight <= 0 || targetWidth <= 0 || targetHeight <= 0)
        return inputWidth to inputHeight
    val scale = minOf(1.0, targetWidth.toDouble() / inputWidth, targetHeight.toDouble() / inputHeight)
    return (inputWidth * scale).toInt().coerceAtLeast(1) to
        (inputHeight * scale).toInt().coerceAtLeast(1)
}

/** Whether the confirmed output is substantially smaller than the corrected video picture. */
internal fun media3NeedsDownscaling(
    sourceWidth: Int,
    sourceHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
    pixelWidthHeightRatio: Float = 1f,
    rotationDegrees: Int = 0,
): Boolean {
    // Our fragment downscalers work before orientation/pixel-aspect correction. Preserve the
    // platform renderer for these formats rather than resizing against mismatched axes.
    if (rotationDegrees % 180 != 0 || pixelWidthHeightRatio != 1f) return false
    if (sourceWidth <= 0 || sourceHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) return false
    val factor = minOf(outputWidth.toDouble() / sourceWidth, outputHeight.toDouble() / sourceHeight)
    return factor < 0.99
}
