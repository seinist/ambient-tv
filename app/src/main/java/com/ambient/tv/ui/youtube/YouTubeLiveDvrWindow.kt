package com.ambient.tv.ui.youtube

/**
 * The seekable part of a YouTube live timeline, in the decoder's window-relative clock.
 *
 * The metadata flag `isLive` alone is not enough: many live broadcasts have no DVR buffer.
 * For Media3, `livePositionMs` is the manifest's default live position, which is usually
 * slightly before the end of the window to allow for the target live latency.
 */
internal data class YouTubeLiveDvrWindow(val livePositionMs: Long) {
    fun behindLiveMs(positionMs: Long): Long =
        (livePositionMs - positionMs.coerceAtLeast(0L)).coerceAtLeast(0L)

    fun canJumpToLive(positionMs: Long): Boolean = behindLiveMs(positionMs) > LIVE_BEHIND_TOLERANCE_MS

    /** Scrubbing to the right edge should go to the current live position, not to VOD EOF. */
    fun targetsLiveEdge(positionMs: Long): Boolean =
        positionMs >= (livePositionMs - LIVE_EDGE_SEEK_TOLERANCE_MS).coerceAtLeast(0L)
}

internal fun youtubeLiveDvrWindow(
    isLive: Boolean,
    seekable: Boolean,
    durationMs: Long,
    livePositionMs: Long? = null,
): YouTubeLiveDvrWindow? {
    if (!isLive || !seekable || durationMs <= 0L) return null
    val edge = livePositionMs?.takeIf { it > 0L }?.coerceAtMost(durationMs) ?: durationMs
    return edge.takeIf { it > 0L }?.let(::YouTubeLiveDvrWindow)
}

private const val LIVE_BEHIND_TOLERANCE_MS = 5_000L
private const val LIVE_EDGE_SEEK_TOLERANCE_MS = 1_000L
