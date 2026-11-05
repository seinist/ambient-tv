package com.ambient.tv.youtube

import org.junit.Assert.*
import org.junit.Test

class YouTubeResumeTest {
    private fun stream(live: Boolean = false) = YouTubePlayback("video", "SmartTube", uri = "https://fixture.invalid/video.mpd", isLive = live)
    private fun metadata() = YouTubeMetadata("video", "Title", durationMs = 120_000, percentWatched = 25)

    @Test fun partlyWatchedResumes() {
        assertEquals(30_000L, youtubeResumeMs(120_000, 25, isLive = false))
        assertEquals(112_800L, youtubeResumeMs(120_000, 94, isLive = false))
    }

    @Test fun finishedVideosStartFromTheBeginning() {
        assertEquals(0L, youtubeResumeMs(120_000, 95, isLive = false))
        assertEquals(0L, youtubeResumeMs(120_000, 100, isLive = false))
    }

    @Test fun liveAndUnknownStartAtZero() {
        assertEquals(0L, youtubeResumeMs(120_000, 50, isLive = true))
        assertEquals(0L, youtubeResumeMs(120_000, null, isLive = false))
    }

    @Test fun knownSavedResumeRewindsOnceAndMetadataCannotApplyItAgain() {
        val loaded = applyYouTubePlaybackMetadata(stream(), metadata())
        val resumed = prepareYouTubeSavedResume(loaded, 10)
        assertEquals(20_000L, resumed.resumeMs)
        assertTrue(resumed.resumeResolved)
        val enriched = applyYouTubePlaybackMetadata(resumed, metadata().copy(percentWatched = 50))
        assertEquals(20_000L, enriched.resumeMs)
        assertTrue(enriched.resumeResolved)
    }
    @Test fun lateSavedProgressUsesTheCapturedRewindAndThenLocksTheStart() {
        val waiting = prepareYouTubeSavedResume(stream(), 10)
        assertFalse(waiting.resumeResolved)
        val resolved = applyYouTubePlaybackMetadata(waiting, metadata())
        assertEquals(20_000L, resolved.resumeMs)
        assertTrue(resolved.resumeResolved)
        assertEquals(20_000L, applyYouTubePlaybackMetadata(resolved, metadata()).resumeMs)
    }
    @Test fun savedResumeClampedToZeroStillRejectsLaterHistoricalPositions() {
        val loaded = applyYouTubePlaybackMetadata(stream(), metadata().copy(percentWatched = 5))
        val resumed = prepareYouTubeSavedResume(loaded, 10)
        assertEquals(0L, resumed.resumeMs)
        assertTrue(resumed.resumeResolved)
        val later = applyYouTubePlaybackMetadata(resumed, metadata().copy(percentWatched = 50))
        assertEquals(0L, later.resumeMs)
        assertNull(lateYouTubeResumeTarget(false, 0, 60_000, false, resumeAlreadyApplied = true))
    }
    @Test fun explicitChapterAndRunningSessionPositionsSurviveMetadataEnrichment() {
        for (position in listOf(0L, 45_000L)) {
            val explicit = stream().copy(resumeMs = position, resumeResolved = true)
            assertEquals(position, applyYouTubePlaybackMetadata(explicit, metadata()).resumeMs)
        }
    }
    @Test fun liveHistoryAndInFlightMetadataCannotTurnAnOldClockIntoASeek() {
        val stale = stream(live = true).copy(
            durationMs = 7_200_000, resumeMs = 3_600_000,
            resumeResolved = true, resumeRewindSeconds = 10,
        )
        val immediate = youtubeAtLiveEdge(stale)
        assertEquals(0L, immediate.resumeMs)
        assertEquals(0L, immediate.durationMs)
        assertTrue(immediate.resumeResolved)
        assertEquals(0, immediate.resumeRewindSeconds)
        val enriched = applyYouTubePlaybackMetadata(stale,
            metadata().copy(durationMs = 5_400_000, percentWatched = 72))
        assertEquals(0L, enriched.resumeMs)
        assertEquals(0L, enriched.durationMs)
        assertTrue(enriched.resumeResolved)
        assertEquals(0, enriched.resumeRewindSeconds)
        assertTrue(enriched.timeline.chapters.isEmpty())
    }

