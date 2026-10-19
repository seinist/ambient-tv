package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.VlcImageOptions
import com.ambient.tv.data.local.VideoImageCatalog
import com.ambient.tv.ui.player.HdrStandard

/** A second option change during rebuffering must retain the first restart's target. */
internal fun vlcRestartPosition(pending: Long?, current: Long, preserve: Boolean): Long =
    if (preserve) (pending ?: current).coerceAtLeast(0L) else 0L

internal fun isMpvSdrTransfer(transfer: String?): Boolean = transfer?.lowercase() in setOf(
    "bt.1886", "srgb", "gamma1.8", "gamma2.0", "gamma2.2", "gamma2.4", "gamma2.6", "gamma2.8", "linear",
)

/** Confirmed HDR transfers. Unknown and SDR stay false so picture controls are not hidden early. */
internal fun isMpvHdrTransfer(transfer: String?): Boolean {
    val value = transfer?.trim()?.lowercase() ?: return false
    return value in setOf("pq", "smpte2084", "st2084", "hlg", "arib-std-b67")
}

internal fun hdrStandardFromMpvTransfer(transfer: String?): HdrStandard? = when (transfer?.trim()?.lowercase()) {
    "pq", "smpte2084", "st2084" -> HdrStandard.HDR10
    "hlg", "arib-std-b67" -> HdrStandard.HLG
    else -> null
}

/**
 * LibVLC's swscale mode is skipped when Android surface output scales the picture.
 * Software processing actually runs the scaler, including on that surface.
 * Vulkan is not the surface bypass.
 */
internal fun vlcSurfaceBypassesSwscale(outputMode: String, softwareProcessing: Boolean): Boolean =
    !softwareProcessing && outputMode != "vulkan"

internal fun vlcSurfaceBypassesSwscale(androidSurface: Boolean, softwareProcessing: Boolean): Boolean =
    androidSurface && !softwareProcessing

/**
 * Fitted scale of the video on the output (the limiting axis). Unknown geometry is
 * infinite so the current selection stays loaded until real sizes arrive.
 */
internal fun mpvFittedScale(sourceWidth: Double, sourceHeight: Double, outputWidth: Int, outputHeight: Int): Double {
    if (sourceWidth <= 0.0 || sourceHeight <= 0.0 || outputWidth <= 0 || outputHeight <= 0) {
        return Double.POSITIVE_INFINITY
    }
    return minOf(outputWidth / sourceWidth, outputHeight / sourceHeight)
}

/** Below this, enlargement is treated as 1:1. Matches the previous luma gate. */
internal const val MPV_ANY_UPSCALE_SCALE = 1.01

/**
 * Whether the fitted video is enlarged on the output surface. Unknown geometry keeps the
 * selection. This is the gate for enlargement-only helpers such as SSimSuperRes.
 * Luma upscalers use [mpvLumaUpscalerMinScale]: their //!WHEN can sit well above 1.01,
 * and a loaded-but-skipped hook still forces MediaCodec copy-back.
 */
internal fun mpvUpscaling(sourceWidth: Double, sourceHeight: Double, outputWidth: Int, outputHeight: Int): Boolean =
    mpvFittedScale(sourceWidth, sourceHeight, outputWidth, outputHeight) > MPV_ANY_UPSCALE_SCALE

/**
 * Lowest fitted scale at which this luma upscaler's bundled //!WHEN can pass.
 * NNEDI and RAVU Lite test `HOOKED/OUTPUT < 0.707106` (about √2, 1.41×) and only
 * on the axes they name. Anime4K requires both axes above 1.2. ArtCNN, FSRCNN and
 * CuNNy require both axes above 1.3. FSR, NIS and RAVU Zoom run on any enlargement.
 */
internal fun mpvLumaUpscalerMinScale(scaler: String): Double = when {
    scaler.startsWith("nnedi") || scaler.startsWith("ravu_lite") -> 1.0 / 0.707106
    scaler.startsWith("anime4k") -> 1.2
    scaler.startsWith("artcnn") || scaler.startsWith("fsrcnn") || scaler.startsWith("cunny") -> 1.3
    else -> MPV_ANY_UPSCALE_SCALE
}

