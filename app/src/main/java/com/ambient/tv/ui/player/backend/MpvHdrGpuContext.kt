package com.ambient.tv.ui.player.backend

import android.content.Context
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.ResolutionVideoProfile
import com.ambient.tv.data.local.TitleVideoSettings
import com.ambient.tv.ui.player.HdrStandard
import com.ambient.tv.ui.player.PlaybackMetadata
import com.ambient.tv.ui.player.withGlobalSvp

/**
 * "HDR über GPU verarbeiten" is a TV feature: phones and tablets keep the direct surface. Same
 * device class as the TV UI ([com.ambient.tv.ui.mobile.isTelevision]), not the leanback feature.
 */
internal fun mpvHdrGpuTvDevice(context: Context): Boolean =
    runCatching { com.ambient.tv.ui.mobile.isTelevision(context) }.getOrDefault(false)

private fun defaultDisplay(context: Context): android.view.Display? = runCatching {
    (context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
        .getDisplay(android.view.Display.DEFAULT_DISPLAY)
}.getOrNull()

/** Identity of the display currently driven ([mpvHdrDisplayKey]). */
internal fun mpvHdrGpuDisplayKey(context: Context, standards: Set<HdrStandard>): String {
    val display = defaultDisplay(context)
    return mpvHdrDisplayKey(display?.displayId, display?.name, standards)
}

/** [mpvHdrGpuMediaKey] for a playback. */
internal fun mpvHdrGpuMediaKey(metadata: PlaybackMetadata?, url: String?): String? =
    mpvHdrGpuMediaKey(metadata?.source, metadata?.mediaId, metadata?.liveTvSourceId, metadata?.route,
        metadata?.isTrailer == true, url)

/**
 * Resolution part of a media failure key ([MpvHdrGpuSession.failure]). Live channels are keyed
 * by the channel alone: their width can be stale from the previous channel and changes with the
 * programme.
 */
internal fun mpvHdrGpuResolutionKey(metadata: PlaybackMetadata?): String? {
    if (metadata?.isLive == true) return null
    val width = metadata?.sourceWidth ?: return null
    val height = metadata.sourceHeight ?: return null
    return "${width}x$height"
}

/**
 * Enlargement of the source onto the current display mode ([mpvFittedScale]); unknown sizes
 * give +∞ (= counts as upscaling). Before the surface exists, the display mode is the best
 * estimate of the passthrough's output size.
 */
internal fun mpvHdrGpuFittedScale(context: Context, metadata: PlaybackMetadata?): Double {
    val mode = defaultDisplay(context)?.mode
    return mpvHdrGpuFittedScale(metadata?.sourceWidth, metadata?.sourceHeight, mode?.physicalWidth, mode?.physicalHeight)
}

internal fun mpvHdrGpuFittedScale(sourceWidth: Int?, sourceHeight: Int?, displayWidth: Int?, displayHeight: Int?): Double {
    // A portrait mode report (some TVs) still drives a landscape panel.
    val (w, h) = if ((displayWidth ?: 0) < (displayHeight ?: 0)) displayHeight to displayWidth else displayWidth to displayHeight
    return mpvFittedScale((sourceWidth ?: 0).toDouble(), (sourceHeight ?: 0).toDouble(), w ?: 0, h ?: 0)
}

/** Picture options a new MPV player of this playback starts with (title → resolution → global, + global SVP). */
internal fun mpvHdrGpuConfiguredImageOptions(preferences: AppPreferences, title: TitleVideoSettings?,
    resolution: ResolutionVideoProfile?): MpvImageOptions =
    (title?.mpv ?: resolution?.mpv ?: preferences.mpvImageOptions).withGlobalSvp(preferences)

/**
 * [mpvHdrGpuEffectsActive] for a playback: the effective options (Sparsam, SVP) and the real
 * enlargement ([mpvHdrGpuFittedScale]) decide, so a 4K title on a 4K display does not count a
 * luma upscaler that would never run. While the same playback already runs on the passthrough,
 * the verdict stays true, so editing the picture options in the player never reopens the title.
 */
internal fun mpvHdrGpuEffectsActiveFor(
    context: Context,
    metadata: PlaybackMetadata?,
    options: MpvImageOptions,
    passthroughRunning: Boolean,
): Boolean = passthroughRunning || mpvHdrGpuEffectsActive(
    effectiveMpvImageOptions(options, com.ambient.tv.ui.player.reducedVideoEffects(context), svpAllowed(context), com.ambient.tv.ui.player.mpvCheapDefaults(context)),
    fittedScale = mpvHdrGpuFittedScale(context, metadata),
)
