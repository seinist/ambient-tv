package com.ambient.tv.ui.player

/** An explicit end target requires a finite media length, never an unknown-duration sentinel. */
internal fun isPlaybackEndSeek(requestedMs: Long, durationMs: Long): Boolean =
    durationMs in 1 until Long.MAX_VALUE && requestedMs >= durationMs

/**
 * Let DASH/HLS and native decoders play a short tail into their own EOF. Seeking to the exact
 * final timestamp can leave them buffering indefinitely; seeking backwards within the tail
 * can instead replay the final chapter. Null means the remaining tail should simply play out.
 */
internal fun safePlaybackEndSeekTarget(currentMs: Long, durationMs: Long): Long? {
    if (durationMs !in 1 until Long.MAX_VALUE) return null
    val current = currentMs.coerceAtLeast(0L)
    val target = (durationMs - PLAYBACK_END_TAIL_MS).coerceAtLeast(0L)
    return target.takeIf { it - current > PLAYBACK_END_MIN_FORWARD_MS }
}

private const val PLAYBACK_END_TAIL_MS = 1_500L
private const val PLAYBACK_END_MIN_FORWARD_MS = 250L