internal fun mpvLumaUpscalerWouldRun(scaler: String, fittedScale: Double): Boolean =
    fittedScale > mpvLumaUpscalerMinScale(scaler)


/**
 * More than 10% dropped frames during a steady playback window means rendering cannot keep up.
 * SVP can also be too heavy before that ratio is reached: a plex-svp bypass_reason other than
 * the disabled gate (2), or analysis+synthesis above ambientsvp's per-second budget.
 * Extra arguments default so the existing VLC lost-picture call stays unchanged.
 */
internal fun mpvRenderOverloaded(
    droppedFrames: Long,
    fps: Double,
    seconds: Double,
    svpBypassReason: Int = 0,
    svpNativeCostUs: Long = 0L,
): Boolean {
    val drops = fps > 0.0 && seconds > 0.0 && droppedFrames > fps * seconds * 0.1
    return drops || mpvSvpNativeOverloaded(svpBypassReason, svpNativeCostUs, seconds)
}

/** Offer a session-only reduction for an actually running sharpening shader. */
internal fun mpvSharpeningAfterOverload(sharpening: String, loadedShaders: List<String>): String? = when {
    // Built-in Light/Medium running as CAS on gpu-next.
    sharpening in setOf("light", "medium") && "cas_$sharpening" in loadedShaders -> "off"
    sharpening !in loadedShaders -> null
    sharpening == "adaptive" -> "cas"
    sharpening == "cas" -> "off"
    else -> null
}

/** ambientsvp bypasses after a source-second costs more than 850ms of analysis+synthesis. */
internal const val MPV_SVP_SLOW_WINDOW_US = 850_000L

/**
 * Only the engine's compute bypass (1) is an overload. The runtime gate (2), engine errors (3)
 * and the adapter's passthrough reasons (format, timing, unavailable backend) are reported as
 * status, never as "too slow". Cost is ignored until a sample exists.
 */
internal fun mpvSvpNativeOverloaded(bypassReason: Int, nativeCostUs: Long, seconds: Double): Boolean {
    if (bypassReason == SvpNativeReason.BYPASS_OVERLOAD) return true
    return seconds > 0.0 && nativeCostUs > (seconds * MPV_SVP_SLOW_WINDOW_US.toDouble()).toLong()
}

/**
 * SVP, NNEDI deinterlace, and a loaded heavy luma upscaler decide after about
 * 1s of steady playback plus a 2s sample. Other processing keeps 3s + 6s.
 */
internal fun mpvRenderWarmupSeconds(fastSample: Boolean): Int = if (fastSample) 1 else 3

internal fun mpvRenderSampleSeconds(fastSample: Boolean, configuredSeconds: Int = 0): Double =
    if (configuredSeconds in 2..60) configuredSeconds.toDouble() else if (fastSample) 2.0 else 6.0

/**
 * Quality (preset 3) is not applied until a lighter preset has survived a short probe.
 * Light is the probe: it does not use quality's heavier analysis, so the heaviest mode
 * cannot run unbounded before the first check. A failed probe stays on light.
 */
internal const val MPV_SVP_PROBE_MODE = "light"

internal fun mpvSvpAppliedMode(requested: String, qualityCleared: Boolean): String =
    if (requested == "quality" && !qualityCleared) MPV_SVP_PROBE_MODE else requested

/**
 * Quality did not hold. Live options switch to the lighter preset that is still
 * running. Saved settings take that same mode when they still say quality, or
 * when the user is saving image defaults. Otherwise the stored preset is left
 * alone so a session-only trial cannot overwrite a different saved choice.
 */
internal data class MpvSvpLevelUpdate(
    val live: MpvImageOptions,
    val stored: MpvImageOptions,
    val persisted: Boolean,
)

internal fun mpvSvpLevelAfterHeavyFailure(
    live: MpvImageOptions,
    stored: MpvImageOptions,
    survivedMode: String = MPV_SVP_PROBE_MODE,
    saveImageDefaults: Boolean = false,
): MpvSvpLevelUpdate? {
    val current = live.normalized()
    if (current.svpMode != "quality") return null
    if (survivedMode !in setOf("light", "balanced")) return null
    val nextLive = current.copy(svpMode = survivedMode, svp = svpProbeOptions(current.svp)).normalized()
    val saved = stored.normalized()
    val nextStored = if (saveImageDefaults) nextLive else saved
    return MpvSvpLevelUpdate(nextLive, nextStored, saveImageDefaults && nextStored != saved)
}

