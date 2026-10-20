package com.ambient.tv.ui.player.backend

import android.content.Context
import android.content.pm.PackageManager
import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.ui.player.HdrStandard
import com.ambient.tv.ui.player.PlaybackMetadata

/** "HDR über GPU verarbeiten" is a TV feature: phones and tablets keep the direct surface. */
internal fun mpvHdrGpuTvDevice(context: Context): Boolean =
    runCatching { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }.getOrDefault(false)

/** Identity of the display currently driven ([mpvHdrDisplayKey]). */
internal fun mpvHdrGpuDisplayKey(context: Context, standards: Set<HdrStandard>): String {
    val display = runCatching {
        (context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)
    }.getOrNull()
    return mpvHdrDisplayKey(display?.displayId, display?.name, standards)
}

/** Resolution part of a media failure key ([MpvHdrGpuSession.failure]). */
internal fun mpvHdrGpuResolutionKey(metadata: PlaybackMetadata?): String? {
    val width = metadata?.sourceWidth ?: return null
    val height = metadata.sourceHeight ?: return null
    return "${width}x$height"
}

/**
 * [mpvHdrGpuEffectsActive] for a playback: the effective options (Sparsam, SVP) decide. While
 * the same playback already runs on the passthrough, the verdict stays true, so editing the
 * picture options in the player never reopens the title.
 */
internal fun mpvHdrGpuEffectsActiveFor(
    context: Context,
    options: MpvImageOptions,
    passthroughRunning: Boolean,
): Boolean = passthroughRunning || mpvHdrGpuEffectsActive(
    effectiveMpvImageOptions(options, com.ambient.tv.ui.player.reducedVideoEffects(context), svpAllowed(context)),
)
