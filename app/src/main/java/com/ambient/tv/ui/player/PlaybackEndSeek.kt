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

/**
 * A deliberate jump to logical EOF must also finish a paused item. Decoders intentionally
 * land a little before EOF (and may refuse another seek while already in that tail), so the
 * remaining frames need to play to deliver the real ended event and normal completion/Next Up.
 * This applies only to explicit user seeks; background SponsorBlock stays hands-off.
 */
internal fun shouldPlayThroughPlaybackEnd(
    requestedMs: Long,
    durationMs: Long,
    wantsToPlay: Boolean,
    ended: Boolean,
): Boolean = isPlaybackEndSeek(requestedMs, durationMs) && !wantsToPlay && !ended

private const val PLAYBACK_END_TAIL_MS = 1_500L
private const val PLAYBACK_END_MIN_FORWARD_MS = 250L

/**
 * A forward seek that stops within [PLAYBACK_END_SNAP_MS] of EOF targets the end itself. Chapter
 * navigation can step to a duration a few hundred ms shorter than the decoder's (rounded metadata,
 * session snapshot); without the snap that request is an ordinary seek which replays the tail
 * (keyframe seeks land seconds earlier) instead of finishing the item.
 */
internal fun snapForwardSeekToPlaybackEnd(requestedMs: Long, durationMs: Long, forward: Boolean): Long =
    if (forward && durationMs in 1 until Long.MAX_VALUE && requestedMs < durationMs &&
        durationMs - requestedMs <= PLAYBACK_END_SNAP_MS) durationMs else requestedMs

private const val PLAYBACK_END_SNAP_MS = 1_500L
