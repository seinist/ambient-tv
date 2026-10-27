package com.ambient.tv.data.local

import kotlinx.serialization.Serializable

/** Stable, validated persistence keys shared by settings, player controls and renderer adapters. */
object VideoImageCatalog {
    val mpvBuiltins = linkedMapOf(
        "bilinear" to "Bilinear", "bicubic" to "Bicubic", "catmull_rom" to "Catmull-Rom",
        "mitchell" to "Mitchell", "hermite" to "Hermite", "robidoux" to "Robidoux",
        "robidouxsharp" to "RobidouxSharp", "spline16" to "Spline16", "spline36" to "Spline36",
        "spline64" to "Spline64", "lanczos" to "Lanczos", "sinc" to "Sinc",
        "ginseng" to "Ginseng", "gaussian" to "Gaussian", "ewa_lanczos" to "EWA Lanczos",
        "ewa_lanczossharp" to "EWA LanczosSharp", "ewa_lanczos4sharpest" to "EWA Lanczos4Sharpest",
        "ewa_lanczossoft" to "EWA LanczosSoft", "ewa_robidoux" to "EWA Robidoux",
        "ewa_robidouxsharp" to "EWA RobidouxSharp", "nearest" to "Nearest", "oversample" to "Oversample",
    )
    val mpvShaders = linkedMapOf<String, String>().apply {
        Nnedi3QualityCatalog.presets.forEach { preset -> put(preset.key, preset.displayName) }
        putAll(linkedMapOf(
        "ravu_lite_r2" to "RAVU Lite AR · r2", "ravu_lite_r3" to "RAVU Lite AR · r3",
        "ravu_zoom_r3" to "RAVU Zoom AR · r3", "ravu_lite_r4" to "RAVU Lite AR · r4",
        "fsrcnnx8" to "FSRCNNX · 8", "fsrcnnx16" to "FSRCNNX · 16",
        "artcnn16" to "ArtCNN · C4F16", "artcnn32" to "ArtCNN · C4F32",
        "artcnn16_ds" to "ArtCNN · C4F16 · DS", "artcnn16_dn" to "ArtCNN · C4F16 · DN",
        "artcnn32_ds" to "ArtCNN · C4F32 · DS", "artcnn32_dn" to "ArtCNN · C4F32 · DN",
        "anime4k" to "Anime4K · CNN x2 S", "anime4k_denoise" to "Anime4K · Denoise CNN x2 S",
        "anime4k_m" to "Anime4K · CNN x2 M", "anime4k_denoise_m" to "Anime4K · Denoise CNN x2 M",
        "cunny_fast_ds" to "CuNNy · Fast DS",
        "cunny_faster_ds" to "CuNNy · Faster DS",
        "cunny_fast_soft" to "CuNNy · Fast SOFT",
        "cunny_faster_soft" to "CuNNy · Faster SOFT",
        "cunny_veryfast_soft" to "CuNNy · Veryfast SOFT",
        "cunny_fast_soft_q" to "CuNNy · Fast SOFT · dp4a (Vulkan)",
        "cunny_faster_soft_q" to "CuNNy · Faster SOFT · dp4a (Vulkan)",
        "cunny_veryfast_soft_q" to "CuNNy · Veryfast SOFT · dp4a (Vulkan)",
        "nis" to "NVIDIA NIS · NVScaler",
        "fsr" to "FSR · EASU + RCAS",
        ))
    }
    val mpvScalers = mpvBuiltins + mpvShaders
    /** Chroma scalers that are GLSL hooks; cscale stays on its built-in fallback while they run. */
    val chromaShaders = linkedMapOf(
        "krig" to "KrigBilateral",
        "cfl_lite" to "CfL Prediction · Lite",
        "cfl" to "CfL Prediction",
    )
    val chromaScalers = mpvBuiltins.filterKeys { it != "oversample" } + chromaShaders
    val downscalers = mpvBuiltins.filterKeys { it !in setOf("oversample", "ewa_lanczos4sharpest") } + ("ssim_down" to "SSimDownscaler")
    val vlcScalers = linkedMapOf(
        "fast_bilinear" to "Fast bilinear", "bilinear" to "Bilinear", "bicubic" to "Bicubic",
        "nearest" to "Nearest", "area" to "Area", "bicublin" to "Bicublin",
        "gaussian" to "Gaussian", "sinc" to "Sinc", "lanczos" to "Lanczos", "spline" to "Spline",
    )
    val strengths = setOf("off", "light", "medium", "strong")
    val sharpening = setOf("off", "light", "medium", "strong", "cas", "adaptive")
    /**
     * Media3 GPU upscalers, defined once for the panel, settings overview and Stats for Nerds.
     * Bicubic and Catmull-Rom use mpv's B/C values; FSR brings its own RCAS sharpening.
     */
    val media3Scalers = linkedMapOf(
        "bilinear" to "Bilinear", "bicubic" to "Bicubic", "catmull_rom" to "Catmull-Rom",
        "lanczos" to "Lanczos · 3", "fsr" to "FSR · EASU + RCAS",
        // MPV's other built-in kernels, sampled like MPV does (Media3KernelScalers).
        "mitchell" to "Mitchell", "hermite" to "Hermite", "robidoux" to "Robidoux",
        "robidouxsharp" to "RobidouxSharp", "spline16" to "Spline16", "spline36" to "Spline36",
        "spline64" to "Spline64", "sinc" to "Sinc", "ginseng" to "Ginseng", "gaussian" to "Gaussian",
        "ewa_lanczos" to "EWA Lanczos", "ewa_lanczossharp" to "EWA LanczosSharp",
        "ewa_lanczos4sharpest" to "EWA Lanczos4Sharpest", "ewa_lanczossoft" to "EWA LanczosSoft",
        "ewa_robidoux" to "EWA Robidoux", "ewa_robidouxsharp" to "EWA RobidouxSharp",
        "nearest" to "Nearest", "oversample" to "Oversample",
        // MPV's RAVU shader files, translated for Media3 at runtime (Media3RavuEffect).
        "ravu_lite_r2" to "RAVU Lite AR · r2", "ravu_lite_r3" to "RAVU Lite AR · r3", "ravu_lite_r4" to "RAVU Lite AR · r4",
        "ravu_zoom_r3" to "RAVU Zoom AR · r3",
        // NVScaler ported from MPV's compute shader to a fragment shader (Media3NisEffect).
        "nis" to "NVIDIA NIS · NVScaler",
    )
    /** light/medium/strong: unsharp mask; cas: AMD Contrast Adaptive Sharpening; adaptive: bacondither's Adaptive Sharpen. */
    val media3Sharpening = setOf("off", "light", "medium", "strong", "cas", "adaptive")
    val media3Deband = strengths
    val toneMapping = setOf("auto", "bt.2390", "reinhard", "hable", "mobius", "clip")
    val svpModes = setOf("off", "light", "balanced", "quality")
}

