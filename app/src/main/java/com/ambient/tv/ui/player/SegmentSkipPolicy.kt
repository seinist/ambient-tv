package com.ambient.tv.ui.player

import com.ambient.tv.domain.model.PlaybackSegment

/** Lifetime is the media selection, not the decoder: a renderer switch must not re-arm auto skip. */
internal class SegmentSkipPolicy {
    private val handled = mutableSetOf<PlaybackSegment>()
    private var previousPosition: Long? = null
    fun active(segments: List<PlaybackSegment>, positionMs: Long): PlaybackSegment? =
        segments.firstOrNull { positionMs >= it.startMs && (it.endMs == -1L || positionMs < it.endMs) }
    fun automaticTarget(segments: List<PlaybackSegment>, positionMs: Long, playing: Boolean): PlaybackSegment? {
        val previous = previousPosition
        previousPosition = positionMs
        // Explicit backwards seeks should let the viewer watch the intro.
        if (previous != null && positionMs < previous - 1_000) {
            handled.addAll(segments.filter { it.startMs < previous && (it.endMs == -1L || it.endMs > positionMs) })
        }
        val segment = active(segments, positionMs) ?: return null
        return segment.takeIf { playing && handled.add(it) }
    }
    fun handled(segment: PlaybackSegment) { handled.add(segment) }
}

/**
 * Seeking to the exact final DASH/HLS timestamp can leave YouTube/other decoders buffering
 * forever without emitting EOF. Stop just short of the end so the renderer reaches EOF itself.
 * A final segment shorter than this safety tail should simply play out, never seek backward.
 */
internal fun safeSegmentSkipTarget(endMs: Long, currentMs: Long, durationMs: Long): Long? {
    if (endMs < 0) return null // Open-ended credits are handled by Next Up, not a seek.
    if (durationMs <= 0 || endMs < durationMs - SEGMENT_END_PROXIMITY_MS) return endMs
    val safe = (durationMs - SEGMENT_END_TAIL_MS).coerceAtLeast(0L)
    return safe.takeIf { it > currentMs + SEGMENT_MIN_FORWARD_MS }
}

private const val SEGMENT_END_PROXIMITY_MS = 750L
private const val SEGMENT_END_TAIL_MS = 1_500L
private const val SEGMENT_MIN_FORWARD_MS = 250L

