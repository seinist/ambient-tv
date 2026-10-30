package com.ambient.tv.ui.player

/** Pixels per second the cleanup (deblock/denoise/deband) and sharpen shaders may process: Full HD at 60 fps. */
internal const val MEDIA3_FILTER_MAX_PIXEL_RATE = 1920L * 1088L * 60L

/**
 * Whether cleanup or sharpening may run on [width]×[height] at [fps]. Like the SDR→HDR budget,
 * the GPU passes stay within Full HD at 60 fps; 4K60 would be four times that work on every frame.
 * Unknown frame rates count as 30 fps (broadcast and most films).
 */
internal fun media3FilterBudgetAllows(width: Int, height: Int, fps: Float): Boolean {
    if (width <= 0 || height <= 0) return true
    val rate = if (fps.isFinite() && fps > 0f) fps else 30f
    return width.toLong() * height * rate <= MEDIA3_FILTER_MAX_PIXEL_RATE
}

/**
 * Sharpening and grain see the source unless an earlier GPU effect actually scales it.
 * Bilinear uses the display path after these effects, even when the surface is larger;
 * charging those source-sized passes for the display pixels would incorrectly disable them.
 */
internal fun media3SharpenBudgetAllows(
    sourceWidth: Int,
    sourceHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
    fps: Float,
    scalingInChain: Boolean,
): Boolean = if (scalingInChain) media3FilterBudgetAllows(outputWidth, outputHeight, fps)
    else media3FilterBudgetAllows(sourceWidth, sourceHeight, fps)
