package com.ambient.tv.ui.player.backend

import kotlin.math.abs

private fun validTargetFrameRate(value: Double?): Double? =
    value?.takeIf { it.isFinite() && it > 0.0 }

/**
 * Nominal output cadence before playback speed is applied. SVP's live target is authoritative;
 * display Hz and a merely selected interpolation option are not video frame rates.
 *
 * MPV's estimated-vf-fps describes filter timestamps, not presented frames. For automatic
 * deinterlacing/IVTC it can identify a known cadence, but must not lower the target to an
 * arbitrary measured rate when decoding falls behind or a seek unsettles the estimate.
 * See https://github.com/mpv-player/mpv/blob/master/DOCS/man/input.rst.
 */
internal fun mpvTargetVideoFps(
    sourceFps: Double?,
    filteredFps: Double?,
    svpActive: Boolean,
    svpTargetFps: Double?,
    deinterlaceEnabled: Boolean,
    deinterlaceAutomatic: Boolean,
    deinterlaceFilter: String,
    deinterlaceSingleRate: Boolean,
): Double? {
    if (svpActive) return validTargetFrameRate(svpTargetFps)
    val source = validTargetFrameRate(sourceFps) ?: return null
    if (!deinterlaceEnabled) return source

    if (deinterlaceFilter == "ivtc") {
        return matchingFilterCadence(filteredFps, source, source * 0.8)
    }
    val nominal = mpvDeinterlacedFps(source, true, deinterlaceFilter, deinterlaceSingleRate)
        ?.let(::validTargetFrameRate) ?: return null
    // A single-rate method retains its cadence even when Auto bypasses progressive input.
    if (!deinterlaceAutomatic || nominal == source) return nominal
    return matchingFilterCadence(filteredFps, source, nominal)
}

/** Allow timestamp quantization without presenting a transient slowdown as a new target. */
private fun matchingFilterCadence(filteredFps: Double?, vararg candidates: Double): Double? {
    val filtered = validTargetFrameRate(filteredFps) ?: return null
    return candidates.filter { it.isFinite() && it > 0.0 }
        .minByOrNull { abs(filtered - it) / it }
        ?.takeIf { abs(filtered - it) / it <= 0.02 }
}

/**
 * VLC exposes the track rate, but no live interlace flag or filter-output cadence here.
 * Its software deinterlacer maps the Auto method to X (one output per input); an explicit
 * double-rate method doubles only when forced on. Auto activation and IVTC remain unknown.
 * See https://github.com/videolan/vlc/blob/master/modules/video_filter/deinterlace/deinterlace.c.
 */
internal fun vlcTargetVideoFps(
    sourceFps: Double?,
    svpActive: Boolean,
    svpTargetFps: Double?,
    deinterlaceMode: String,
    deinterlaceFilter: String,
): Double? {
    if (svpActive) return validTargetFrameRate(svpTargetFps)
    val source = validTargetFrameRate(sourceFps) ?: return null
    if (deinterlaceMode == "off") return source
    if (deinterlaceMode != "on" && deinterlaceMode != "auto") return null
    return when (deinterlaceFilter) {
        "auto", "discard", "mean", "blend", "x", "yadif" -> source
        "bob", "linear", "yadif2x", "phosphor" ->
            if (deinterlaceMode == "on") validTargetFrameRate(source * 2.0) else null
        else -> null
    }
}
