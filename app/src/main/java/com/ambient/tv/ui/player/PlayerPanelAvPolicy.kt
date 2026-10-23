package com.ambient.tv.ui.player

/**
 * Which video rows the playback panel may show for the engine that is actually running.
 * A false flag means that control does nothing on this renderer, so the panel hides it.
 */
internal data class PlayerPanelVideoVisibility(
    val deinterlace: Boolean,
    val upscaler: Boolean,
    /** MPV user shaders. Built-in scalers can still apply when this is false. */
    val shaderUpscalers: Boolean,
    /** HQDN3D and the other SDR-only cleanup filters. */
    val denoise: Boolean = false,
    /** Sharpen shaders and other user-shader stages. Direct output leaves this false; HDR limits it (see [hdrShaders]). */
    val shaders: Boolean = false,
    /** SVP / motion interpolation this renderer can run. */
    val svp: Boolean = false,
    /** Brightness, contrast and saturation. SDR only. */
    val picture: Boolean = false,
    /** Gamma. Media3's effect chain has no gamma stage. */
    val pictureGamma: Boolean = false,
    /** Media3 can turn its SDR effect chain on. The scaler itself stays behind [upscaler]. */
    val media3Processing: Boolean = false,
    /** MPV deband runs on a GPU VO even for HDR. VLC deband needs software processing. */
    val deband: Boolean = false,
    /** Source is larger than the fitted output; a downscaler can run. */
    val downscaler: Boolean = false,
    /** Actual HDR input is converted to SDR by the renderer. */
    val toneMapping: Boolean = false,
    /**
     * MPV GPU VO with a confirmed HDR source: [shaders] and [shaderUpscalers] only offer the
     * HDR-safe subset (sharpening, grain, RAVU Lite / FSR as experimental), see hdrSafe.
     */
    val hdrShaders: Boolean = false,
    /** [hdrShaders] with PQ reaching the display (HDR über GPU): film grain is left out there. */
    val hdrPassthrough: Boolean = false,
)

/**
 * Sparsam (reduced playback): the renderers ignore these filters (see effective*ImageOptions),
 * so their rows are hidden instead of offering settings without effect. Deinterlace stays
 * (only 2x variants are downgraded); MPV/VLC picture controls stay; Media3's effect chain is off.
 */
internal fun PlayerPanelVideoVisibility.sparsam(reduced: Boolean, media3: Boolean = false): PlayerPanelVideoVisibility =
    if (!reduced) this else copy(
        shaderUpscalers = false, denoise = false, shaders = false, svp = false, deband = false, hdrShaders = false, hdrPassthrough = false,
        media3Processing = media3Processing && !media3,
        picture = picture && !media3, pictureGamma = pictureGamma && !media3,
        upscaler = upscaler && !media3,
    )

internal enum class VideoScaleRelation { UNKNOWN, UPSCALE, NATIVE, DOWNSCALE }

/** Unknown geometry stays configurable; known native output has no spatial scaling stage. */
internal fun videoScaleRelation(sourceWidth: Int?, sourceHeight: Int?, outputWidth: Int?, outputHeight: Int?): VideoScaleRelation {
    if (sourceWidth == null || sourceHeight == null || outputWidth == null || outputHeight == null ||
        sourceWidth <= 0 || sourceHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) return VideoScaleRelation.UNKNOWN
    val fitted = minOf(outputWidth.toDouble() / sourceWidth, outputHeight.toDouble() / sourceHeight)
    return when {
        fitted > 1.01 -> VideoScaleRelation.UPSCALE
        fitted < 0.99 -> VideoScaleRelation.DOWNSCALE
        else -> VideoScaleRelation.NATIVE
    }
}

/** Which live audio rows the playback panel may show. */
internal data class PlayerPanelAudioVisibility(
    val night: Boolean,
    val bitstream: Boolean,
    val delay: Boolean = true,
    /** Channel mix. VLC does not apply it, and passthrough ignores it. */
    val channels: Boolean = true,
    /** Equalizer and the other PCM DSP. Hidden while encoded output suspends DSP. */
    val equalizer: Boolean = true,
)

/**
 * Groups follow the renderer that is actually presenting the frame.
 * MPV direct output does not run the vf chain or user shaders. On a GPU VO an HDR source
 * keeps the built-in scaler and the HDR-safe shader subset ([PlayerPanelVideoVisibility.hdrShaders]);
 * denoise/deblock, picture controls and SVP stay SDR-only. Media3's effect chain is SDR-only.
 */
