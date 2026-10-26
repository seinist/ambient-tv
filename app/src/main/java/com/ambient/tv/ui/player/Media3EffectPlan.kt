package com.ambient.tv.ui.player

import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.data.local.VideoImageCatalog

/** One stage of Media3's SDR effect chain, independent of GL so the order stays testable. */
internal sealed interface Media3Stage {
    data class Deband(val strength: String) : Media3Stage
    /** Only planned while the fitted output is larger than the source; never past the display. */
    data class Scale(val scaler: String) : Media3Stage
    /** light/medium unsharp mask, cas, or rcas (FSR's own sharpening pass). */
    data class Sharpen(val kind: String) : Media3Stage
    data class Brightness(val value: Int) : Media3Stage
    data class Contrast(val value: Int) : Media3Stage
    data class Saturation(val value: Int) : Media3Stage
    data class Gamma(val value: Int) : Media3Stage
}

/**
 * Order: deband (source pixels) → scale → sharpen → color. Bilinear means no scale stage: the
 * display path scales as with direct output. FSR includes RCAS, so the sharpening choice is not
 * stacked on top while FSR runs.
 */
internal fun media3EffectPlan(options: Media3ImageOptions, upscaling: Boolean): List<Media3Stage> {
    val o = options.normalized()
    if (!o.enabled) return emptyList()
    val scaler = o.scaler.takeIf { upscaling && it != "bilinear" }
    return buildList {
        if (o.deband != "off") add(Media3Stage.Deband(o.deband))
        scaler?.let { add(Media3Stage.Scale(it)) }
        when {
            scaler == "fsr" -> add(Media3Stage.Sharpen("rcas"))
            o.sharpening != "off" -> add(Media3Stage.Sharpen(o.sharpening))
        }
        if (o.brightness != 0) add(Media3Stage.Brightness(o.brightness))
        if (o.contrast != 0) add(Media3Stage.Contrast(o.contrast))
        if (o.saturation != 0) add(Media3Stage.Saturation(o.saturation))
        if (o.gamma != 0) add(Media3Stage.Gamma(o.gamma))
    }
}

/** True when FSR replaces the sharpening row (it would otherwise sharpen twice). */
internal fun media3FsrSharpens(options: Media3ImageOptions, upscalerApplies: Boolean): Boolean =
    upscalerApplies && options.normalized().scaler == "fsr"

internal fun media3ScalerLabel(key: String): String = VideoImageCatalog.media3Scalers[key] ?: key

internal fun media3ScalerChoices(): List<Pair<String, String>> = VideoImageCatalog.media3Scalers.toList()
