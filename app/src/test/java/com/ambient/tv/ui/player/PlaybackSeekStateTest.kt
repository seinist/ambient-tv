package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
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
}