internal fun playerPanelVideoVisibility(
    engine: String,
    gpuFilters: Boolean,
    shadersApply: Boolean,
    media3ScalerApplies: Boolean,
    vlcSoftware: Boolean = false,
    media3SdrAllowed: Boolean = false,
    /** False with the Maven libVLC fallback, which has no ambientsvp filter. */
    vlcSvp: Boolean = true,
    vlcScalerApplies: Boolean = true,
    scale: VideoScaleRelation = VideoScaleRelation.UNKNOWN,
    hdrInput: Boolean = false,
    hdrOutput: Boolean = false,
    /**
     * MPV: the player really runs "HDR über GPU" (PQ reaches OUTPUT hooks). Decides passthrough
     * vs. tone mapping instead of [hdrOutput], which also covers HDR shown on other routes.
     */
    hdrGpuPassthrough: Boolean? = null,
    /** Global SVP mode is not off; presets alone never bring the SVP row back. */
    globalSvp: Boolean = true,
): PlayerPanelVideoVisibility = when (engine) {
    "mpv" -> PlayerPanelVideoVisibility(
        deinterlace = gpuFilters,
        upscaler = gpuFilters && scale in setOf(VideoScaleRelation.UNKNOWN, VideoScaleRelation.UPSCALE),
        shaderUpscalers = gpuFilters && (shadersApply || hdrInput) && scale in setOf(VideoScaleRelation.UNKNOWN, VideoScaleRelation.UPSCALE),
        denoise = gpuFilters && shadersApply,
        shaders = gpuFilters && (shadersApply || hdrInput),
        hdrShaders = gpuFilters && hdrInput && !shadersApply,
        hdrPassthrough = gpuFilters && hdrInput && (hdrGpuPassthrough ?: hdrOutput) && !shadersApply,
        svp = gpuFilters && shadersApply && globalSvp,
        picture = gpuFilters && shadersApply,
        pictureGamma = gpuFilters && shadersApply,
        deband = gpuFilters,
        downscaler = gpuFilters && scale in setOf(VideoScaleRelation.UNKNOWN, VideoScaleRelation.DOWNSCALE),
        toneMapping = gpuFilters && hdrInput && !(hdrGpuPassthrough ?: hdrOutput),
    )
    "vlc" -> PlayerPanelVideoVisibility(
        deinterlace = !hdrInput,
        upscaler = !hdrInput && vlcScalerApplies && scale in setOf(VideoScaleRelation.UNKNOWN, VideoScaleRelation.UPSCALE),
        shaderUpscalers = false,
        denoise = vlcSoftware && !hdrInput,
        shaders = false,
        svp = vlcSvp && !hdrInput && globalSvp,
        picture = vlcSoftware && !hdrInput,
        pictureGamma = vlcSoftware && !hdrInput,
        deband = vlcSoftware && !hdrInput,
    )
    "media3" -> PlayerPanelVideoVisibility(
        deinterlace = false,
        upscaler = media3ScalerApplies && scale in setOf(VideoScaleRelation.UNKNOWN, VideoScaleRelation.UPSCALE),
        shaderUpscalers = false,
        denoise = false,
        shaders = false,
        svp = false,
        picture = media3ScalerApplies,
        pictureGamma = false,
        media3Processing = media3SdrAllowed,
    )
    else -> PlayerPanelVideoVisibility(deinterlace = false, upscaler = false, shaderUpscalers = false)
}

/** Night mode follows the engine that actually applies it. Bitstream follows the probed route. */
internal fun playerPanelAudioVisibility(
    supportsNight: Boolean,
    routeOffersBitstream: Boolean,
    engine: String = "mpv",
    dspSuspended: Boolean = false,
) = PlayerPanelAudioVisibility(
    night = supportsNight && !dspSuspended,
    bitstream = routeOffersBitstream,
    delay = true,
    channels = true, // Selecting Stereo must also be possible while passthrough is active.
    equalizer = !dspSuspended,
)

/** A phone speaker (or any route that advertises nothing, or only rejected codecs) cannot bitstream. */
internal fun routeOffersBitstream(passthroughCodecs: Set<String>, rejectedCodecs: Set<String>): Boolean =
    passthroughCodecs.any { it !in rejectedCodecs }

/**
 * When HDR blocks MPV shaders, the GPU scaler falls back to Lanczos. Show that applied key
 * instead of a shader name that is not running. The stored preference is left unchanged.
 */
internal fun panelUpscalerKey(
    stored: String,
    shaderKeys: Set<String>,
    shadersApply: Boolean,
    /** Shader upscalers that still run on this (HDR) source. */
    stillRunning: Set<String> = emptySet(),
): String = if (!shadersApply && stored in shaderKeys && stored !in stillRunning) "lanczos" else stored

/** Upscaler shaders the panel offers: all for SDR, the HDR-safe subset for an HDR source. */
internal fun panelShaderUpscalers(all: Map<String, String>, hdr: Boolean, hdrSafe: Set<String>): Map<String, String> =
    if (!hdr) all else all.filterKeys { it in hdrSafe }

/** True when the filters group has at least one control this renderer will run. */
internal fun PlayerPanelVideoVisibility.hasFilters(svpAllowed: Boolean): Boolean =
    deinterlace || denoise || shaders || deband || (svp && svpAllowed)
