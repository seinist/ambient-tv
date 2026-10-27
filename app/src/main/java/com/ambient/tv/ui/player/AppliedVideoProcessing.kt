package com.ambient.tv.ui.player

import com.ambient.tv.data.local.DeinterlaceCatalog
import com.ambient.tv.data.local.VlcImageOptions
import com.ambient.tv.ui.player.backend.MpvPlaybackSnapshot
import com.ambient.tv.ui.player.backend.VlcPlaybackSnapshot
import com.ambient.tv.ui.player.backend.vlcImageMediaOptions
import java.util.Locale

/**
 * Values actually in effect for the Stats-for-Nerds overlay.
 *
 * A null field means the backend did not expose that signal (rendered as "n/a").
 * "off" is used only when the backend reported the stage as inactive.
 * Callers must not substitute settings defaults for a missing runtime value.
 */
internal data class AppliedVideoProcessing(
    val videoFilters: String? = null,
    val deinterlacer: String? = null,
    val upscaler: String? = null,
    val frameInterpolation: String? = null,
)

internal fun appliedStatValue(value: String?): String = value?.takeIf { it.isNotBlank() } ?: "n/a"

internal fun mpvAppliedProcessing(snapshot: MpvPlaybackSnapshot): AppliedVideoProcessing {
    if (!snapshot.processingSampled) return AppliedVideoProcessing()
    return AppliedVideoProcessing(
        videoFilters = summarizeMpvVideoFilters(snapshot.activeVideoFilters),
        deinterlacer = summarizeMpvDeinterlacer(snapshot.activeVideoFilters, snapshot.activeDeinterlace),
        upscaler = if (!snapshot.gpuScalerExposed) {
            "n/a"
        } else {
            // Prefer the live shader when loaded. When a GLSL upscaler is selected but
            // idle (WHEN not met / SVP active), [configuredScaler] keeps NNEDI/FSR visible
            // instead of collapsing to the builtin finish scaler.
            formatMpvUpscalerForStats(
                snapshot.configuredScaler.orEmpty(),
                snapshot.activeScaler,
                snapshot.activeShaderPreset,
                snapshot.activeUpscaling,
                sparsam = snapshot.sparsam,
            ) ?: "n/a"
        },
        frameInterpolation = withSvpStatus(
            if (snapshot.activeVideoFilters?.contains("plex-svp") == true) "svp" else "off",
            snapshot.svpStatus,
        ),
    )
}

/** Replaces the bare "svp" token with what SVP is doing, e.g. "SVP 23.976 → 60 fps · balanced". */
internal fun withSvpStatus(summary: String, svpStatus: String?): String {
    svpStatus ?: return summary
    val others = summary.split(" · ").filter { it != "svp" && it != "off" && it != "n/a" }
    return (others + "SVP $svpStatus").joinToString(" · ")
}

/** Labels successfully present in mpv's `vf` property. Deinterlace is reported on its own line. */
internal fun summarizeMpvVideoFilters(vf: String?): String {
    if (vf.isNullOrBlank() || vf == "[]") return "off"
    val labels = Regex("""plex-[a-z0-9-]+""").findAll(vf).map { it.value }.distinct().toList()
    val friendly = labels.mapNotNull { label ->
        when (label) {
            "plex-deint" -> null
            "plex-denoise" -> "denoise"
            "plex-deblock" -> "deblock"
            "plex-svp" -> "svp"
            else -> label.removePrefix("plex-")
        }
    }
    if (friendly.isNotEmpty()) return friendly.joinToString(", ")
    // A chain we could not name is still on. Do not invent filter names.
    return if (labels.isEmpty()) "on" else "off"
}

/** Explicit `@plex-deint` graph wins over the builtin `deinterlace` property (set to "no" while that graph runs). */
internal fun summarizeMpvDeinterlacer(vf: String?, property: String?): String {
    mpvExplicitDeinterlace(vf)?.let { return it }
    return when (property?.trim()?.lowercase(Locale.US)) {
        null, "" -> "n/a"
        "no" -> "off"
        else -> property.trim()
    }
}

/**
 * Stats row for the configured deinterlacer: the method actually running plus the mode.
 * mpv's builtin `deinterlace` inserts BWDIF in software; "inactive" means no filter is in the chain.
 */
internal fun mpvDeinterlaceStat(mode: String, filter: String, vf: String?, property: String?): String? {
    if (mode == "off") return null
    val active = mpvExplicitDeinterlace(vf)
        ?: when (property?.trim()?.lowercase(Locale.US)) {
            "yes", "auto" -> "bwdif (mpv)"
            "no" -> "inactive"
            else -> filter.takeIf { it.isNotBlank() && it != "auto" }
        }
    return listOfNotNull(active, mode).joinToString(" · ")
}

