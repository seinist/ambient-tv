package com.ambient.tv.youtube

import com.ambient.tv.domain.model.PlaybackSegment
import com.ambient.tv.domain.model.PlaybackTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class YouTubeSponsorSegmentsTest {
    @Test fun sponsorRangesAreConnectedToThePlayerTimeline() {
        val original = PlaybackTimeline(segments = listOf(PlaybackSegment("intro", 0, 10_000)))
        val result = youtubeSponsorTimeline(original, listOf(com.ambient.tv.youtube.YouTubeSponsorSegment(20_000L, 35_000L, "sponsor"), com.ambient.tv.youtube.YouTubeSponsorSegment(80_000L, 100_000L, "sponsor")), 100_000L)
        assertEquals(listOf(
            PlaybackSegment("intro", 0, 10_000),
            PlaybackSegment("sponsor:sponsor", 20_000L, 35_000L),
            PlaybackSegment("sponsor:sponsor", 80_000L, 100_000L),
        ), result.segments)
    }

    @Test fun invalidAndOutsideDurationSegmentsCannotReachTheSeekPlayer() {
        val result = youtubeSponsorTimeline(PlaybackTimeline(),
            listOf(com.ambient.tv.youtube.YouTubeSponsorSegment(-1L, 5_000L, "sponsor"), com.ambient.tv.youtube.YouTubeSponsorSegment(6_000L, 6_000L, "sponsor"), com.ambient.tv.youtube.YouTubeSponsorSegment(50_000L, 120_000L, "sponsor")), 90_000L)
        assertEquals(listOf(PlaybackSegment("sponsor:sponsor", 50_000L, 90_000L)), result.segments)
    }

    @Test fun noSponsorResponseKeepsTheExistingTimeline() {
        val original = PlaybackTimeline(segments = listOf(PlaybackSegment("credits", 80_000L, -1L)))
        assertSame(original, youtubeSponsorTimeline(original, emptyList(), 100_000L))
    }

    @Test fun categoriesKeepTheirTypeAndOffCategoriesAreDropped() {
        val modes = SponsorBlockCategories.DEFAULTS + ("intro" to "off")
        val result = youtubeSponsorTimeline(PlaybackTimeline(), listOf(
            YouTubeSponsorSegment(1_000L, 5_000L, "intro"),
            YouTubeSponsorSegment(10_000L, 20_000L, "selfpromo"),
        ), 60_000L, modes)
        assertEquals(listOf(PlaybackSegment("sponsor:selfpromo", 10_000L, 20_000L)), result.segments)
        assertEquals("selfpromo", sponsorCategoryOf("sponsor:selfpromo"))
    }

    @Test fun storedModesRoundTripAndFallBackToDefaults() {
        val parsed = SponsorBlockCategories.parse("sponsor=button,bogus=auto,intro=nonsense")
        assertEquals("button", parsed["sponsor"])
        assertEquals(SponsorBlockCategories.DEFAULTS["intro"], parsed["intro"])
        assertEquals(parsed, SponsorBlockCategories.parse(SponsorBlockCategories.format(parsed)))
    }
}
