package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.SvpOptions
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.ambient.tv.ui.player.performanceProfile
import com.ambient.tv.ui.player.videoImagePreferences

/** Global SVP switch, performance mode, an explicit choice under Auto and the native filter must all agree. */
internal fun svpAllowed(context: Context): Boolean {
    val preferences = videoImagePreferences(context)
    val profile = performanceProfile(context)
    return preferences.svpAvailable &&
        svpModeAllowsInterpolation(profile.playbackMode, profile.allowSvpInterpolation, preferences.svpExplicitChoice) &&
        SvpNative.isEnabledAndAvailable()
}

/**
 * Maximum runs a configured SVP mode. Auto (not low-end) runs it only after the user picked an
 * SVP mode in an SVP menu: a stored legacy value (old "auto" → light) used to start
 * interpolation by itself, forced copy-back and kept display-mode matching on the desktop rate.
 */
internal fun svpModeAllowsInterpolation(
    playbackMode: com.ambient.tv.ui.performance.PerformanceMode,
    profileAllows: Boolean,
    explicitChoice: Boolean,
): Boolean = profileAllows && (playbackMode == com.ambient.tv.ui.performance.PerformanceMode.MAXIMUM || explicitChoice)

internal fun svpDisplayFps(context: Context): Double? =
    context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        ?.refreshRate?.toDouble()?.takeIf { it.isFinite() && it in 5.0..120.0 }

/** Native preset number. Stored "auto" is normalized to light before it gets here. */
internal fun svpPreset(mode: String): Int = when (mode) {
    "auto", "light" -> 1
    "balanced" -> 2
    "quality" -> 3
    else -> 0
}

internal fun svpTargetChanged(mode: String, before: Double?, after: Double?): Boolean =
    mode != "off" && if (before == null || after == null) before != after else kotlin.math.abs(before - after) > 0.01

/** Why SVP is not interpolating right now. Availability reasons come first. */
enum class SvpInactiveReason(val unavailable: Boolean) {
    /** The current engine/output has no SVP filter (Media3, MPV direct output, VLC without SVP). */
    ENGINE(true),
    /** Only "Maximum performance" runs SVP. */
    PERFORMANCE_MODE(true),
    /** The native library could not be loaded on this device. */
    LIBRARY(true),
    HDR(true),
    /** Source larger than the configured resolution limit. */
    RESOLUTION(false),
    /** Source above 30 fps while "film only" is set. */
    SOURCE_RATE_LIMIT(false),
    /** No constant source rate known yet (or outside 5..120 fps / more than 5x). */
    SOURCE_RATE_UNKNOWN(false),
    DISPLAY_RATE_UNKNOWN(false),
    /** Source already at or above the target rate. */
    NOT_NEEDED(false),
    /** Frames are passed through: 10-bit, interlaced, unsupported pixel format. */
    FORMAT(false),
    /** Frames are passed through: variable frame rate or timestamp gap. */
    TIMING(false),
    /** The filter is configured but produced no interpolated frames (VLC) or failed. */
    PASSTHROUGH(false),
    /** Planned; the filter is being attached. */
    STARTING(false),
    /** Too slow on this device; removed for this session. */
    OVERLOAD(false),
}

/** Decision before the filter is attached. */
internal sealed interface SvpDecision {
    data class Run(val source: Double, val target: Double) : SvpDecision
    data class Skip(val reason: SvpInactiveReason) : SvpDecision
}

private const val SVP_FILM_MAX_FPS = 30.5
/** Tolerance for codec padding such as 1920x1088. */
private const val SVP_SIZE_SLACK = 16

internal fun svpMaxWidth(maxHeight: Int): Int = when (maxHeight) { 720 -> 1280; 2160 -> 3840; else -> 1920 }

internal fun svpWithinResolution(width: Int?, height: Int?, options: SvpOptions): Boolean {
    val o = options.normalized()
    if (width == null || height == null || width <= 0 || height <= 0) return true
    return width <= svpMaxWidth(o.maxHeight) + SVP_SIZE_SLACK && height <= o.maxHeight + SVP_SIZE_SLACK
}

/**
 * The static part of the decision: unknown / VFR / too large / filtered sources stay unfiltered.
 * No guessed source rate.
 */