internal fun mpvExplicitDeinterlace(vf: String?): String? {
    if (vf.isNullOrBlank() || !vf.contains("plex-deint")) return null
    val graph = Regex("""@plex-deint:lavfi=\[(.*?)]""").find(vf)?.groupValues?.get(1) ?: return "on"
    val names = graph.split(',')
        .map { it.substringBefore('=').substringBefore(':').trim() }
        .filter { it.isNotEmpty() }
    return names.joinToString(" · ").ifBlank { "on" }
}

/**
 * What the current LibVLC instance was created with.
 *
 * VLC 4 inherits the vout filter chain from the instance, and media-only `:video-filter`
 * options are ignored. Denoise/deband/adjust and `:swscale-mode` are therefore not reported
 * as applied. libVLC does not expose the scaler in effect, so [upscaler] comes from the
 * output path: the swscale mode when swscale runs, otherwise the surface that scales.
 */
internal fun vlcInstanceAppliedProcessing(
    deinterlaceMode: String,
    deinterlaceFilter: String,
    svpMode: String,
    svpTargetFps: Double?,
    upscaler: String? = null,
): AppliedVideoProcessing {
    val method = DeinterlaceCatalog.normalize("vlc", deinterlaceFilter)
    val deinterlacer = when (deinterlaceMode) {
        "off" -> "off"
        "auto" -> if (method == "auto") "auto" else "auto · $method"
        "on" -> if (method == "auto") "on" else method
        else -> "n/a"
    }
    val filterArg = vlcImageMediaOptions(VlcImageOptions(svpMode = svpMode), svpTargetFps)
        .firstOrNull { it.startsWith(":video-filter=") }
        ?.substringAfter('=')
    val svpApplied = filterArg?.contains("ambientsvp") == true
    val interpolation = if (svpApplied) {
        val fps = svpTargetFps?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { " · ${String.format(Locale.US, "%.0f", it)} fps" }
            .orEmpty()
        "svp · $svpMode$fps"
    } else {
        "off"
    }
    return AppliedVideoProcessing(
        videoFilters = if (svpApplied) "svp" else "off",
        deinterlacer = deinterlacer,
        upscaler = upscaler ?: "n/a",
        frameInterpolation = interpolation,
    )
}

internal fun vlcAppliedProcessing(snapshot: VlcPlaybackSnapshot): AppliedVideoProcessing {
    if (!snapshot.processingSampled) return AppliedVideoProcessing()
    return AppliedVideoProcessing(
        videoFilters = snapshot.appliedVideoFilters,
        deinterlacer = snapshot.appliedDeinterlacer,
        upscaler = snapshot.appliedUpscaler,
        frameInterpolation = snapshot.svpStatus?.let { "SVP $it" } ?: snapshot.appliedFrameInterpolation,
    )
}

/**
 * Media3 only applies its GPU effect list (deband, upscaler, sharpening, picture controls).
 * It has no deinterlacer and no frame interpolation, so those stay "n/a".
 * [scaler] is the scaler really upscaling in the chain, null when the display path scales.
 */
internal fun media3AppliedProcessing(
    effectsActive: Boolean,
    scaler: String?,
    stages: List<Media3Stage>,
): AppliedVideoProcessing {
    if (!effectsActive) {
        return AppliedVideoProcessing(
            videoFilters = "off",
            deinterlacer = "n/a",
            upscaler = "n/a",
            frameInterpolation = "n/a",
        )
    }
    val filters = stages.filter { it !is Media3Stage.Scale }
        .joinToString(", ") { media3StageSummary(it) }.ifBlank { "off" }
    return AppliedVideoProcessing(
        videoFilters = filters,
        deinterlacer = "n/a",
        upscaler = scaler?.let { media3ScalerLabel(it) } ?: "n/a",
        frameInterpolation = "n/a",
    )
}

/** English Stats-for-Nerds wording for one Media3 stage. */
internal fun media3StageSummary(stage: Media3Stage): String = when (stage) {
    is Media3Stage.Deband -> "deband ${stage.strength}"
    is Media3Stage.Scale -> media3ScalerLabel(stage.scaler)
    is Media3Stage.Sharpen -> when (stage.kind) {
        "cas" -> "sharpen CAS"
        "rcas" -> "sharpen RCAS (FSR)"
        else -> "sharpen ${stage.kind}"
    }
    is Media3Stage.Brightness -> "brightness ${stage.value}"
    is Media3Stage.Contrast -> "contrast ${stage.value}"
    is Media3Stage.Saturation -> "saturation ${stage.value}"
    is Media3Stage.Gamma -> "gamma ${stage.value}"
}
