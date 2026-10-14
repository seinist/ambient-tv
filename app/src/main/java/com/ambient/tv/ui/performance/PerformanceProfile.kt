package com.ambient.tv.ui.performance

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Shared level names for interface and playback performance.
 * Until a side has its own stored key, it still reads [AppPreferences.performanceModeKey].
 */
enum class PerformanceMode(val key: String) {
    AUTO("auto"),
    BALANCED("balanced"),
    MAXIMUM("maximum"),
    LOW("low");

    companion object {
        val KEYS = entries.map { it.key }.toSet()

        fun fromKey(key: String?): PerformanceMode =
            entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/**
 * Resolved gates for the current device.
 *
 * [mode] is interface performance: animations, shimmer, blur and heavy surfaces.
 * [playbackMode] is playback performance: SVP, heavy upscalers, deinterlace downgrades
 * and the Sparsam video gates. The two are independent.
 *
 * - [PerformanceMode.LOW] always applies that side's reduced gates.
 * - [PerformanceMode.AUTO] applies them only on low-end devices.
 * - [PerformanceMode.BALANCED] and [PerformanceMode.MAXIMUM] keep that side full.
 *   SVP still requires playback [PerformanceMode.MAXIMUM].
 */
data class PerformanceProfile(
    val mode: PerformanceMode,
    val lowEndDevice: Boolean,
    /** Interface gates: animations, shimmer, blur, heavy surfaces. */
    val reducedEffects: Boolean,
    val playbackMode: PerformanceMode = mode,
    /** Playback gates: SVP, upscalers, deinterlace and other Sparsam video paths. */
    val reducedPlayback: Boolean = reducedEffects,
) {
    val allowBlur: Boolean get() = !reducedEffects
    val allowSoftShadow: Boolean get() = !reducedEffects
    val allowSpringFocus: Boolean get() = !reducedEffects
    /** Kodi-like nav clicks; hard-off under interface Sparsam/Low. */
    val allowUiSounds: Boolean get() = !reducedEffects
    val allowComplexHero: Boolean get() = !reducedEffects
    val allowRowFadeSaveLayer: Boolean get() = !reducedEffects
    val allowHighQualityAssets: Boolean get() = !reducedEffects
    val constrainImageDecodeSize: Boolean get() = reducedEffects
    /** When the interface is reduced, ProjectM uses dirty/throttled rendering instead of continuous VSync. */
    val throttleProjectM: Boolean get() = reducedEffects
    /** Poll OSD timeline position only while chrome/seek feedback is visible when playback is reduced. */
    val pollOsdPositionOnlyWhenChromeVisible: Boolean get() = reducedPlayback
    /**
     * When playback is reduced, expensive VIDEO paths use effective overrides (GPU shaders,
     * CPU denoise/deblock, VLC software filters, Media3 GPU sink, 2× deinterlace, spectrum).
     * Saved image prefs are preserved and restored automatically when leaving Low.
     * Audio DSP (EQ/night/dialogue/loudness/remix) stays available.
     */
    val gateExpensiveVideoVisuals: Boolean get() = reducedPlayback
    /** Motion interpolation follows playback mode only, never the interface mode or Auto. */
    val allowSvpInterpolation: Boolean get() = playbackMode == PerformanceMode.MAXIMUM
    /** Screensaver shaders run at Economy (or static ambient fallback) when the interface is reduced. */
    val forceScreensaverEconomy: Boolean get() = reducedEffects
    /** Skip FFT/spectrum work unless a visualizer is visible; throttle polling when playback is reduced. */
    val throttleSpectrumVisualizer: Boolean get() = reducedPlayback

    companion object {
        val Full = PerformanceProfile(
            mode = PerformanceMode.BALANCED,
            lowEndDevice = false,
            reducedEffects = false,
        )

        fun resolve(
            mode: PerformanceMode,
            lowEndDevice: Boolean,
            playbackMode: PerformanceMode = mode,
        ): PerformanceProfile = PerformanceProfile(
            mode = mode,
            lowEndDevice = lowEndDevice,
            reducedEffects = reducedFor(mode, lowEndDevice),
            playbackMode = playbackMode,
            reducedPlayback = reducedFor(playbackMode, lowEndDevice),
        )

        private fun reducedFor(mode: PerformanceMode, lowEndDevice: Boolean): Boolean = when (mode) {
            PerformanceMode.LOW -> true
            PerformanceMode.BALANCED, PerformanceMode.MAXIMUM -> false
            PerformanceMode.AUTO -> lowEndDevice
        }
    }
}

val LocalPerformanceProfile = staticCompositionLocalOf { PerformanceProfile.Full }

/** Heuristic for Amlogic/Rockchip-class TV sticks: low-RAM flag, small heap, or &lt;2 GiB total. */
fun detectLowEndDevice(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    if (am.isLowRamDevice) return true
    if (am.memoryClass > 0 && am.memoryClass <= 192) return true
    return runCatching {
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem in 1 until (2L * 1024L * 1024L * 1024L)
    }.getOrDefault(false)
}
