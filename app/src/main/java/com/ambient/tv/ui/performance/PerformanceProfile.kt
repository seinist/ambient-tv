package com.ambient.tv.ui.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
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
 *   SVP requires playback [PerformanceMode.MAXIMUM], or [PerformanceMode.AUTO] on a device
 *   that is not low-end.
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
    /** A small focus shadow is reserved for the explicitly selected highest UI profile. */
    val allowFocusedItemShadow: Boolean get() = mode == PerformanceMode.MAXIMUM
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
    /**
     * Motion interpolation follows playback mode only, never the interface mode: Maximum, or
     * Auto on a device that is not low-end. Balanced and Low (and Auto resolving to Sparsam) stay off.
     */
    val allowSvpInterpolation: Boolean get() = playbackMode == PerformanceMode.MAXIMUM ||
        (playbackMode == PerformanceMode.AUTO && !reducedPlayback)
    /** Playback is Auto and this device was classified low-end, so Sparsam gates are active. */
    val playbackAutoReduced: Boolean get() = playbackMode == PerformanceMode.AUTO && reducedPlayback
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

/**
 * Low-end for a system low-RAM device, less than 2 GiB of total RAM, or a 32-bit-only userspace.
 * memoryClass (dalvik heapgrowthlimit) is not a device-class signal: AOSP assigns 192 MB
 * to 2–4 GiB configurations, so ordinary TVs and the SHIELD were classified low-end and
 * Auto silently ran playback in Sparsam (bilinear, no shaders, no SVP).
 * RAM alone cannot separate the SHIELD from 32-bit MediaTek TVs (both ~2.8 GiB); those
 * TVs drop frames with full GPU processing at their 4K output (Philips TA1: 21 of 24 fps).
 */
fun detectLowEndDevice(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    val totalMem = runCatching {
        ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem
    }.getOrDefault(0L)
    return isLowEndDevice(am.isLowRamDevice, totalMem, Build.SUPPORTED_64_BIT_ABIS.isNotEmpty())
}

internal const val LOW_END_TOTAL_RAM_BYTES = 2L * 1024L * 1024L * 1024L

/** [totalMemBytes] <= 0 means unknown and does not classify the device. */
internal fun isLowEndDevice(lowRamDevice: Boolean, totalMemBytes: Long, supports64Bit: Boolean = true): Boolean =
    lowRamDevice || !supports64Bit || totalMemBytes in 1 until LOW_END_TOTAL_RAM_BYTES