/**
 * SVP tuning. Each native player (MPV, VLC) keeps its own copy inside its image options.
 * Zero in [algorithm], [block], [overlap] and [pel] follows the selected preset; any other
 * value there or in [search] makes the setup "custom" ([isCustom]).
 */
@Serializable
data class SvpOptions(
    /** 1 display refresh rate, 2 half of it, 3 at most 60 fps (smallest integer divisor of the display rate). */
    val rateDivisor: Int = 1,
    /** 0 off, 2 medium, 3 strong. The old value 1 ("off" with an explicit cover mask) reads as 0. */
    val artifacts: Int = 0,
    /** 1 sensitive, 2 normal, 3 relaxed. The old preset value 0 was always normal. */
    val scene: Int = 2,
    val algorithm: Int = 0,
    val block: Int = 0,
    val overlap: Int = 0,
    val pel: Int = 0,
    /** 0 SVP's standard search, 1..3 light/medium/strong. */
    val search: Int = 0,
    val backend: Int = 0,
    val threads: Int = 0,
    /** Largest source height that is interpolated: 720, 1080 or 2160. */
    val maxHeight: Int = 1080,
    /** Interpolate only sources up to 30 fps (film and TV), not 50/60 fps video. */
    val filmOnly: Boolean = true,
) {
    fun normalized() = copy(
        rateDivisor = rateDivisor.takeIf { it in 1..3 } ?: 1,
        artifacts = artifacts.takeIf { it in setOf(2, 3) } ?: 0,
        scene = scene.takeIf { it in 1..3 } ?: 2,
        algorithm = algorithm.takeIf { it in setOf(0, 13, 21, 23) } ?: 0,
        block = block.takeIf { it in setOf(0, 8, 16, 32) } ?: 0,
        // 1/8 of an 8 px block is odd; CPU synthesis (also Vulkan's fallback) needs even overlap.
        overlap = if (block == 8 && overlap == 2) 3 else overlap.takeIf { it in 0..4 } ?: 0,
        pel = pel.takeIf { it in setOf(0, 1, 2, 4) } ?: 0,
        search = search.takeIf { it in 0..3 } ?: 0,
        backend = backend.takeIf { it in 0..2 } ?: 0,
        threads = threads.takeIf { it in 0..8 } ?: 0,
        maxHeight = maxHeight.takeIf { it in SVP_MAX_HEIGHTS } ?: 1080,
    )

    /** Analysis values that differ from the preset: the UI shows "custom". */
    val isCustom: Boolean get() = normalized().let { it.algorithm != 0 || it.block != 0 || it.overlap != 0 || it.pel != 0 || it.search != 0 }

    /** Choosing a preset again returns the analysis to that preset; output and device choices stay. */
    fun withoutAnalysisOverrides() = copy(algorithm = 0, block = 0, overlap = 0, pel = 0, search = 0).normalized()

    companion object {
        val SVP_MAX_HEIGHTS = setOf(720, 1080, 2160)
    }
}

