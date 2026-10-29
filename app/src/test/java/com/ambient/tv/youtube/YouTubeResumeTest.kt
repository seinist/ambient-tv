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
    @Test fun disabledRewindAndLivePlaybackKeepTheirExistingStarts() {
        val vod = prepareYouTubeSavedResume(applyYouTubePlaybackMetadata(stream(), metadata()), 0)
        assertEquals(30_000L, vod.resumeMs)
        val live = prepareYouTubeSavedResume(stream(live = true).copy(resumeMs = 30_000), 10)
        assertEquals(0L, live.resumeMs)
        assertTrue(live.resumeResolved)
    }
}
