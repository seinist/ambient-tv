package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSeekStateTest {
    private var now = 0L
    private val commits = mutableListOf<Long>()
    private fun seekState(engineMs: Long) =
        PlaybackSeekState(PlaybackSeekCoordinator(nowMs = { now }), engineMs) { commits += it }

    @Test fun quickSeeksStackWhileTheEngineStillReportsThePreSeekPosition() {
        val seek = seekState(741_000)
        seek.seekBy(30_000, 3_600_000)
        now = 230
        seek.sync(741_200)
        assertEquals(771_000L, seek.positionMs)
        seek.seekBy(30_000, 3_600_000)
        assertEquals(801_000L, seek.positionMs)
        now = 230 + PlaybackSeekCoordinator.DEBOUNCE_MS
        seek.commitSettled()
        assertEquals(listOf(771_000L, 801_000L), commits)
    }

    @Test fun aBackwardJumpAfterForwardSeeksStartsFromTheRequestedTime() {
        val seek = seekState(741_000)
        seek.seekBy(30_000, 3_600_000)
        seek.seekBy(30_000, 3_600_000)
        now = 650
        seek.sync(771_000)
        seek.seekBy(-60_000, 3_600_000)
        assertEquals(741_000L, seek.positionMs)
    }

    @Test fun theOsdHandsBackToTheEngineOncePlaybackReachesTheTarget() {
        val seek = seekState(100_000)
        seek.seekBy(10_000, 3_600_000)
        now = 400
        seek.sync(109_300)
        assertEquals(110_000L, seek.positionMs)
        now = 1_000
        seek.sync(109_900)
        // 109_900 has been inside the keyframe window, and moving, long enough to land.
        assertEquals(109_900L, seek.positionMs)
        now = 1_500
        seek.sync(110_500)
        assertEquals(110_500L, seek.positionMs)
    }

    @Test fun anAbsoluteSeekCommitsTheTimelineTarget() {
        val seek = seekState(500_000)
        seek.seekTo(0L)
        assertEquals(0L, seek.positionMs)
        assertEquals(listOf(0L), commits)
    }

    @Test fun playFromStartReplacesPendingSeekAndCommitsBeforePlaying() {
        val actions = mutableListOf<String>()
        val seek = PlaybackSeekState(PlaybackSeekCoordinator(nowMs = { now }), 500_000,
            onSeekRequested = { actions += "user-seek" },
            commit = { actions += "seek:$it" })
        seek.seekBy(10_000, 900_000)
        now = 40
        seek.seekBy(10_000, 900_000)
        actions.clear()

        seek.playFromStart { actions += "play" }

        assertEquals(listOf("user-seek", "seek:0", "play"), actions)
        assertEquals(0L, seek.positionMs)
        assertEquals(0L, seek.pendingTargetMs)
        seek.commitSettled()
        assertEquals(listOf("user-seek", "seek:0", "play"), actions)
    }

    @Test fun retiredOsdCannotRestartOrResumeTheNextItem() {
        var current = true
        var plays = 0
        var interactions = 0
        val seek = PlaybackSeekState(PlaybackSeekCoordinator(nowMs = { now }), 500_000,
            onSeekRequested = { interactions++ }, isCurrent = { current },
            commit = { commits += it })
        current = false

        seek.playFromStart { plays++ }

        assertTrue(commits.isEmpty())
        assertEquals(0, plays)
        assertEquals(0, interactions)
    }

    @Test fun immediateExitFlushesLatestTargetWithoutWaitingForDebounce() {
        val seek = seekState(100_000)
        seek.seekBy(10_000, 3_600_000)
        now = 40
        seek.seekBy(10_000, 3_600_000)
        assertEquals(listOf(110_000L), commits)
        assertEquals(120_000L, seek.pendingTargetMs)
        seek.flushPending()
        seek.flushPending()
        assertEquals(listOf(110_000L, 120_000L), commits)
    }

    @Test fun chapterStepsUsePendingAbsoluteTimelineAcrossTranscodeOffset() {
        val timeline = com.ambient.tv.domain.model.PlaybackTimeline(listOf(
            com.ambient.tv.domain.model.VideoChapter(0),
            com.ambient.tv.domain.model.VideoChapter(120_000),
            com.ambient.tv.domain.model.VideoChapter(480_000),
            com.ambient.tv.domain.model.VideoChapter(600_000),
        ))
        // Engine time is 20s with a 480s transcode offset; seek state uses original media time.
        val seek = seekState(500_000)
        seek.seekBy(checkNotNull(timeline.chapterStepDelta(seek.positionMs, 1)), 900_000)
        assertEquals(600_000L, seek.positionMs)
        now = 40
        seek.sync(500_100) // Engine has not reached the chapter yet.
        seek.seekBy(checkNotNull(timeline.chapterStepDelta(seek.positionMs, -1)), 900_000)
        assertEquals(480_000L, seek.positionMs)
        seek.seekBy(checkNotNull(timeline.chapterStepDelta(seek.positionMs, -1)), 900_000)
        seek.flushPending()
        assertEquals(120_000L, seek.positionMs)
        assertEquals(listOf(600_000L, 120_000L), commits)
    }

    @Test fun pendingResumeTargetUsesAbsoluteTimelineEvenBeforeTranscodeOffset() {
        val seek = seekState(500_000)
        seek.seekTo(480_000)
        now = 40
        seek.seekTo(120_000)
        assertEquals(120_000L, seek.pendingTargetMs)
        seek.flushPending()
        assertEquals(listOf(480_000L, 120_000L), commits)
    }

    @Test fun finalChapterSkipUsesItsRequestedChapterAfterAnEarlierKeyframeLanding() {
        val timeline = com.ambient.tv.domain.model.PlaybackTimeline(listOf(
            com.ambient.tv.domain.model.VideoChapter(0),
            com.ambient.tv.domain.model.VideoChapter(20_000),
            com.ambient.tv.domain.model.VideoChapter(50_000),
        ))
        val seek = seekState(25_000)
        seek.seekBy(checkNotNull(timeline.chapterStepDelta(seek.positionMs, 1, 90_000, seek.chapterPositionMs)), 90_000)
        now = 800
        seek.sync(48_000)
        now += PlaybackSeekCoordinator.ACK_STABILITY_MS
        seek.sync(48_600)
        assertEquals(48_600L, seek.positionMs)
        assertEquals(50_000L, seek.chapterPositionMs)
        seek.seekBy(checkNotNull(timeline.chapterStepDelta(seek.positionMs, 1, 90_000, seek.chapterPositionMs)), 90_000)
        assertEquals(90_000L, seek.positionMs)
        assertEquals(listOf(50_000L, 90_000L), commits)
        assertEquals(0L, timeline.chapterStepDelta(seek.positionMs, 1, 90_000, seek.chapterPositionMs))
    }

    @Test fun anExternalSeekSupersedesPendingChapterNavigationWithoutAnotherCommit() {
        val seek = seekState(25_000)
        seek.seekTo(50_000)
        now = 40
        seek.seekTo(75_000)
        seek.followExternalSeek(10_000)
        assertEquals(10_000L, seek.positionMs)
        assertEquals(10_000L, seek.chapterPositionMs)
        seek.flushPending()
        assertEquals(listOf(50_000L), commits)
    }

    @Test fun cancelledPreparationFollowsLateDecoderLandingAndAllowsANewSeekToTheSameZero() {
        val seek = seekState(60_000L)
        seek.seekTo(0L) // Already sent to a slow decoder.
        now = 40L
        seek.seekTo(120_000L) // Still waiting for debounce.
        seek.cancelPendingSeek(actualPositionMs = 60_000L)
        seek.flushPending()
        assertNull(seek.pendingTargetMs)
        assertFalse(seek.awaitingSeekAck)
        assertEquals(listOf(0L), commits)

        seek.sync(0L) // The earlier decoder seek lands after preparation was cancelled.
        assertEquals(0L, seek.positionMs)
        assertEquals(0L, seek.chapterPositionMs)
        seek.seekTo(0L) // A new request must not be swallowed as an already committed zero.
        assertEquals(listOf(0L, 0L), commits)
    }

    @Test fun settledDebounceCommitsLatestTargetDespiteElapsedClockRounding() {
        val seek = seekState(100_000)
        seek.seekBy(10_000, 600_000)
        now = 40
        seek.seekBy(10_000, 600_000)
        // The coroutine's debounce has elapsed, but elapsedRealtime rounded one ms below it.
        now = 40 + PlaybackSeekCoordinator.DEBOUNCE_MS - 1
        seek.commitSettled()
        assertEquals(listOf(110_000L, 120_000L), commits)
    }

    @Test fun reusedDecoderCannotCommitThePreviousItemsPendingTarget() {
        val owner = Any()
        var generation = 1L
        var interactions = 0
        val seek = PlaybackSeekState(PlaybackSeekCoordinator(nowMs = { now }), 100_000,
            onSeekRequested = { interactions++ },
            isCurrent = { playbackSeekOwnerIsCurrent(owner, 1L, owner, generation, false) },
            commit = { commits += it })
        seek.seekTo(110_000)
        seek.seekTo(120_000)
        generation = 2L // Gapless/Next Up changed the item but kept the decoder.
        seek.flushPending()
        seek.commitSettled()
        seek.seekBy(10_000, 600_000)
        seek.seekTo(0)
        seek.followExternalSeek(5_000)
        assertEquals(listOf(110_000L), commits)
        assertEquals(2, interactions)
        assertEquals(120_000L, seek.positionMs)
        assertNull(seek.pendingTargetMs) // Stop/resume cannot save the outgoing item's target.
    }

    @Test fun pendingSourceReplacementRejectsOldInputBeforeItChangesUserIntent() {
        val owner = Any()
        var replacing = false
        var interactions = 0
        val seek = PlaybackSeekState(PlaybackSeekCoordinator(nowMs = { now }), 10_000,
            onSeekRequested = { interactions++ },
            isCurrent = { playbackSeekOwnerIsCurrent(owner, 1L, owner, 1L, replacing) },
            commit = { commits += it })
        replacing = true
        seek.seekTo(60_000)
        assertTrue(commits.isEmpty())
        assertEquals(0, interactions)
        assertFalse(seek.awaitingSeekAck)
    }

    @Test fun hiddenPositionIsSampledBeforeTheNextRelativeSeek() {
        var engine = 100_000L
        val seek = PlaybackSeekState(PlaybackSeekCoordinator(nowMs = { now }), engine,
            readEnginePositionMs = { engine }, commit = { commits += it })
        // The reduced-playback profile did not poll the hidden OSD during this interval.
        now = 180_000
        engine = 280_000
        seek.seekBy(10_000, 600_000)
        assertEquals(290_000L, seek.positionMs)
        assertEquals(listOf(290_000L), commits)
    }

    @Test fun freshInputPreservesASeekStillInFlight() {
        val engine = 100_000L
        val seek = PlaybackSeekState(PlaybackSeekCoordinator(nowMs = { now }), engine,
            readEnginePositionMs = { engine }, commit = { commits += it })
        seek.seekBy(10_000, 600_000)
        now = 40
        seek.seekBy(10_000, 600_000)
        assertEquals(120_000L, seek.positionMs)
        assertTrue(seek.awaitingSeekAck)
        seek.commitSettled()
        assertEquals(listOf(110_000L, 120_000L), commits)
    }

    @Test fun acknowledgementEndsTheNeedToPollHiddenChrome() {
        val seek = seekState(100_000)
        assertFalse(seek.awaitingSeekAck)
        seek.seekTo(120_000)
        assertTrue(seek.awaitingSeekAck)
        now = 700
        seek.sync(119_900)
        now = 1_200
        seek.sync(120_500)
        assertFalse(seek.awaitingSeekAck)
        assertEquals(120_500L, seek.positionMs)
    }

    @Test fun pendingOnlyPollingRetiresALostSeekBeforeTheNextHiddenInput() {
        var engine = 10_000L
        val seek = PlaybackSeekState(PlaybackSeekCoordinator(nowMs = { now }), engine,
            readEnginePositionMs = { engine }, commit = { commits += it })
        seek.seekTo(200_000)
        // Chrome has hidden, but a pending target still requires reconciliation.
        repeat(30) {
            now += 250
            engine += 250
            if (seek.awaitingSeekAck) seek.sync(engine)
        }
        assertFalse(seek.awaitingSeekAck)
        assertNull(seek.pendingTargetMs)
        now = 300_000
        engine = 300_000
        seek.seekBy(10_000, 600_000)
        assertEquals(310_000L, seek.positionMs)
        assertEquals(listOf(200_000L, 310_000L), commits)
    }
}