/** Neural / CNN luma scalers. Built-in mpv scalers, chroma, and sharpen shaders are not included. */
internal fun mpvHeavyLumaUpscaler(scaler: String): Boolean = scaler in VideoImageCatalog.mpvShaders

internal fun mpvHeavyScalerLoaded(scaler: String, loadedShaders: Collection<String>): Boolean =
    mpvHeavyLumaUpscaler(scaler) && scaler in loadedShaders

internal fun mpvNnediDeinterlacer(filter: String): Boolean = filter == "nnedi" || filter == "nnedi2x"

/** Short watchdog while NNEDI deinterlace or a loaded heavy luma upscaler is actually running. */
internal fun mpvFastRenderSample(
    deinterlaceEnabled: Boolean,
    deinterlaceFilter: String,
    heavyScalerLoaded: Boolean,
): Boolean = (deinterlaceEnabled && mpvNnediDeinterlacer(deinterlaceFilter)) || heavyScalerLoaded

internal const val MPV_DEINTERLACE_FALLBACK = "yadif"
internal const val MPV_UPSCALER_FALLBACK = "lanczos"

/**
 * Copy-back follows the work that still needs readable frames.
 * Removing a luma shader must not leave copy-back on by itself.
 * SVP's session latch is applied by the caller, not here.
 */
internal fun mpvCopyBackNeeded(
    shaderKeys: Collection<String>,
    cpuFilters: Boolean,
    deinterlaceEnabled: Boolean,
): Boolean = deinterlaceEnabled || cpuFilters ||
    shaderKeys.any { MPV_IMAGE_SHADERS[it]?.requiresCopyBack == true }

internal data class MpvDeinterlaceBackoff(
    val mode: String,
    val filter: String,
    val storedMode: String,
    val storedFilter: String,
    val persisted: Boolean,
    val turnedOff: Boolean,
)

internal data class MpvUpscalerBackoff(
    val live: MpvImageOptions,
    val stored: MpvImageOptions,
    val persisted: Boolean,
)

internal sealed class MpvHeavyBackoff {
    data class Deinterlace(val update: MpvDeinterlaceBackoff) : MpvHeavyBackoff()
    data class Upscaler(val update: MpvUpscalerBackoff) : MpvHeavyBackoff()
}

/**
 * One step. NNEDI / NNEDI2x becomes Yadif. Yadif reached by that step becomes
 * off only when no heavy upscaler is still running. A heavy luma shader becomes
 * Lanczos. Unrelated image fields stay. A session-only trial does not replace
 * a different saved preset unless image defaults are being saved, and even then
 * only the scaler field is written.
 */
internal fun mpvHeavyProcessingBackoff(
    deinterlaceEnabled: Boolean,
    deinterlaceMode: String,
    deinterlaceFilter: String,
    storedDeinterlaceMode: String,
    storedDeinterlaceFilter: String,
    steppedFromNnedi: Boolean,
    allowDeinterlacePersist: Boolean,
    liveImage: MpvImageOptions,
    storedImage: MpvImageOptions,
    heavyScalerLoaded: Boolean,
    saveImageDefaults: Boolean,
): MpvHeavyBackoff? {
    if (deinterlaceEnabled && mpvNnediDeinterlacer(deinterlaceFilter)) {
        return MpvHeavyBackoff.Deinterlace(
            mpvDeinterlaceAfterOverload(
                liveMode = deinterlaceMode,
                liveFilter = deinterlaceFilter,
                storedMode = storedDeinterlaceMode,
                storedFilter = storedDeinterlaceFilter,
                step = MpvDeinterlaceStep.YADIF,
                allowPersist = allowDeinterlacePersist,
            ),
        )
    }
    if (heavyScalerLoaded) {
        val update = mpvUpscalerAfterHeavyFailure(liveImage, storedImage, saveImageDefaults) ?: return null
        return MpvHeavyBackoff.Upscaler(update)
    }
    if (deinterlaceEnabled && deinterlaceFilter == MPV_DEINTERLACE_FALLBACK && steppedFromNnedi) {
        return MpvHeavyBackoff.Deinterlace(
            mpvDeinterlaceAfterOverload(
                liveMode = deinterlaceMode,
                liveFilter = deinterlaceFilter,
                storedMode = storedDeinterlaceMode,
                storedFilter = storedDeinterlaceFilter,
                step = MpvDeinterlaceStep.OFF,
                allowPersist = allowDeinterlacePersist,
            ),
        )
    }
    return null
}

