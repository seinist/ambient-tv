package com.ambient.tv.ui.player

/**
 * Only moving video on a handheld enters the Android system PiP window.
 * Background audio playback is separate and must keep working without PiP.
 */
internal fun handheldPipEligible(
    handheld: Boolean,
    supported: Boolean,
    enabled: Boolean,
    active: Boolean,
    hasVideo: Boolean,
    wantsToPlay: Boolean,
    isTrailer: Boolean,
): Boolean = handheld && supported && enabled && active && hasVideo && wantsToPlay && !isTrailer

/**
 * The Android PiP aspect ratio is limited to 1:2.39..2.39:1. Keep portrait
 * videos portrait, and avoid IllegalArgumentException for unusually wide files.
 */
internal fun handheldPipAspectRatio(sourceWidth: Int?, sourceHeight: Int?, sourceHint: Float?): Float {
    val dimensions = if (sourceWidth != null && sourceHeight != null &&
        sourceWidth > 0 && sourceHeight > 0) sourceWidth.toFloat() / sourceHeight else null
    val ratio = sourceHint?.takeIf { it.isFinite() && it > 0f } ?: dimensions ?: (16f / 9f)
    return ratio.coerceIn(1f / 2.39f, 2.39f)
}
