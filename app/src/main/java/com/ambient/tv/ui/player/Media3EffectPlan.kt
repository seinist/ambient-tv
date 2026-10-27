package com.ambient.tv.ui.player

import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.data.local.VideoImageCatalog

/** One stage of Media3's SDR effect chain, independent of GL so the order stays testable. */
internal sealed interface Media3Stage {
    /** Edge-preserving (bilateral) denoise on the source, before deband smooths what is left. */
    data class Denoise(val strength: String) : Media3Stage
    data class Deband(val strength: String) : Media3Stage
    /** Only planned while the fitted output is larger than the source; never past the display. */
    data class Scale(val scaler: String) : Media3Stage
    /** light/medium/strong unsharp mask, cas, adaptive, or rcas (FSR's own sharpening pass). */
    data class Sharpen(val kind: String) : Media3Stage
    /** Film grain after sharpening, so the sharpener does not amplify it. */
    data class Grain(val strength: String) : Media3Stage
    data class Brightness(val value: Int) : Media3Stage
    data class Contrast(val value: Int) : Media3Stage
    data class Saturation(val value: Int) : Media3Stage
    data class Gamma(val value: Int) : Media3Stage
}

/**
 * Order: denoise, deband (source pixels) → scale → sharpen → grain → color. Bilinear means no scale stage: the
 * display path scales as with direct output. FSR includes RCAS, so the sharpening choice is not
 * stacked on top while FSR runs.
 */
internal fun media3EffectPlan(
    options: Media3ImageOptions,
    upscaling: Boolean,
    /** False above [media3FilterBudgetAllows] for the source: no deband pass. */
    debandAllowed: Boolean = true,
    /** False above [media3FilterBudgetAllows] for the sharpened size: no unsharp/CAS/RCAS pass. */
    sharpenAllowed: Boolean = true,
    /** False above [media3HeavyScalerBudgetAllows] for the output: heavy scalers run as Catmull-Rom. */
    heavyScaleAllowed: Boolean = true,
): List<Media3Stage> {
    val o = options.normalized()
    if (!o.enabled) return emptyList()
    val scaler = o.scaler.takeIf { upscaling && it != "bilinear" }
        ?.let { if (!heavyScaleAllowed && it in MEDIA3_HEAVY_SCALERS) "catmull_rom" else it }
    return buildList {
        // Denoise reads the source like deband and shares its budget.
        if (o.denoise != "off" && debandAllowed) add(Media3Stage.Denoise(o.denoise))
        if (o.deband != "off" && debandAllowed) add(Media3Stage.Deband(o.deband))
        scaler?.let { add(Media3Stage.Scale(it)) }
        when {
            !sharpenAllowed -> Unit
            scaler == "fsr" -> add(Media3Stage.Sharpen("rcas"))
            // NVScaler sharpens inside its own pass.
            scaler == "nis" -> Unit
            o.sharpening != "off" -> add(Media3Stage.Sharpen(o.sharpening))
        }
        // Grain runs on the same (output) size as sharpening and shares its budget.
        if (o.filmGrain != "off" && sharpenAllowed) add(Media3Stage.Grain(o.filmGrain))
        if (o.brightness != 0) add(Media3Stage.Brightness(o.brightness))
        if (o.contrast != 0) add(Media3Stage.Contrast(o.contrast))
        if (o.saturation != 0) add(Media3Stage.Saturation(o.saturation))
        if (o.gamma != 0) add(Media3Stage.Gamma(o.gamma))
    }
}

/**
 * Scalers that read many texels per output pixel (NVScaler, RAVU, EWA and the wide orthogonal
 * kernels): several times the work of the cubic or FSR pass.
 */
internal val MEDIA3_HEAVY_SCALERS: Set<String> = setOf("nis") + MEDIA3_RAVU_SCALERS +
    MEDIA3_KERNELS.filterValues { it.polar || it.taps >= 3 }.keys

/** Output pixels per second a heavy scaler may produce: 4K at 30 fps (twice the filter budget). */
internal const val MEDIA3_HEAVY_SCALER_MAX_PIXEL_RATE = 2L * 1920L * 1088L * 60L

/** Whether a heavy scaler may produce [width]x[height] at [fps]; unknown frame rates count as 30. */
internal fun media3HeavyScalerBudgetAllows(width: Int, height: Int, fps: Float): Boolean {
    if (width <= 0 || height <= 0) return true
    val rate = if (fps.isFinite() && fps > 0f) fps else 30f
    return width.toLong() * height * rate <= MEDIA3_HEAVY_SCALER_MAX_PIXEL_RATE
}

/** Pixels per second the multi-tap deband/sharpen shaders may process: Full HD at 60 fps. */
internal const val MEDIA3_FILTER_MAX_PIXEL_RATE = 1920L * 1088L * 60L

/**
 * Whether deband or sharpening may run on [width]×[height] at [fps]. Like the SDR→HDR budget,
 * the GPU passes stay within Full HD at 60 fps; 4K60 would be four times that work on every frame.
 * Unknown frame rates count as 30 fps (broadcast and most films).
 */
internal fun media3FilterBudgetAllows(width: Int, height: Int, fps: Float): Boolean {
    if (width <= 0 || height <= 0) return true
    val rate = if (fps.isFinite() && fps > 0f) fps else 30f
    return width.toLong() * height * rate <= MEDIA3_FILTER_MAX_PIXEL_RATE
}

/** True when FSR (RCAS) or NIS replaces the sharpening row (it would otherwise sharpen twice). */
internal fun media3FsrSharpens(options: Media3ImageOptions, upscalerApplies: Boolean): Boolean =
    upscalerApplies && options.normalized().scaler in setOf("fsr", "nis")

internal fun media3ScalerLabel(key: String): String = VideoImageCatalog.media3Scalers[key] ?: key

internal fun media3ScalerChoices(): List<Pair<String, String>> = VideoImageCatalog.media3Scalers.toList()

/** A planned stage plus the gamma fused into its pass (0 = none). */
internal data class Media3FusedStage(val stage: Media3Stage, val gamma: Int = 0)

/**
 * Saves one full-size GPU texture: a trailing gamma is computed in the previous own shader pass
 * (deband, FSR/cubic scale, sharpen) instead of a pass of its own. Brightness, contrast and
 * saturation run between them, so with any of those set gamma stays separate (same result).
 * Lanczos is Media3's own effect and cannot take it either.
 */
internal fun media3FusedStages(stages: List<Media3Stage>): List<Media3FusedStage> {
    val gamma = stages.lastOrNull() as? Media3Stage.Gamma ?: return stages.map { Media3FusedStage(it) }
    val previous = stages.getOrNull(stages.size - 2)
    val fusable = previous is Media3Stage.Denoise || previous is Media3Stage.Deband || previous is Media3Stage.Sharpen || previous is Media3Stage.Grain ||
        // Only the single-pass cubic and FSR shaders take the gamma; Lanczos is Media3's own effect,
        // the MPV kernels, RAVU and NIS are programs of their own.
        (previous is Media3Stage.Scale && previous.scaler in setOf("bicubic", "catmull_rom", "fsr"))
    if (!fusable) return stages.map { Media3FusedStage(it) }
    return stages.dropLast(2).map { Media3FusedStage(it) } + Media3FusedStage(previous, gamma.value)
}