internal enum class MpvDeinterlaceStep { YADIF, OFF }

internal fun mpvDeinterlaceAfterOverload(
    liveMode: String,
    liveFilter: String,
    storedMode: String,
    storedFilter: String,
    step: MpvDeinterlaceStep,
    allowPersist: Boolean,
): MpvDeinterlaceBackoff {
    val nextMode = if (step == MpvDeinterlaceStep.OFF) "off" else liveMode
    val nextFilter = MPV_DEINTERLACE_FALLBACK
    val same = allowPersist && storedMode == liveMode && storedFilter == liveFilter
    val nextStoredMode = if (same) nextMode else storedMode
    val nextStoredFilter = if (same) nextFilter else storedFilter
    return MpvDeinterlaceBackoff(
        mode = nextMode,
        filter = nextFilter,
        storedMode = nextStoredMode,
        storedFilter = nextStoredFilter,
        persisted = same && (nextStoredMode != storedMode || nextStoredFilter != storedFilter),
        turnedOff = step == MpvDeinterlaceStep.OFF,
    )
}

internal fun mpvUpscalerAfterHeavyFailure(
    live: MpvImageOptions,
    stored: MpvImageOptions,
    saveImageDefaults: Boolean = false,
): MpvUpscalerBackoff? {
    val current = live.normalized()
    if (!mpvHeavyLumaUpscaler(current.scaler)) return null
    val nextLive = current.copy(scaler = MPV_UPSCALER_FALLBACK).normalized()
    val saved = stored.normalized()
    val persist = saveImageDefaults
    val nextStored = if (persist) saved.copy(scaler = MPV_UPSCALER_FALLBACK).normalized() else saved
    return MpvUpscalerBackoff(nextLive, nextStored, persist && nextStored != saved)
}

/** LibVLC reports output drops as lost pictures; keep MPV's 10% safety threshold. */
internal fun vlcRenderOverloaded(lostPictures: Long, fps: Double, seconds: Double): Boolean =
    mpvRenderOverloaded(lostPictures, fps, seconds)

/** Processing which requires a fresh LibVLC lost-picture sample after a restart. */
internal fun vlcRenderCheckSignature(
    options: VlcImageOptions,
    deinterlaceMode: String,
    deinterlaceFilter: String,
): String? = buildList {
    val safe = options.normalized()
    if (safe.softwareProcessing) {
        add("software:${safe.scaler}:${safe.denoise}:${safe.deband}:${safe.brightness}:${safe.contrast}:${safe.saturation}:${safe.gamma}")
    }
    if (safe.svpMode != "off") add("svp:${safe.svpMode}")
    if (deinterlaceMode != "off") add("deinterlace:$deinterlaceMode:$deinterlaceFilter")
}.takeIf { it.isNotEmpty() }?.joinToString("|")

/**
 * Describes the processing which is actually active in the MPV render path.
 *
 * The controller uses this to restart its dropped-frame sample after a live
 * change. Include filter values, rather than only their labels: changing a
 * denoise preset or an SVP preset must receive a fresh measurement too.
 */
