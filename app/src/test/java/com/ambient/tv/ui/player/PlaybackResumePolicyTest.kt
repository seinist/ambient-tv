package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackResumePolicyTest {
    private fun paused(positionMs: Long = 30_000L) = PauseRewindPolicy().apply {
        userPaused(nowMs = 1_000L, positionMs = positionMs, wasPlaying = true, eligible = true)
    }

    private fun PauseRewindPolicy.resume(
        now: Long = 11_000L,
        position: Long = 30_000L,
        seconds: Int = 5,
        minimum: Int = 10,
        playing: Boolean = false,
        eligible: Boolean = true,
        earliest: Long = 0,
        latest: Long = Long.MAX_VALUE,
    ) = resumeTarget(now, position, playing, eligible, seconds, minimum, earliest, latest)

    @Test fun resumeStartsEarlierWithoutGoingBeforeTheBeginning() {
        assertEquals(1_460_000L, resumeRewindPositionMs(1_470_000L, 10))
        assertEquals(0L, resumeRewindPositionMs(3_000L, 5))
        assertEquals(0L, resumeRewindPositionMs(-1L, 5))
        assertEquals(30_000L, resumeRewindPositionMs(30_000L, 0))
        assertEquals(30_000L, resumeRewindPositionMs(30_000L, -10))
    }

    @Test fun invalidLargeDurationIsBoundedWithoutIntegerOverflow() {
        assertEquals(60_000L, resumeRewindPositionMs(120_000L, Int.MAX_VALUE))
        assertEquals(Long.MAX_VALUE - 60_000L, resumeRewindPositionMs(Long.MAX_VALUE, 60))
    }

    @Test fun pauseRewindsAtTheExactThresholdAndOnlyOnce() {
        val pause = paused()
        assertEquals(25_000L, pause.resume())
        assertNull(pause.resume(now = 12_000L))
    }

    @Test fun aShortPauseDoesNotCarryItsCountdownIntoTheNextPause() {
        val pause = paused()
        assertNull(pause.resume(now = 10_999L))
        assertNull(pause.resume(now = 15_000L))
        pause.userPaused(20_000L, 35_000L, wasPlaying = true, eligible = true)
        assertEquals(30_000L, pause.resume(now = 30_000L, position = 35_000L))
    }

    @Test fun alwaysAllowsImmediateUserResume() {
        assertEquals(25_000L, paused().resume(now = 1_000L, minimum = 0))
    }

    @Test fun repeatedPauseDoesNotResetTheOriginalPauseTime() {
        val pause = paused()
        pause.userPaused(9_000L, 30_000L, wasPlaying = false, eligible = true)
        assertEquals(25_000L, pause.resume())
    }

    @Test fun technicalOrAlreadyPausedPlaybackDoesNotArmAJump() {
        val pause = PauseRewindPolicy()
        assertNull(pause.resume())
        pause.userPaused(1_000L, 30_000L, wasPlaying = false, eligible = true)
        assertNull(pause.resume())
    }

    @Test fun manualSeekOrRendererReplacementCancelsEvenAnUncommittedJump() {
        val pause = paused()
        pause.clear()
        assertNull(pause.resume())
    }

    @Test fun playCommandWhileAlreadyPlayingCannotRewind() {
        val pause = paused()
        assertNull(pause.resume(playing = true))
        assertNull(pause.resume())
    }

    @Test fun disabledAndUnavailableModesConsumeTheOldPause() {
        val disabled = paused()
        assertNull(disabled.resume(seconds = 0))
        assertNull(disabled.resume())
        val unavailable = paused()
        assertNull(unavailable.resume(eligible = false))
        assertNull(unavailable.resume())
    }

    @Test fun anUnreportedPositionChangeDoesNotRewindTheUsersNewTarget() {
        assertNull(paused().resume(position = 80_000L))
        assertEquals(25_500L, paused().resume(position = 30_500L))
    }

    @Test fun aClockMovingBackCannotSatisfyTheMinimumPause() {
        assertNull(paused().resume(now = 999L, minimum = 0))
    }

    @Test fun liveRewindUsesTheCurrentBufferBounds() {
        val wall = 1_790_000_000_000L
        assertEquals(wall - 2_000L, paused(wall).resume(position = wall, earliest = wall - 2_000L, latest = wall + 10_000L))
        // A long pause can outlive the ring buffer; the earliest retained point is then the only valid start.
        assertEquals(wall + 1_000L, paused(wall).resume(position = wall, earliest = wall + 1_000L, latest = wall + 20_000L))
        assertNull(paused(wall).resume(position = wall, earliest = wall, latest = wall - 1L))
    }

    @Test fun rewindAtTheBeginningDoesNotIssueARedundantSeek() {
        assertNull(paused(0L).resume(position = 0L))
    }

    @Test fun capabilitiesExcludeMusicAndUnavailableSeekWindows() {
        assertEquals(PlaybackResumeCapabilities(), playbackResumeCapabilities(false, false, true, true))
        assertEquals(PlaybackResumeCapabilities(), playbackResumeCapabilities(true, true, false, false))
        assertEquals(PlaybackResumeCapabilities(true, false), playbackResumeCapabilities(true, true, true, true))
        assertEquals(PlaybackResumeCapabilities(true, false), playbackResumeCapabilities(true, false, true, false))
        assertEquals(PlaybackResumeCapabilities(true, true), playbackResumeCapabilities(true, false, true, true))
    }
}
