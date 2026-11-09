package com.ambient.tv.ui.youtube

/**
 * A live DVR window in the decoder's own time base, not a saved watch position.
 *
 * HLS/DASH in Media3 normally starts the current window at zero. Other decoders and
 * SmartTube's SABR sources can use an absolute timeline with a nonzero first seekable
 * position; keep the origin explicit rather than pretending that stream time zero is
 * necessarily available. The right edge is the window END, never the preferred start
 * position (which deliberately sits behind live to allow buffering).
 */
internal data class YouTubeLiveDvrWindow(
    val livePositionMs: Long,
    val windowEndMs: Long = livePositionMs,
    val windowStartMs: Long = 0L,
) {
    val durationMs: Long get() = (windowEndMs - windowStartMs).coerceAtLeast(0L)

    fun displayPositionMs(decoderPositionMs: Long): Long =
        (decoderPositionMs.coerceIn(windowStartMs, windowEndMs) - windowStartMs)

    fun clampDecoderPositionMs(targetMs: Long): Long = targetMs.coerceIn(windowStartMs, windowEndMs)

    /** Relative control jumps still use decoder time; the OSD shows window-relative time. */
    fun decoderPositionMs(displayPositionMs: Long): Long =
        windowStartMs + displayPositionMs.coerceIn(0L, durationMs)

    fun behindLiveMs(positionMs: Long): Long =
        (livePositionMs - positionMs.coerceAtLeast(windowStartMs)).coerceAtLeast(0L)

    fun canJumpToLive(positionMs: Long): Boolean = behindLiveMs(positionMs) > LIVE_BEHIND_TOLERANCE_MS

    /** Rightmost scrub and Go Live target the latest live point, not VOD EOF. */
    fun targetsLiveEdge(positionMs: Long): Boolean =
        positionMs >= (windowEndMs - LIVE_EDGE_SEEK_TOLERANCE_MS).coerceAtLeast(windowStartMs)

    fun targetsWindowStart(positionMs: Long): Boolean = positionMs <= windowStartMs
}

internal fun youtubeLiveDvrWindow(
    isLive: Boolean,
    seekable: Boolean,
    durationMs: Long,
    livePositionMs: Long? = null,
    windowStartMs: Long = 0L,
): YouTubeLiveDvrWindow? {
    if (!isLive || !seekable || durationMs <= windowStartMs ||
        durationMs <= 0L || durationMs == Long.MAX_VALUE || windowStartMs < 0L) return null
    // The default player position is the SAFE live playback point, *not* the DVR duration.
    val safeLive = livePositionMs?.takeIf { it > windowStartMs && it <= durationMs } ?: durationMs
    return YouTubeLiveDvrWindow(safeLive, durationMs, windowStartMs)
}

private const val LIVE_BEHIND_TOLERANCE_MS = 5_000L
private const val LIVE_EDGE_SEEK_TOLERANCE_MS = 1_000L
