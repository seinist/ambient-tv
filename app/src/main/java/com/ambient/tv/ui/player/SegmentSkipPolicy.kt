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
    if (durationMs <= 0 || durationMs == Long.MAX_VALUE || endMs < durationMs - SEGMENT_END_PROXIMITY_MS) return endMs
    return safePlaybackEndSeekTarget(currentMs, durationMs)
}

private const val SEGMENT_END_PROXIMITY_MS = 750L
private const val SEGMENT_REWIND_LEAD_MS = 1_000L
/** End-near skips must never be re-armed by a decoder briefly reporting an older keyframe. */
private const val SPONSOR_NEAR_EOF_REARM_GUARD_MS = 5_000L

/**
 * A hidden Next Up overlay should not strand autoplay behind an automatic SponsorBlock
 * segment covering the actual EOF. There is no meaningful content after this range: let
 * the shared Next Up handoff happen instead of repeatedly seeking into the same tail.
 *
 * This is deliberately stricter than the re-arm guard: a sponsor ending several seconds
 * *before* EOF may be followed by a real post-credit scene which must still be played.
 */
internal fun terminalSponsorAutoAdvance(
    positionMs: Long,
    durationMs: Long,
    ranges: List<com.ambient.tv.youtube.YouTubeSponsorSegment>,
    modes: Map<String, String>,
    autoplay: Boolean,
    showOverlay: Boolean,
    stillWatching: Boolean,
): Boolean {
    if (!autoplay || showOverlay || stillWatching || durationMs <= 0L ||
        positionMs < 0L || positionMs >= durationMs) return false
    return ranges.any { range ->
        modes[range.category] == "auto" &&
            range.startMs >= 0L && range.startMs < durationMs &&
            range.endMs >= durationMs - SEGMENT_END_PROXIMITY_MS &&
            positionMs >= range.startMs && positionMs < range.endMs
    }
}

/**
 * A user-directed seek always uses the original media clock (never subtract skipped time).
 * If its target falls inside an automatically skipped SponsorBlock range, land at the end
 * instead. For backwards navigation land briefly before the segment start, otherwise repeated
 * rewind steps would get stuck at its end. Button/mark/off categories stay seekable.
 * Handle overlapping auto-ranges in traversal order and keep the safe EOF tail.
 */
internal fun sponsorBlockSeekTarget(
    requestedMs: Long,
    ranges: List<com.ambient.tv.youtube.YouTubeSponsorSegment>,
    durationMs: Long,
    modes: Map<String, String>,
    backward: Boolean = false,
): Long {
    val target = requestedMs.coerceIn(0L, durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
    if (ranges.isEmpty() || durationMs <= 0L) return target
    val segments = com.ambient.tv.youtube.youtubeSponsorTimeline(
        com.ambient.tv.domain.model.PlaybackTimeline(), ranges, durationMs, modes,
    ).segments
    var landing = target
    for (segment in if (backward) segments.asReversed() else segments) {
        if (com.ambient.tv.youtube.sponsorCategoryOf(segment.type)?.let(modes::get) != "auto" ||
            landing < segment.startMs || landing >= segment.endMs) continue
        landing = if (backward) (segment.startMs - SEGMENT_REWIND_LEAD_MS).coerceAtLeast(0L)
        else safeSegmentSkipTarget(segment.endMs, landing, durationMs) ?: landing
    }
    return landing
}

/**
 * SponsorBlock is owned by the background playback service, not the Compose skip overlay:
 * this keeps skipping functional in PiP/background and prevents two concurrent seeks.
 * Mark a segment before seeking, including when the terminal safety tail is too short.
 */
internal class BackgroundSponsorSkipPolicy {
    private var generation: Long? = null
    private val handledStarts = mutableSetOf<Long>()
    /** Terminal skips stay handled for the whole item, even after optimistic outside samples. */
    private val terminalHandledStarts = mutableSetOf<Long>()
    // A decoder may briefly report a seek target outside a range before falling back to its
    // preceding keyframe. Require two outside polls to re-arm, not a single optimistic sample.
    private val outsideSamples = mutableMapOf<Long, Int>()

    fun nextSeek(
        playbackGeneration: Long,
        ranges: List<com.ambient.tv.youtube.YouTubeSponsorSegment>,
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
        /** Only categories set to "auto" are skipped here; "button" belongs to the overlay. */
        modes: Map<String, String> = com.ambient.tv.youtube.SponsorBlockCategories.DEFAULTS,
    ): Long? {
        if (generation != playbackGeneration) {
            generation = playbackGeneration
            handledStarts.clear()
            terminalHandledStarts.clear()
            outsideSamples.clear()
        }
        // Without a known EOF, a segment's end may be the decoder's final timestamp.
        // Wait for playback duration rather than risking an exact-end seek.
        if (ranges.isEmpty() || durationMs <= 0L) return null
        val segments = com.ambient.tv.youtube.youtubeSponsorTimeline(
            com.ambient.tv.domain.model.PlaybackTimeline(), ranges, durationMs, modes,
        ).segments.filter { com.ambient.tv.youtube.sponsorCategoryOf(it.type)?.let(modes::get) == "auto" }
        // Previously skipped ranges must become actionable again after playback actually
        // leaves them. Otherwise a later user seek back into the same sponsor is never skipped.
        for (start in handledStarts.toList()) {
            // Near EOF, a seek may land on an older keyframe or temporarily report a
            // position past the segment; re-arming would skip the same final seconds forever.
            // Manual seeks are redirected separately by sponsorBlockSeekTarget.
            if (start in terminalHandledStarts) continue
            val stillInside = segments.any { it.startMs == start && positionMs >= it.startMs && positionMs < it.endMs }
            if (stillInside) {
                outsideSamples.remove(start)
            } else {
                val samples = (outsideSamples[start] ?: 0) + 1
                if (samples >= 2) {
                    handledStarts.remove(start)
                    outsideSamples.remove(start)
                } else outsideSamples[start] = samples
            }
        }
        if (!playing) return null
        val active = segments.firstOrNull {
            positionMs >= it.startMs && positionMs < it.endMs && it.startMs !in handledStarts
        } ?: return null
        handledStarts.add(active.startMs)
        if (active.endMs >= durationMs - SPONSOR_NEAR_EOF_REARM_GUARD_MS) {
            terminalHandledStarts.add(active.startMs)
        }
        return safeSegmentSkipTarget(active.endMs, positionMs, durationMs)
    }
}
