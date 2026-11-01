package com.ambient.tv.ui.player

/**
 * The last position seen for each playback (per session generation). A stopped playback whose
 * renderer no longer knows its position (a released or failed engine reports 0 without an end)
 * is then saved where it actually stopped instead of resetting its resume point.
 */
internal class PlaybackPositionMemory(private val capacity: Int = 16) {
    private val positions = LinkedHashMap<Long, Long>()

    init { require(capacity > 0) }

    /** [timelinePositionMs] counts only when the renderer reported a real position. */
    fun record(generation: Long, rendererPositionMs: Long, timelinePositionMs: Long) {
        if (rendererPositionMs <= 0L) return
        positions.remove(generation)
        positions[generation] = timelinePositionMs
        while (positions.size > capacity) positions.remove(positions.keys.first())
    }

    /** Position to save for a report of [generation]. */
    fun finalPosition(
        generation: Long,
        reportedTimelineMs: Long,
        rendererPositionMs: Long,
        requestedSeek: Boolean,
        ended: Boolean,
        stopped: Boolean,
    ): Long = if (stopped && rendererPositionKnown(rendererPositionMs, requestedSeek, ended).not())
        positions[generation] ?: reportedTimelineMs
    else reportedTimelineMs
}

/** False for a renderer that reports 0 without having ended and without a pending seek. */
internal fun rendererPositionKnown(rendererPositionMs: Long, requestedSeek: Boolean, ended: Boolean): Boolean =
    ended || requestedSeek || rendererPositionMs > 0L
