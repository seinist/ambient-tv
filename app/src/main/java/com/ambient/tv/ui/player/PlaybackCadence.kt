package com.ambient.tv.ui.player

/** Cadence of video frames on the active display; never inferred from enabled settings. */
internal fun playbackCadenceLabel(
    fps: Double?,
    refreshHz: Double?,
    doubleRate: String = "Double frame rate",
    pulldown: String = "3:2 pulldown",
): String? {
    if (fps == null || refreshHz == null || !fps.isFinite() || !refreshHz.isFinite() ||
        fps <= 0.0 || refreshHz <= 0.0) return null
    // Match the display mode selector tolerance, including fractional broadcast rates.
    return when {
        kotlin.math.abs(refreshHz - fps * 2.0) <= 0.6 -> doubleRate
        fps in 23.0..24.5 && kotlin.math.abs(refreshHz - fps * 2.5) <= 0.6 -> pulldown
        else -> null
    }
}