    @Test fun completedBroadcastThatIsNowVodCanStillResume() {
        val archived = stream(live = false).copy(durationMs = 3_600_000)
        val enriched = applyYouTubePlaybackMetadata(archived,
            metadata().copy(durationMs = 3_600_000, percentWatched = 50))
        assertFalse(enriched.isLive)
        assertEquals(1_800_000L, enriched.resumeMs)
        assertEquals(1_800_000L, youtubeAtLiveEdge(enriched).resumeMs)
    }

    @Test fun publicationDateFromWatchNextEnrichesPlaybackWithoutChangingResumeOrSource() {
        val original = stream().copy(publishedDate = "vor 2 Tagen")
        val updated = applyYouTubePlaybackMetadata(original, metadata().copy(
            publishedDate = "  12.10.2026  ",
        ))
        assertEquals("12.10.2026", updated.publishedDate)
        assertEquals(original.uri, updated.uri)
        assertEquals(30_000L, updated.resumeMs)
        // Incomplete watch-next data may arrive later; it must not erase a valid early label.
        val blank = applyYouTubePlaybackMetadata(updated, metadata().copy(publishedDate = "  "))
        assertEquals("12.10.2026", blank.publishedDate)
    }

    @Test fun earlyPublicationLabelRemainsAvailableWhenWatchNextMetadataIsMissing() {
        val original = stream().copy(publishedDate = "vor 3 Tagen")
        assertEquals(original.publishedDate, applyYouTubePlaybackMetadata(original, null).publishedDate)
        assertEquals(original.publishedDate, applyYouTubePlaybackMetadata(original, metadata()).publishedDate)
        assertNull(applyYouTubePlaybackMetadata(stream(), metadata()).publishedDate)
    }

    @Test fun providerPublicationTimesHandleSecondsMillisAndRejectUntrustedNumbers() {
        assertEquals(1_700_000_000_000L, youtubePublicationEpochMs(1_700_000_000L))
        assertEquals(1_700_000_000_000L, youtubePublicationEpochMs(1_700_000_000_000L))
        assertNull(youtubePublicationEpochMs(0L))
        assertNull(youtubePublicationEpochMs(999_999_999_999_999L))
        assertEquals("vor 3 Tagen", youtubePublicationText("  vor 3 Tagen  "))
        assertEquals("2026-10-18", youtubePublicationText("2026-10-18"))
        assertNull(youtubePublicationText("1700000000000"))
        assertNull(youtubePublicationText("0"))
        assertNull(youtubePublicationText("unknown"))
    }

    @Test fun lateAbsoluteDateIsKeptWithoutCreatingAStringFromAnEpochNumber() {
        val instant = 1_700_000_000_000L
        val loaded = applyYouTubePlaybackMetadata(stream(), metadata().copy(
            publishedDate = "1700000000000", publishedAtMs = instant,
        ))
        assertNull(loaded.publishedDate)
        assertEquals(instant, loaded.publishedAtMs)
        val unchanged = applyYouTubePlaybackMetadata(loaded, metadata())
        assertEquals(instant, unchanged.publishedAtMs)
    }

    @Test fun disabledRewindAndLivePlaybackKeepTheirExistingStarts() {
        val vod = prepareYouTubeSavedResume(applyYouTubePlaybackMetadata(stream(), metadata()), 0)
        assertEquals(30_000L, vod.resumeMs)
        val live = prepareYouTubeSavedResume(stream(live = true).copy(resumeMs = 30_000), 10)
        assertEquals(0L, live.resumeMs)
        assertTrue(live.resumeResolved)
    }
}
