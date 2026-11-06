package com.ambient.tv.youtube

/** A validated position shared by local cards and YouTube's watch-history update. */
internal data class YouTubeProgressReport(val positionMs: Long, val completed: Boolean)

/**
 * New/released decoders can briefly report zero without having played or sought there.
 * Saving that placeholder would erase the card's resume point. A deliberate seek to zero
 * and an actual end remain valid even when the decoder no longer exposes a useful clock.
 * YouTube's completion boundary is 95%, separate from the media-library credits policy.
 */
internal fun youtubeProgressReport(
    positionMs: Long,
    durationMs: Long,
    ended: Boolean,
    failed: Boolean = false,
    explicitSeek: Boolean = false,
    completedByNavigation: Boolean = false,
): YouTubeProgressReport? {
    val completed = completedByNavigation || (ended && !failed) ||
        (durationMs > 0L && positionMs.toDouble() / durationMs >= YOUTUBE_FINISHED_PERCENT / 100.0)
    if (!completed && !explicitSeek && positionMs <= 0L) return null
    val position = if (completed && durationMs > 0L) durationMs else positionMs.coerceAtLeast(0L)
    return YouTubeProgressReport(position, completed)
}