internal fun mpvRenderCheckSignature(
    shaderKeys: List<String>,
    cpuFilters: Map<String, String>,
    deinterlaceEnabled: Boolean,
    automaticDeinterlace: Boolean,
    explicitDeinterlaceFilter: String?,
    options: MpvImageOptions,
): String? = buildList {
    shaderKeys.forEach { add("shader:$it") }
    cpuFilters.toSortedMap().forEach { (label, filter) -> add("filter:$label=$filter") }
    if (deinterlaceEnabled) add(
        "deinterlace:${explicitDeinterlaceFilter ?: if (automaticDeinterlace) "auto" else "builtin"}",
    )

    // These are MPV properties rather than entries in the vf/glsl lists, but
    // they can still add GPU work. Defaults retain the previous behaviour of
    // avoiding a watchdog for an otherwise unmodified Direct Play session.
    val safe = options.normalized()
    val defaults = MpvImageOptions()
    if (safe.deband != defaults.deband) add("deband:${safe.deband}")
    if (safe.filmGrain != defaults.filmGrain) add("grain:${safe.filmGrain}")
    if (safe.scaler != defaults.scaler) add("scale:${safe.scaler}")
    if (safe.chromaScaler != defaults.chromaScaler) add("cscale:${safe.chromaScaler}")
    if (safe.downscaler != defaults.downscaler) add("dscale:${safe.downscaler}")
    if (safe.sigmoid != defaults.sigmoid) add("sigmoid:${safe.sigmoid}")
    if (safe.antiring != defaults.antiring) add("antiring:${safe.antiring}")
    if (safe.toneMapping != defaults.toneMapping) add("tone-map:${safe.toneMapping}")
    if (safe.ditherDepth != defaults.ditherDepth) add("dither:${safe.ditherDepth}")
}.takeIf { it.isNotEmpty() }?.joinToString("|")

/**
 * @param fittedScale limiting-axis scale from [mpvFittedScale]. Null keeps the
 * historical boolean: [upscaling] true loads every selected luma hook.
 * @param svpActive plex-svp is in the vf chain. GLSL hooks run in the renderer,
 * after vf, so they cannot see source frames ahead of interpolation. Loading
 * one anyway would run NNEDI/RAVU/Anime4K on every interpolated frame. mpv has
 * no way to move glsl-shaders in front of vf; the luma upscaler and SSimSuperRes
 * stay unloaded and the built-in scaler remains lanczos for that session.
 */
internal fun mpvImageShaderKeys(
    options: MpvImageOptions,
    sdr: Boolean,
    upscaling: Boolean = true,
    fittedScale: Double? = null,
    svpActive: Boolean = false,
    /**
     * Confirmed PQ/HLG source on a GPU VO that tone-maps. Only [MpvShaderAsset.hdrSafe] hooks
     * load; grain moves to its OUTPUT variant. Unknown transfers (neither flag) load nothing.
     */
    hdr: Boolean = false,
    /** vo=gpu-next: mpv's built-in sharpen does nothing there, Light/Medium become CAS. */
    gpuNext: Boolean = false,
): List<String> = if (!sdr && !hdr) emptyList() else buildList {
    val o = options.normalized()
    val hdrSource = !sdr
    val scale = fittedScale ?: if (upscaling) Double.POSITIVE_INFINITY else 0.0
    fun allowed(key: String) = !hdrSource || MPV_IMAGE_SHADERS[key]?.hdrSafe == true
    if (o.chromaScaler in VideoImageCatalog.chromaShaders && allowed(o.chromaScaler)) add(o.chromaScaler)
    // A hook that will skip its own //!WHEN must not be loaded: most luma
    // upscalers force decoder copy-back for as long as the file is attached.
    if (!svpActive && o.scaler in VideoImageCatalog.mpvShaders && allowed(o.scaler) &&
        scale > mpvLumaUpscalerMinScale(o.scaler)) {
        add(o.scaler)
    }
    // FSR and NIS already contain a sharpening stage. Do not silently stack
    // SSimSuperRes on top of their internal edge enhancement. Like the luma
    // hook, it is a renderer upscaler and would run once per SVP output frame.
    if (!svpActive && scale > MPV_ANY_UPSCALE_SCALE && o.superResolution && o.scaler !in setOf("fsr", "nis") &&
        allowed("ssim_super")) {
        add("ssim_super")
    }
    if (o.downscaler == "ssim_down" && allowed("ssim_down")) add("ssim_down")
    // FSR contains RCAS and NIS/NVScaler contains its own adaptive sharpening.
    mpvSharpeningShader(o.sharpening, o.scaler, gpuNext)?.takeIf(::allowed)?.let(::add)
    // Grain goes last so scalers and sharpeners do not amplify it.
    if (o.filmGrain != "off") add(if (hdrSource) "filmgrain_${o.filmGrain}_output" else "filmgrain_${o.filmGrain}")
}