/** Stored "auto" was the light preset with a different name; it reads as light. */
internal fun normalizeSvpMode(mode: String): String = when (mode) {
    "auto" -> "light"
    in VideoImageCatalog.svpModes -> mode
    else -> "off"
}

@Serializable
data class MpvImageOptions(
    val scaler: String = "lanczos",
    val chromaScaler: String = "lanczos",
    val downscaler: String = "mitchell",
    val deband: String = "medium",
    val sharpening: String = "off",
    val denoise: String = "off",
    val deblock: String = "off",
    val superResolution: Boolean = false,
    val antiring: Int = 0,
    val sigmoid: Boolean = true,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val gamma: Int = 0,
    val toneMapping: String = "auto",
    val ditherDepth: String = "auto",
    /** Open-SVPFlow motion compensation; effective only in Maximum performance mode. */
    val svpMode: String = "off",
    val svp: SvpOptions = SvpOptions(),
    /** Synthetic film grain on luma, applied after scaling and sharpening. */
    val filmGrain: String = "off",
) {
    fun normalized(): MpvImageOptions {
        val safeScaler = scaler.takeIf { it in VideoImageCatalog.mpvScalers } ?: "lanczos"
        return copy(
            scaler = safeScaler,
            chromaScaler = chromaScaler.takeIf { it in VideoImageCatalog.chromaScalers } ?: "lanczos",
            downscaler = downscaler.takeIf { it in VideoImageCatalog.downscalers } ?: "mitchell",
            deband = deband.takeIf { it in VideoImageCatalog.strengths } ?: "off",
            sharpening = sharpening.takeIf { it in VideoImageCatalog.sharpening } ?: "off",
            denoise = denoise.takeIf { it in VideoImageCatalog.strengths } ?: "off",
            deblock = deblock.takeIf { it in VideoImageCatalog.strengths } ?: "off",
            superResolution = superResolution && safeScaler !in setOf("fsr", "nis"),
            antiring = antiring.coerceIn(0, 100), brightness = brightness.coerceIn(-20, 20),
            contrast = contrast.coerceIn(-20, 20), saturation = saturation.coerceIn(-20, 20), gamma = gamma.coerceIn(-20, 20),
            toneMapping = toneMapping.takeIf { it in VideoImageCatalog.toneMapping } ?: "auto",
            ditherDepth = ditherDepth.takeIf { it in setOf("auto", "no", "8", "10") } ?: "auto",
            svp = svp.normalized(),
            svpMode = normalizeSvpMode(svpMode),
            filmGrain = filmGrain.takeIf { it in VideoImageCatalog.strengths } ?: "off",
        )
    }
}

@Serializable
data class VlcImageOptions(
    val softwareProcessing: Boolean = false,
    val scaler: String = "bilinear",
    val deband: String = "off",
    val denoise: String = "off",
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val gamma: Int = 0,
    val svpMode: String = "off",
    val svp: SvpOptions = SvpOptions(),
) {
    fun normalized() = copy(
        svp = svp.normalized(),
        svpMode = normalizeSvpMode(svpMode),
        scaler = scaler.takeIf { it in VideoImageCatalog.vlcScalers } ?: "bilinear",
        deband = deband.takeIf { it in VideoImageCatalog.strengths } ?: "off",
        denoise = denoise.takeIf { it in VideoImageCatalog.strengths } ?: "off",
        brightness = brightness.coerceIn(-20, 20), contrast = contrast.coerceIn(-20, 20),
        saturation = saturation.coerceIn(-20, 20), gamma = gamma.coerceIn(-20, 20),
    )
}

@Serializable
data class Media3ImageOptions(
    val enabled: Boolean = false,
    val scaler: String = "bilinear",
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    /** Added later: profiles and backups without these fields read as off / 0. */
    val sharpening: String = "off",
    val deband: String = "off",
    val gamma: Int = 0,
    /** Synthetic film grain, after sharpening; older profiles read as off. */
    val filmGrain: String = "off",
    /** Edge-preserving GPU denoise on source pixels, before deband; older profiles read as off. */
    val denoise: String = "off",
) {
    fun normalized() = copy(
        scaler = scaler.takeIf { it in VideoImageCatalog.media3Scalers } ?: "bilinear",
        brightness = brightness.coerceIn(-20, 20), contrast = contrast.coerceIn(-20, 20),
        saturation = saturation.coerceIn(-20, 20),
        sharpening = sharpening.takeIf { it in VideoImageCatalog.media3Sharpening } ?: "off",
        deband = deband.takeIf { it in VideoImageCatalog.media3Deband } ?: "off",
        gamma = gamma.coerceIn(-20, 20),
        filmGrain = filmGrain.takeIf { it in VideoImageCatalog.strengths } ?: "off",
        denoise = denoise.takeIf { it in VideoImageCatalog.strengths } ?: "off",
    )
}
