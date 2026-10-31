package com.ambient.tv.youtube

import com.ambient.tv.domain.model.PlaybackSegment
import com.ambient.tv.domain.model.PlaybackTimeline

/**
 * SmartTube returns segment boundaries without exposing their category. Keep them as
 * SponsorBlock ranges instead of claiming they are intros or credits. They can update after
 * playback has already begun and are delivered to the shared segment-skip overlay.
 */
internal fun youtubeSponsorTimeline(
    timeline: PlaybackTimeline,
    ranges: List<LongRange>,
    durationMs: Long,
): PlaybackTimeline {
    if (ranges.isEmpty()) return timeline
    val sponsorSegments = ranges.mapNotNull { range ->
        val start = range.first
        val end = if (durationMs > 0) range.last.coerceAtMost(durationMs) else range.last
        if (start < 0 || end <= start) null else PlaybackSegment("sponsor", start, end)
    }
    return timeline.copy(segments = timeline.segments + sponsorSegments).normalized(durationMs)
}