/**
 * GLSL hook for the sharpening choice, or null when none is loaded. Light/Medium are mpv's
 * built-in unsharp mask on vo=gpu (a property, no hook); on gpu-next that property has no
 * effect, so they map to CAS at a matching strength.
 */
internal fun mpvSharpeningShader(sharpening: String, scaler: String, gpuNext: Boolean): String? = when {
    scaler in setOf("fsr", "nis") -> null
    sharpening in setOf("cas", "adaptive") -> sharpening
    gpuNext && sharpening == "light" -> "cas_light"
    gpuNext && sharpening == "medium" -> "cas_medium"
    else -> null
}

/** mpv `sharpen` value: built-in unsharp mask, only on vo=gpu (gpu-next uses [mpvSharpeningShader]). */
internal fun mpvBuiltinSharpen(sharpening: String, scaler: String, processing: Boolean, gpuNext: Boolean): String =
    if (!processing || gpuNext || scaler in setOf("fsr", "nis")) "0" else when (sharpening) {
        "light" -> "0.2"
        "medium" -> "0.5"
        else -> "0"
    }

internal fun mpvCpuImageFilters(options: MpvImageOptions, sdr: Boolean): Map<String, String> = buildMap {
    if (!sdr) return@buildMap
    val o = options.normalized()
    when (o.denoise) {
        "light" -> put("plex-denoise", "lavfi=[hqdn3d=1.5:1.5:3:3]")
        "medium" -> put("plex-denoise", "lavfi=[hqdn3d=3:2:4.5:3]")
    }
    when (o.deblock) {
        "light" -> put("plex-deblock", "lavfi=[deblock=filter=weak:block=8]")
        "medium" -> put("plex-deblock", "lavfi=[deblock=filter=strong:block=8]")
    }
}

private val MPV_CPU_FILTER_ORDER = listOf("plex-denoise", "plex-deblock")

internal fun orderedMpvCpuImageFilters(filters: Map<String, String>): List<Pair<String, String>> =
    MPV_CPU_FILTER_ORDER.mapNotNull { label -> filters[label]?.let { label to it } } +
        filters.filterKeys { it !in MPV_CPU_FILTER_ORDER }.toList()

/** VLC's FFmpeg scaler is used only when conversion/resizing actually runs in swscale. */
internal fun vlcSwscaleMode(filterKey: String): Int = when (filterKey) {
    "fast_bilinear" -> 0
    "bilinear" -> 1
    "bicubic" -> 2
    "nearest" -> 4
    "area" -> 5
    "bicublin" -> 6
    "gaussian" -> 7
    "sinc" -> 8
    "lanczos" -> 9
    "spline" -> 10
    else -> 1
}

/** Only modules confirmed in the bundled libVLC 3.7.5 ARM runtime are exposed. */
internal fun vlcImageMediaOptions(options: VlcImageOptions, svpTargetFps: Double? = null): List<String> = buildList {
    val o = options.normalized()
    add(":swscale-mode=${vlcSwscaleMode(o.scaler)}")
    val filters = mutableListOf<String>()
    if (o.softwareProcessing && o.denoise != "off") {
        filters += "hqdn3d"
        val values = if (o.denoise == "light") listOf(1.5, 1.5, 3.0, 3.0) else listOf(3.0, 2.0, 4.5, 3.0)
        listOf("luma-spat", "chroma-spat", "luma-temp", "chroma-temp").zip(values).forEach { (key, value) -> add(":hqdn3d-$key=$value") }
    }
    if (o.softwareProcessing && o.deband != "off") {
        filters += "gradfun"
        add(":gradfun-radius=16")
        add(":gradfun-strength=${if (o.deband == "light") 1.2 else 2.0}")
    }
    if (o.softwareProcessing && (o.brightness != 0 || o.contrast != 0 || o.saturation != 0 || o.gamma != 0)) {
        filters += "adjust"
        add(":brightness=${1.0 + o.brightness / 100.0}")
        add(":contrast=${1.0 + o.contrast / 100.0}")
        add(":saturation=${1.0 + o.saturation / 100.0}")
        add(":gamma=${1.0 + o.gamma / 100.0}")
    }
    val target = svpTargetFps?.takeIf { it.isFinite() && it in 5.0..120.0 }
    if (o.svpMode != "off" && target != null) {
        filters += "ambientsvp{fps=$target,preset=${svpPreset(o.svpMode)},${svpNativeOptions(o.svp, ",")}}"
        add(":no-mediacodec-dr")
    }
    if (filters.isNotEmpty()) add(":video-filter=${filters.joinToString(":")}")
}


