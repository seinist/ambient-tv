package com.ambient.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class YouTubeLocalProgressTest {
    @Test fun localPositionWinsUntilYouTubeReportsSomethingNew() {
        val id = "local-progress-a"
        YouTubeLocalProgress.observeRemote(id, 10)
        YouTubeLocalProgress.record(id, positionMs = 60_000, durationMs = 100_000)
        // YouTube has not applied the update yet: still the old value.
        assertEquals(60, YouTubeLocalProgress.percent(id, 10))
        YouTubeLocalProgress.observeRemote(id, 10)
        assertEquals(60, YouTubeLocalProgress.percent(id, 10))
        // Another device (or the synced update) changed YouTube's value: YouTube wins.
        YouTubeLocalProgress.observeRemote(id, 80)
        assertEquals(80, YouTubeLocalProgress.percent(id, 80))
    }

    @Test fun unknownRemoteAdoptsTheFirstReportedValue() {
        val id = "local-progress-b"
        YouTubeLocalProgress.record(id, positionMs = 30_000, durationMs = 100_000)
        assertEquals(30, YouTubeLocalProgress.percent(id, 0))
        YouTubeLocalProgress.observeRemote(id, 0)
        assertEquals(30, YouTubeLocalProgress.percent(id, 0))
    }

    @Test fun resumeUsesTheCardValueWhenWatchNextHasNone() {
        val id = "local-progress-c"
        YouTubeLocalProgress.observeRemote(id, 40)
        assertEquals(40_000L, YouTubeLocalProgress.resumePositionMs(id, 100_000, 0))
        assertEquals(40_000L, YouTubeLocalProgress.resumePositionMs(id, 100_000, 25))
    }

    @Test fun staleCachedCardsCannotInvalidateTheLastLocalPosition() {
        val id = "cached-card-local-position"
        YouTubeLocalProgress.observeRemote(id, 10)
        YouTubeLocalProgress.record(id, 60_500, 100_000)
        YouTubeLocalProgress.observeCached(id, 5)
        assertEquals(60, YouTubeLocalProgress.percent(id, 5))
        assertEquals(60_500L, YouTubeLocalProgress.resumePositionMs(id, 100_000, 5))
    }

    @Test fun exactPositionUpdatesEvenWithinTheSameWholePercent() {
        val id = "exact-local-progress"
        YouTubeLocalProgress.observeRemote(id, 0)
        YouTubeLocalProgress.record(id, 575_000, 3_600_000)
        assertEquals(15, YouTubeLocalProgress.percent(id, 0))
        assertEquals(575_000L, YouTubeLocalProgress.resumePositionMs(id, 3_600_000))
        YouTubeLocalProgress.record(id, 575_999, 3_600_000)
        assertEquals(575_999L, YouTubeLocalProgress.resumePositionMs(id, 3_600_000))
    }

    @Test fun acknowledgementKeepsExactPositionButAnotherDevicesChangeWins() {
        val id = "acknowledged-local-progress"
        YouTubeLocalProgress.observeRemote(id, 10)
        YouTubeLocalProgress.record(id, 60_555, 100_000)
        YouTubeLocalProgress.observeRemote(id, 60)
        assertEquals(60_555L, YouTubeLocalProgress.resumePositionMs(id, 100_000))
        YouTubeLocalProgress.observeRemote(id, 25) // Deliberately rewound on another device.
        assertEquals(25, YouTubeLocalProgress.percent(id, 10))
        assertEquals(25_000L, YouTubeLocalProgress.resumePositionMs(id, 100_000, 60))
    }

    @Test fun freshCardUpdatesEveryCachedCopyAndCanResetAStalePositiveResume() {
        val id = "fresh-card-reset"
        YouTubeLocalProgress.observeCached(id, 75)
        YouTubeLocalProgress.observeRemote(id, 0)
        assertEquals(0, YouTubeLocalProgress.percent(id, 75))
        assertEquals(0L, YouTubeLocalProgress.resumePositionMs(id, 100_000, 75))
    }

    @Test fun cachedCardSeedsResumeWithoutPretendingItIsANewServerUpdate() {
        val id = "cached-card-seed"
        YouTubeLocalProgress.record(id, 30_555, 100_000)
        YouTubeLocalProgress.observeCached(id, 10)
        assertEquals(30, YouTubeLocalProgress.percent(id, 10))
        assertEquals(30_555L, YouTubeLocalProgress.resumePositionMs(id, 100_000))
        val cachedOnly = "cached-card-without-local"
        YouTubeLocalProgress.observeCached(cachedOnly, 40)
        assertEquals(40_000L, YouTubeLocalProgress.resumePositionMs(cachedOnly, 100_000))
    }

    @Test fun unknownProgressIsDifferentFromAKnownZero() {
        val id = "unknown-card-progress"
        assertNull(YouTubeLocalProgress.resumePositionMs(id, 100_000))
        YouTubeLocalProgress.observeRemote(id, -1)
        assertNull(YouTubeLocalProgress.resumePositionMs(id, 100_000))
        YouTubeLocalProgress.observeCached(id, 0)
        assertEquals(0L, YouTubeLocalProgress.resumePositionMs(id, 100_000, 50))
    }

    @Test fun completingVideoReplacesPartialProgressAndSurvivesStaleCards() {
        val id = "completed-progress"
        YouTubeLocalProgress.observeRemote(id, 30)
        YouTubeLocalProgress.record(id, 30_000, 100_000)
        YouTubeLocalProgress.record(id, 80_000, 100_000, completed = true)
        YouTubeLocalProgress.observeCached(id, 30)
        YouTubeLocalProgress.observeRemote(id, 30) // Server has not applied the finish yet.
        assertEquals(100, YouTubeLocalProgress.percent(id, 30))
        assertEquals(0L, YouTubeLocalProgress.resumePositionMs(id, 100_000, 30))
        YouTubeLocalProgress.observeRemote(id, 100)
        assertEquals(100, YouTubeLocalProgress.percent(id, 30))
        assertEquals(0L, YouTubeLocalProgress.resumePositionMs(id, 100_000))
    }

    @Test fun completionWithoutKnownDurationStillMarksSeen() {
        val id = "completed-progress-no-duration"
        YouTubeLocalProgress.record(id, 80_000, 0, completed = true)
        assertEquals(100, YouTubeLocalProgress.percent(id, 25))
        assertEquals(0L, YouTubeLocalProgress.resumePositionMs(id, 0))
    }

    @Test fun replayCanSaveANewPositionAfterCompletion() {
        val id = "completed-video-replay"
        YouTubeLocalProgress.observeRemote(id, 100)
        YouTubeLocalProgress.record(id, 100_000, 100_000, completed = true)
        YouTubeLocalProgress.record(id, 15_500, 100_000)
        assertEquals(15, YouTubeLocalProgress.percent(id, 100))
        assertEquals(15_500L, YouTubeLocalProgress.resumePositionMs(id, 100_000))
    }

    @Test fun delayedOwnIntermediateReportsCannotUndoCompletionEvenAfterAcknowledgement() {
        val id = "completion-delayed-intermediate-report"
        YouTubeLocalProgress.observeRemote(id, 10)
        YouTubeLocalProgress.record(id, 60_000, 100_000)
        YouTubeLocalProgress.record(id, 80_000, 100_000, completed = true)
        YouTubeLocalProgress.observeRemote(id, 60)
        assertEquals(100, YouTubeLocalProgress.percent(id, 60))
        assertEquals(0L, YouTubeLocalProgress.resumePositionMs(id, 100_000))
        YouTubeLocalProgress.observeRemote(id, 100)
        YouTubeLocalProgress.observeRemote(id, 10) // Older feed request returns after the ack.
        assertEquals(100, YouTubeLocalProgress.percent(id, 10))
        YouTubeLocalProgress.observeRemote(id, 60)
        assertEquals(100, YouTubeLocalProgress.percent(id, 60))
        YouTubeLocalProgress.observeRemote(id, 35) // A genuinely different device position.
        assertEquals(35, YouTubeLocalProgress.percent(id, 60))
    }

    @Test fun delayedCompletionAcknowledgementCannotEraseANewReplayPosition() {
        val id = "replay-delayed-completion-report"
        YouTubeLocalProgress.observeRemote(id, 10)
        YouTubeLocalProgress.record(id, 100_000, 100_000, completed = true)
        YouTubeLocalProgress.record(id, 15_500, 100_000)
        YouTubeLocalProgress.observeRemote(id, 100)
        assertEquals(15, YouTubeLocalProgress.percent(id, 100))
        assertEquals(15_500L, YouTubeLocalProgress.resumePositionMs(id, 100_000))
    }

    @Test fun delayedReportProtectionExpiresAndDoesNotHideAnUnrelatedRemoteChange() {
        assertTrue(youtubePendingProgressEcho(60, setOf(10, 60), 1_000, 121_000))
        assertFalse(youtubePendingProgressEcho(60, setOf(10, 60), 1_000, 121_001))
        assertFalse(youtubePendingProgressEcho(35, setOf(10, 60), 1_000, 2_000))
        assertFalse(youtubePendingProgressEcho(60, setOf(10, 60), 1_000, 999))
    }
}
