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
    val sharpening = setOf("off", "light", "medium", "cas", "adaptive")
    val toneMapping = setOf("auto", "bt.2390", "reinhard", "hable", "mobius", "clip")
    val svpModes = setOf("off", "auto", "light", "balanced", "quality")
}

/** Zero selects the preset value; overrides are shared by both native players. */
@Serializable
data class SvpOptions(
    val rateDivisor: Int = 1,
    val artifacts: Int = 0,
    val scene: Int = 0,
    val algorithm: Int = 0,
    val block: Int = 0,
    val overlap: Int = 0,
    val pel: Int = 0,
    val search: Int = 0,
    val backend: Int = 0,
    val threads: Int = 0,
) {
    fun normalized() = copy(
        rateDivisor = rateDivisor.takeIf { it in 1..2 } ?: 1,
        artifacts = artifacts.takeIf { it in 0..3 } ?: 0,
        scene = scene.takeIf { it in 0..3 } ?: 0,
        algorithm = algorithm.takeIf { it in setOf(0, 13, 21, 23) } ?: 0,
        block = block.takeIf { it in setOf(0, 8, 16, 32) } ?: 0,
        // 1/8 of an 8 px block is odd; CPU synthesis (also Vulkan's fallback) needs even overlap.
        overlap = if (block == 8 && overlap == 2) 3 else overlap.takeIf { it in 0..4 } ?: 0,
        pel = pel.takeIf { it in setOf(0, 1, 2, 4) } ?: 0,
        search = search.takeIf { it in 0..3 } ?: 0,
        backend = backend.takeIf { it in 0..2 } ?: 0,
        threads = threads.takeIf { it in 0..8 } ?: 0,
    )
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
            denoise = denoise.takeIf { it in setOf("off", "light", "medium") } ?: "off",
            deblock = deblock.takeIf { it in setOf("off", "light", "medium") } ?: "off",
            superResolution = superResolution && safeScaler !in setOf("fsr", "nis"),
            antiring = antiring.coerceIn(0, 100), brightness = brightness.coerceIn(-20, 20),
            contrast = contrast.coerceIn(-20, 20), saturation = saturation.coerceIn(-20, 20), gamma = gamma.coerceIn(-20, 20),
            toneMapping = toneMapping.takeIf { it in VideoImageCatalog.toneMapping } ?: "auto",
            ditherDepth = ditherDepth.takeIf { it in setOf("auto", "no", "8", "10") } ?: "auto",
            svp = svp.normalized(),
            svpMode = svpMode.takeIf { it in VideoImageCatalog.svpModes } ?: "off",
            filmGrain = filmGrain.takeIf { it in setOf("off", "light", "medium") } ?: "off",
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
        svpMode = svpMode.takeIf { it in VideoImageCatalog.svpModes } ?: "off",
        scaler = scaler.takeIf { it in VideoImageCatalog.vlcScalers } ?: "bilinear",
        deband = deband.takeIf { it in setOf("off", "light", "medium") } ?: "off",
        denoise = denoise.takeIf { it in setOf("off", "light", "medium") } ?: "off",
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
) {
    fun normalized() = copy(
        scaler = scaler.takeIf { it in setOf("bilinear", "lanczos") } ?: "bilinear",
        brightness = brightness.coerceIn(-20, 20), contrast = contrast.coerceIn(-20, 20),
        saturation = saturation.coerceIn(-20, 20),
    )
}
