package com.ambient.tv.ui.player

import kotlin.math.roundToLong

/** OK on the seek bar: idle toggles play/pause; an active preview commits and resumes. */
internal data class SeekBarConfirm(
    val seekDeltaMs: Long? = null,
    val resumePlayback: Boolean = false,
    val togglePlayback: Boolean = false,
)

internal fun confirmSeekBarOk(
    pendingTargetMs: Long?,
    previewMs: Long?,
    positionMs: Long,
): SeekBarConfirm {
    if (pendingTargetMs != null) {
        return SeekBarConfirm(seekDeltaMs = pendingTargetMs - positionMs, resumePlayback = true)
    }
    if (previewMs != null) {
        return SeekBarConfirm(resumePlayback = true)
    }
    return SeekBarConfirm(togglePlayback = true)
}

internal fun seekPositionFromPointer(x: Float, width: Int, durationMs: Long): Long {
    if (width <= 0 || durationMs <= 0L) return 0L
    return (durationMs * (x / width).coerceIn(0f, 1f)).roundToLong().coerceIn(0L, durationMs)
}