/**
 * Sparsam / Low (and Auto→low) effective overrides.
 * Saved user prefs stay intact; callers apply these only at the player/render boundary.
 * Brightness/contrast/saturation/gamma and tone-mapping stay available as intentional settings.
 */
internal fun effectiveMpvImageOptions(options: MpvImageOptions, reducedEffects: Boolean, allowSvp: Boolean = false): MpvImageOptions {
    val o = options.normalized().let { if (allowSvp && !reducedEffects) it else it.copy(svpMode = "off") }
    if (!reducedEffects) return o
    return o.copy(
        scaler = "bilinear",
        chromaScaler = "bilinear",
        downscaler = "bilinear",
        deband = "off",
        sharpening = "off",
        denoise = "off",
        deblock = "off",
        filmGrain = "off",
        superResolution = false,
        antiring = 0,
        sigmoid = false,
    )
}

internal fun effectiveVlcImageOptions(options: VlcImageOptions, reducedEffects: Boolean, allowSvp: Boolean = false): VlcImageOptions {
    val o = options.normalized().let { if (allowSvp && !reducedEffects) it else it.copy(svpMode = "off") }
    if (!reducedEffects) return o
    return o.copy(
        softwareProcessing = false,
        scaler = "fast_bilinear",
        deband = "off",
        denoise = "off",
    )
}

/** Preserve HDR precision before either native presentation or HDR-to-SDR tone mapping. */
internal fun vlcOptionsForNativeHdr(
    options: VlcImageOptions,
    nativeHdrPreferred: Boolean,
    sourceHdrStandard: com.ambient.tv.ui.player.HdrStandard? = null,
): VlcImageOptions =
    if (!nativeHdrPreferred && sourceHdrStandard == null) options else options.copy(
        softwareProcessing = false, svpMode = "off", deband = "off", denoise = "off",
        brightness = 0, contrast = 0, saturation = 0, gamma = 0,
    )

internal fun effectiveMedia3ImageOptions(
    options: com.ambient.tv.data.local.Media3ImageOptions,
    reducedEffects: Boolean,
): com.ambient.tv.data.local.Media3ImageOptions {
    val safe = options.normalized()
    return if (reducedEffects)
        safe.copy(enabled = false) else safe
}

/** Suppress only double-rate (2×) deinterlacers; Auto/basic yadif/bwdif stay available. */
internal fun effectiveDeinterlaceFilter(filter: String, reducedEffects: Boolean): String {
    if (!reducedEffects) return filter
    return when (filter) {
        "yadif2x" -> "yadif"
        "bwdif2x" -> "bwdif"
        "w3fdif2x" -> "w3fdif"
        "estdif2x" -> "estdif"
        "nnedi2x" -> "nnedi"
        "linear", "phosphor" -> "yadif"
        "mcdeint", "mcdeint_bff" -> "yadif"
        "bob" -> "yadif"
        else -> filter
    }
}

internal fun effectiveScreensaverQualityKey(qualityKey: String, reducedEffects: Boolean): String =
    if (reducedEffects) "economy" else qualityKey




/** SVP replaces the output track and can remove its reported frame rate. */
internal fun vlcRenderSampleFps(sourceFps: Double?, svpMode: String, targetFps: Double?): Double? =
    (if (svpMode != "off") targetFps else sourceFps)?.takeIf { it.isFinite() && it > 0.0 }
        ?: sourceFps?.takeIf { it.isFinite() && it > 0.0 }