internal fun svpDecision(
    sourceFps: Double?,
    displayFps: Double?,
    options: SvpOptions = SvpOptions(),
    width: Int? = null,
    height: Int? = null,
): SvpDecision {
    val o = options.normalized()
    val source = sourceFps?.takeIf { it.isFinite() && it in 5.0..120.0 }
        ?: return SvpDecision.Skip(SvpInactiveReason.SOURCE_RATE_UNKNOWN)
    if (o.filmOnly && source > SVP_FILM_MAX_FPS) return SvpDecision.Skip(SvpInactiveReason.SOURCE_RATE_LIMIT)
    if (!svpWithinResolution(width, height, o)) return SvpDecision.Skip(SvpInactiveReason.RESOLUTION)
    val target = svpOutputFps(displayFps, o)?.takeIf { it.isFinite() && it in 5.0..120.0 }
        ?: return SvpDecision.Skip(SvpInactiveReason.DISPLAY_RATE_UNKNOWN)
    if (target <= source * 1.01) return SvpDecision.Skip(SvpInactiveReason.NOT_NEEDED)
    if (target / source > 5.0) return SvpDecision.Skip(SvpInactiveReason.SOURCE_RATE_UNKNOWN)
    return SvpDecision.Run(source, target)
}

internal fun mpvSvpFilter(
    mode: String,
    sourceFps: Double?,
    displayFps: Double?,
    options: SvpOptions = SvpOptions(),
    width: Int? = null,
    height: Int? = null,
): String? {
    if (mode !in setOf("auto", "light", "balanced", "quality")) return null
    val run = svpDecision(sourceFps, displayFps, options, width, height) as? SvpDecision.Run ?: return null
    return "ambientsvp=source=${run.source}:fps=${run.target}:preset=${svpPreset(mode)}:${svpNativeOptions(options, ":")}"
}

/**
 * Integer display divisors preserve display cadence, including fractional refresh rates.
 * "At most 60" picks the smallest divisor that brings 100/120/144 Hz down to <= 60 fps.
 */
internal fun svpOutputFps(displayFps: Double?, options: SvpOptions): Double? {
    val display = displayFps?.takeIf { it.isFinite() && it in 5.0..120.0 } ?: return null
    val divisor = when (options.normalized().rateDivisor) {
        2 -> 2
        3 -> generateSequence(1) { it + 1 }.first { display / it <= 60.5 }
        else -> 1
    }
    return display / divisor
}

internal fun svpNativeOptions(options: SvpOptions, separator: String): String {
    val o = options.normalized()
    return listOf("backend=${o.backend}", "threads=${o.threads}", "artifacts=${o.artifacts}",
        "scene=${o.scene}", "algo=${o.algorithm}", "block=${o.block}", "overlap=${o.overlap}",
        "pel=${o.pel}", "search=${o.search}").joinToString(separator)
}

/** Probe and recovery must use the same cheap analysis, even with explicit quality overrides. */
internal fun svpProbeOptions(options: SvpOptions): SvpOptions = SvpOptions(
    rateDivisor = options.rateDivisor, backend = options.backend, threads = options.threads,
    maxHeight = options.maxHeight, filmOnly = options.filmOnly,
).normalized()

/**
 * plex-svp metadata. `bypass` is the engine's own reason (1 overload, 2 runtime gate, 3 engine
 * error); `passthrough` is the adapter's reason for handing frames through unchanged.
 */
internal object SvpNativeReason {
    const val BYPASS_OVERLOAD = 1
    const val BYPASS_GATE = 2
    const val PASSTHROUGH_FORMAT = 1
    const val PASSTHROUGH_UNAVAILABLE = 2
    const val PASSTHROUGH_TIMING = 3
    const val PASSTHROUGH_ERROR = 4
}

/** Adapter/engine signals turned into an inactive reason; null while interpolating. */
internal fun svpRuntimeReason(bypass: Int, passthrough: Int): SvpInactiveReason? = when {
    bypass == SvpNativeReason.BYPASS_OVERLOAD -> SvpInactiveReason.OVERLOAD
    passthrough == SvpNativeReason.PASSTHROUGH_FORMAT -> SvpInactiveReason.FORMAT
    passthrough == SvpNativeReason.PASSTHROUGH_TIMING -> SvpInactiveReason.TIMING
    passthrough != 0 -> SvpInactiveReason.PASSTHROUGH
    bypass == SvpNativeReason.BYPASS_GATE -> SvpInactiveReason.PERFORMANCE_MODE
    bypass != 0 -> SvpInactiveReason.PASSTHROUGH
    else -> null
}

/**
 * MPV: whether display-mode matching must keep the refresh rate for SVP. Until the filter
 * decision is made for the loaded file, an SVP that can still run counts as intended,
 * otherwise a 24 Hz switch would make SVP unnecessary before it starts. Afterwards only a
 * filter that is in the chain counts, and not when it permanently passes frames through
 * (unsupported format, overload). A timing gap is transient and must not switch modes.
 */
internal fun mpvSvpInterpolationIntended(
    modeOn: Boolean,
    overloaded: Boolean,
    hdr: Boolean,
    decided: Boolean,
    inChain: Boolean,
    runtimeReason: SvpInactiveReason?,
): Boolean = when {
    !modeOn || overloaded || hdr -> false
    !decided -> true
    else -> inChain && runtimeReason != SvpInactiveReason.FORMAT && runtimeReason != SvpInactiveReason.OVERLOAD
}
