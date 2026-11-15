package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTimelineDurationTest {
    @Test fun roundedMetadataSeedsTheTimelineButDoesNotReplaceAKnownRendererDuration() {
        val initial = PlayerTimelineDuration().update("youtube:video", 0L, fallbackDurationMs = 240_000L)
        assertEquals(240_000L, initial.durationMs)
        val precise = initial.update("youtube:video", 239_576L, fallbackDurationMs = 240_000L)
        assertEquals(239_576L, precise.durationMs)
        assertEquals(239_576L, precise.update("youtube:video", 0L, fallbackDurationMs = 240_000L).durationMs)
        assertEquals(90_000L, precise.update("youtube:next", 0L, fallbackDurationMs = 90_000L).durationMs)
        assertEquals(0L, precise.update("youtube:live", 0L, retainDuration = false, fallbackDurationMs = 90_000L).durationMs)
    }

    @Test fun anUnknownInitialDurationHasNoInventedTimelineLength() {
        val initial = PlayerTimelineDuration()
        assertEquals(0L, initial.update("youtube:first", 0L).durationMs)
        assertEquals(0L, initial.update("youtube:first", -1L).durationMs)
        assertEquals(0L, initial.update("youtube:first", Long.MIN_VALUE + 1L).durationMs)
    }

    @Test fun lateMetadataReplacesTheLoadingStateWithTheRealDuration() {
        val loading = PlayerTimelineDuration().update("youtube:first", 0L)
        val ready = loading.update("youtube:first", 180_000L)
        assertEquals("youtube:first", ready.sourceIdentity)
        assertEquals(180_000L, ready.durationMs)
    }

    @Test fun temporaryMetadataLossDoesNotRemoveAnEstablishedTimeline() {
        var state = PlayerTimelineDuration().update("telegram:video", 240_000L)
        // Buffering and a replacement renderer may each publish a missing duration.
        for (missing in listOf(0L, -1L, 0L, Long.MIN_VALUE + 1L)) {
            state = state.update("telegram:video", missing)
            assertEquals(240_000L, state.durationMs)
        }
    }

    @Test fun aNewPositiveDurationCanCorrectRoundedMetadataInEitherDirection() {
        val rounded = PlayerTimelineDuration().update("youtube:video", 240_000L)
        val shorter = rounded.update("youtube:video", 239_576L)
        assertEquals(239_576L, shorter.durationMs)
        assertEquals(240_128L, shorter.update("youtube:video", 240_128L).durationMs)
    }

    @Test fun aDifferentVideoNeverInheritsThePreviousVideosLength() {
        val first = PlayerTimelineDuration().update("youtube:first", 3_600_000L)
        val secondLoading = first.update("youtube:second", 0L)
        assertEquals("youtube:second", secondLoading.sourceIdentity)
        assertEquals(0L, secondLoading.durationMs)
        assertEquals(90_000L, secondLoading.update("youtube:second", 90_000L).durationMs)
    }

    @Test fun anotherVersionOfTheSameMovieGetsItsOwnTimeline() {
        val theatrical = PlayerTimelineDuration().update("plex:42:version:0", 6_000_000L)
        val directorsCut = theatrical.update("plex:42:version:1", -1L)
        assertEquals(0L, directorsCut.durationMs)
        assertEquals(7_200_000L, directorsCut.update("plex:42:version:1", 7_200_000L).durationMs)
    }

    @Test fun returningThroughAnotherItemDoesNotResurrectAnOldDuration() {
        val first = PlayerTimelineDuration().update("file:first", 60_000L)
        val second = first.update("file:second", 0L)
        val returning = second.update("file:first", 0L)
        assertEquals(0L, returning.durationMs)
        assertEquals(65_000L, returning.update("file:first", 65_000L).durationMs)
    }

    @Test fun aReplacementSourceWithKnownMetadataStartsAtItsOwnDuration() {
        val first = PlayerTimelineDuration().update("file:first", 60_000L)
        val second = first.update("file:second", 15_000L)
        assertEquals("file:second", second.sourceIdentity)
        assertEquals(15_000L, second.durationMs)
    }

    @Test fun liveWindowsCanShrinkAndDisappearWithoutRetainingAStaleSeekRange() {
        val live = PlayerTimelineDuration().update("youtube:live", 120_000L, retainDuration = false)
        val shorter = live.update("youtube:live", 90_000L, retainDuration = false)
        assertEquals(90_000L, shorter.durationMs)
        assertEquals(0L, shorter.update("youtube:live", 0L, retainDuration = false).durationMs)
        assertEquals(0L, shorter.update("youtube:live", -1L, retainDuration = false).durationMs)
    }

    @Test fun visitingAnUnlatchedLiveSourceClearsThePreviousVodDuration() {
        val vod = PlayerTimelineDuration().update("youtube:vod", 600_000L)
        val live = vod.update("youtube:live", 0L, retainDuration = false)
        assertEquals("youtube:live", live.sourceIdentity)
        assertEquals(0L, live.durationMs)
        assertEquals(0L, live.update("youtube:vod", 0L).durationMs)
    }
}
