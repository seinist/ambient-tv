package com.ambient.tv.ui.player

/** Cleanup (deblock/denoise/deband) keeps the conservative Full-HD-at-60fps budget. */
internal const val MEDIA3_FILTER_MAX_PIXEL_RATE = 1920L * 1088L * 60L

/** Adaptive Sharpen has a larger neighbourhood and more ALU work: at most UHD30, including padding. */
internal const val MEDIA3_ADAPTIVE_SHARPEN_MAX_PIXEL_RATE = 2L * MEDIA3_FILTER_MAX_PIXEL_RATE

/** Nine-tap unsharp/CAS, five-tap RCAS and single-pass grain may run at UHD60, including padding. */
internal const val MEDIA3_LIGHT_FILTER_MAX_PIXEL_RATE = 4L * MEDIA3_FILTER_MAX_PIXEL_RATE

/**
 * Whether cleanup may run on [width]×[height] at [fps]. Sharpening and grain have separate,
 * workload-specific limits below. These are admission bounds, not measured GPU capabilities;
 * the existing dropped-frame monitor still evaluates every admitted effect chain.
 */
internal fun media3FilterBudgetAllows(width: Int, height: Int, fps: Float): Boolean =
    media3PixelRateWithinBudget(width, height, fps, MEDIA3_FILTER_MAX_PIXEL_RATE)

/** Unknown frame rates count as 30 fps (broadcast and most films), consistently for every stage. */
internal fun media3FilterFrameRate(fps: Float): Float = fps.takeIf { it.isFinite() && it > 0f } ?: 30f

/**
 * Estimate the texture size passed to custom effects from the track format. Media3's surface
 * decoder applies rotation first (also inverting PAR), then its frame processor expands
 * non-square pixels before the first effect. Match those float operations and integer truncation.
 * Track dimensions may include padding later cropped by the decoder, so this remains conservative.
 */
internal fun media3ProcessingInputSize(
    width: Int,
    height: Int,
    pixelWidthHeightRatio: Float = 1f,
    rotationDegrees: Int = 0,
): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return width to height
    var inputWidth = width
    var inputHeight = height
    var ratio = pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    val rotation = (rotationDegrees % 360 + 360) % 360
    if (rotation == 90 || rotation == 270) {
        inputWidth = height
        inputHeight = width
        ratio = 1f / ratio
    }
    if (ratio > 1f) inputWidth = media3ExpandedPixelDimension(inputWidth * ratio, inputWidth)
    else if (ratio < 1f) inputHeight = media3ExpandedPixelDimension(inputHeight / ratio, inputHeight)
    return inputWidth to inputHeight
}

private fun media3ExpandedPixelDimension(value: Float, original: Int): Int =
    if (!value.isFinite() || value >= Int.MAX_VALUE.toFloat()) Int.MAX_VALUE
    else value.toInt().coerceAtLeast(original)

private fun media3PixelRateWithinBudget(width: Int, height: Int, fps: Float, maximum: Long): Boolean {
    if (width <= 0 || height <= 0) return true
    return width.toDouble() * height * media3FilterFrameRate(fps) <= maximum.toDouble()
}

internal fun media3SharpenMaxPixelRate(kind: String): Long = when (kind) {
    "light", "medium", "strong", "cas", "rcas" -> MEDIA3_LIGHT_FILTER_MAX_PIXEL_RATE
    "adaptive" -> MEDIA3_ADAPTIVE_SHARPEN_MAX_PIXEL_RATE
    // A future/unknown shader must not inherit the light-shader allowance accidentally.
    else -> MEDIA3_FILTER_MAX_PIXEL_RATE
}

/**
 * Sharpening sees the source unless an earlier GPU effect actually scales it.
 * Bilinear uses the display path after these effects, even when the surface is larger;
 * charging those source-sized passes for the display pixels would incorrectly disable them.
 * [outputWidth]/[outputHeight] must name the fitted image, not a larger letterboxed viewport.
 * Runtime planning uses [media3ApplyFilterBudget] to derive those sizes from the actual stages.
 */
internal fun media3SharpenBudgetAllows(
    sourceWidth: Int,
    sourceHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
    fps: Float,
    scalingInChain: Boolean,
    kind: String = "light",
): Boolean = if (scalingInChain)
    media3PixelRateWithinBudget(outputWidth, outputHeight, fps, media3SharpenMaxPixelRate(kind))
else media3PixelRateWithinBudget(sourceWidth, sourceHeight, fps, media3SharpenMaxPixelRate(kind))

/** A configured stage omitted by its own workload bound; usable for an exact UI reason. */
internal data class Media3FilterBudgetExclusion(
    val stage: Media3Stage,
    val width: Int,
    val height: Int,
    val frameRate: Float,
    val maxPixelRate: Long,
)

internal data class Media3FilterBudgetResult(
    val stages: List<Media3Stage>,
    val excluded: List<Media3FilterBudgetExclusion>,
    /** Actual image size after explicit GPU scaling, useful for the overload-monitor signature. */
    val processingSize: Pair<Int, Int>,
    val frameRate: Float,
)

/**
 * Apply per-filter limits after scaler capability/budget fallbacks have chosen their real stages.
 * This matters when NIS falls back to a normal scaler: the saved sharpening choice then runs
 * again and must get its own limit. Cleanup always sees source pixels; sharpen/grain see the
 * fitted result of an explicit GPU scaler. Scaling done later by the display does not change
 * their workload. One rejected stage never disables a cheaper, independent stage.
 */
internal fun media3ApplyFilterBudget(
    stages: List<Media3Stage>,
    sourceWidth: Int,
    sourceHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
    fps: Float,
): Media3FilterBudgetResult {
    var size = sourceWidth to sourceHeight
    val rate = media3FilterFrameRate(fps)
    val excluded = mutableListOf<Media3FilterBudgetExclusion>()
    val accepted = buildList {
        for (stage in stages) {
            val maximum = when (stage) {
                is Media3Stage.Deblock, is Media3Stage.Denoise, is Media3Stage.Deband -> MEDIA3_FILTER_MAX_PIXEL_RATE
                is Media3Stage.Sharpen -> media3SharpenMaxPixelRate(stage.kind)
                is Media3Stage.Grain -> MEDIA3_LIGHT_FILTER_MAX_PIXEL_RATE
                else -> null
            }
            if (maximum != null && !media3PixelRateWithinBudget(size.first, size.second, rate, maximum)) {
                excluded += Media3FilterBudgetExclusion(stage, size.first, size.second, rate, maximum)
                continue
            }
            add(stage)
            size = when (stage) {
                is Media3Stage.Scale -> media3FitSize(size.first, size.second, outputWidth, outputHeight)
                is Media3Stage.Downscale -> media3DownscaleSize(size.first, size.second, outputWidth, outputHeight)
                else -> size
            }
        }
    }
    return Media3FilterBudgetResult(accepted, excluded, size, rate)
}
