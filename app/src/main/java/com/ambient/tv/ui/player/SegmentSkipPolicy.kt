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
