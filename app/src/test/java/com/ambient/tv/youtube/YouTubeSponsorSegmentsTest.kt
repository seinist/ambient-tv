package com.ambient.tv.youtube

import com.ambient.tv.domain.model.PlaybackSegment
import com.ambient.tv.domain.model.PlaybackTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class YouTubeSponsorSegmentsTest {
    @Test fun sponsorRangesAreConnectedToThePlayerTimeline() {
        val original = PlaybackTimeline(segments = listOf(PlaybackSegment("intro", 0, 10_000)))
        val result = youtubeSponsorTimeline(original, listOf(20_000L..35_000L, 80_000L..100_000L), 100_000L)
        assertEquals(listOf(
            PlaybackSegment("intro", 0, 10_000),
            PlaybackSegment("sponsor", 20_000L, 35_000L),
            PlaybackSegment("sponsor", 80_000L, 100_000L),
        ), result.segments)
    }

    @Test fun invalidAndOutsideDurationSegmentsCannotReachTheSeekPlayer() {
        val result = youtubeSponsorTimeline(PlaybackTimeline(),
            listOf(-1L..5_000L, 6_000L..6_000L, 50_000L..120_000L), 90_000L)
        assertEquals(listOf(PlaybackSegment("sponsor", 50_000L, 90_000L)), result.segments)
    }

    @Test fun noSponsorResponseKeepsTheExistingTimeline() {
        val original = PlaybackTimeline(segments = listOf(PlaybackSegment("credits", 80_000L, -1L)))
        assertSame(original, youtubeSponsorTimeline(original, emptyList(), 100_000L))
    }
}
