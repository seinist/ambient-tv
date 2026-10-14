package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSeekCoordinatorTest {
    @Test fun repeatedRelativeSeeksStackOnTheRequestedTime() {
        var now = 0L
        val seek = PlaybackSeekCoordinator(nowMs = { now })
        assertEquals(40_000L, seek.requestRelative(10_000, 30_000, 120_000))
        now = 50
        assertEquals(70_000L, seek.requestRelative(30_000, 30_000, 120_000))
        now = 100
        assertEquals(40_000L, seek.requestRelative(-30_000, 12_000, 120_000))
    }

    @Test fun theFirstSeekCommitsImmediatelyAndLaterOnesWaitForQuiet() {
        var now = 0L
        val seek = PlaybackSeekCoordinator(nowMs = { now })
        seek.requestRelative(30_000, 10_000, 120_000)
        assertEquals(40_000L, seek.consumeCommit())
        now = 80
        seek.requestRelative(30_000, 10_000, 120_000)
        assertNull(seek.consumeCommit())
        now = 80 + PlaybackSeekCoordinator.DEBOUNCE_MS
        assertEquals(70_000L, seek.consumeCommit())
        assertNull(seek.consumeCommit())
    }

    @Test fun theOsdKeepsTheTargetWhileTheEngineIsStillOnTheOldFrame() {
        var now = 0L
        val seek = PlaybackSeekCoordinator(nowMs = { now })
        seek.requestRelative(30_000, 20_000, 180_000)
        now = 800
        assertEquals(50_000L, seek.displayedPosition(20_000))
        now = 1_200
        assertEquals(50_000L, seek.displayedPosition(21_000))
    }

    @Test fun aMovingKeyframeInsideTheAlignWindowHandsOverAfterItStaysThere() {
        var now = 0L
        val seek = PlaybackSeekCoordinator(nowMs = { now })
        seek.requestRelative(10_000, 40_000, 180_000)
        seek.consumeCommit()
        now = 800
        // One sample on the keyframe is not yet a landing.
        assertEquals(50_000L, seek.displayedPosition(48_200))
        now = 800 + PlaybackSeekCoordinator.ACK_STABILITY_MS
        // The clock is advancing and still several seconds short of the request. That is a
        // normal keyframe landing, so the OSD may follow it instead of sticking on the target.
        assertEquals(48_700L, seek.displayedPosition(48_700))
        assertNull(seek.pendingTarget)
    }

    @Test fun aFrozenClockDoesNotPullTheOsdBackToAKeyframeBehindTheTarget() {
        var now = 0L
        val seek = PlaybackSeekCoordinator(nowMs = { now })
        seek.requestRelative(10_000, 40_000, 180_000)
        seek.consumeCommit()
        now = 800
        assertEquals(50_000L, seek.displayedPosition(48_200))
        now = 800 + PlaybackSeekCoordinator.IDLE_MS
        // Buffering or a paused keyframe wait sits still within the old snap tolerance.
        // That must not walk the requested time backward.
        assertEquals(50_000L, seek.displayedPosition(48_200))
        assertEquals(50_000L, seek.pendingTarget)
        now += PlaybackSeekCoordinator.IDLE_MS
        assertEquals(50_000L, seek.displayedPosition(50_000))
        assertEquals(50_000L, seek.pendingTarget)
        now += PlaybackSeekCoordinator.IDLE_MS
        // A frozen clock that is actually on the target is a landing.
        assertEquals(50_000L, seek.displayedPosition(50_000))
        assertNull(seek.pendingTarget)
    }

    @Test fun anOptimisticTargetReportDoesNotSurviveADropBehindTheAlignWindow() {
        var now = 0L
        val seek = PlaybackSeekCoordinator(nowMs = { now })
        seek.requestRelative(30_000, 40_000, 180_000)
        seek.consumeCommit()
        now = 800
        assertEquals(70_000L, seek.displayedPosition(70_000))
        now += PlaybackSeekCoordinator.ACK_STABILITY_MS
        assertEquals(70_000L, seek.displayedPosition(70_000))
        assertNull(seek.pendingTarget)
        val previousKeyframe = 70_000L - PlaybackSeekCoordinator.ALIGN_BEFORE_MS - 1
        now += 250
        assertEquals(70_000L, seek.displayedPosition(previousKeyframe))
        assertEquals(70_000L, seek.pendingTarget)
        now += PlaybackSeekCoordinator.IDLE_MS
        assertEquals(70_000L, seek.displayedPosition(previousKeyframe))
    }

    @Test fun theEngineAnsweringWithTheTargetDoesNotEndTheSeek() {
        var now = 0L
        val seek = PlaybackSeekCoordinator(nowMs = { now })
        seek.requestRelative(30_000, 40_000, 180_000)
        seek.consumeCommit()
        now = 800
        assertEquals(70_000L, seek.displayedPosition(70_000))
        now = 1_050
        assertEquals(70_000L, seek.displayedPosition(66_500))
        assertEquals(100_000L, seek.requestRelative(30_000, 70_000, 180_000))
    }

    @Test fun aKeyRepeatStartingAfterTheFirstSeekLandedIsNotANewBurst() {
        var now = 0L
        val seek = PlaybackSeekCoordinator(nowMs = { now })
        seek.requestRelative(30_000, 40_000, 180_000)
        assertEquals(70_000L, seek.consumeCommit())
        now = 250
        assertEquals(70_000L, seek.displayedPosition(68_000))
        now = 400
        assertEquals(100_000L, seek.requestRelative(30_000, 70_000, 180_000))
        assertNull(seek.consumeCommit())
    }

    @Test fun aSlowInitialSeekNeverHandsTheOsdBackToTheStaleStartPosition() {
        var now = 0L
        val seek = PlaybackSeekCoordinator(nowMs = { now })
        seek.requestRelative(30_000, 10_000, 180_000)
        now = 4_000L
        assertEquals(40_000L, seek.displayedPosition(10_000))
        now = 20_000L
        assertEquals(40_000L, seek.displayedPosition(10_000))
    }

    @Test fun aMovingClockStillFarBehindTheTargetDoesNotTakeOver() {
        var now = 0L
        val seek = PlaybackSeekCoordinator(nowMs = { now })
        seek.requestRelative(30_000, 10_000, 180_000)
        var engine = 10_000L
        now = 800
        assertEquals(40_000L, seek.displayedPosition(engine))
        repeat(8) {
            now += 400
            engine += 400
            assertEquals(40_000L, seek.displayedPosition(engine))
        }
        assertEquals(40_000L, seek.pendingTarget)
    }

    @Test fun aTargetMustStayStableBeforeTheOsdUsesTheEngineClock() {
        var now = 0L
        val seek = PlaybackSeekCoordinator(nowMs = { now })
        seek.requestRelative(30_000, 10_000, 180_000)
        seek.consumeCommit()
        now = 800
        // Native renderers may optimistically report the target before falling back to a
        // preceding keyframe; one report is not enough to make the progress bar jump.
        assertEquals(40_000L, seek.displayedPosition(40_000))
        now += PlaybackSeekCoordinator.ACK_STABILITY_MS
        assertEquals(40_000L, seek.displayedPosition(40_000))
        assertNull(seek.pendingTarget)
    }

    @Test fun seeksClampToTheKnownDuration() {
        assertEquals(90_000L, PlaybackSeekCoordinator(nowMs = { 0L }).requestRelative(30_000, 80_000, 90_000))
        assertEquals(0L, PlaybackSeekCoordinator(nowMs = { 0L }).requestRelative(-20_000, 5_000, 90_000))
    }

    @Test fun anUnknownDurationStillAllowsForwardSeeks() {
        val seek = PlaybackSeekCoordinator(nowMs = { 0L })
        assertEquals(45_000L, seek.requestRelative(15_000, 30_000, 0))
    }

    @Test fun aPausedSeekDoesNotJumpBackToTheKeyframeAfterAnsweringWithTheTarget() {
        var now = 0L
        val seek = PlaybackSeekCoordinator(nowMs = { now })
        // Paused at 40 s: the clock never moves.
        assertEquals(40_000L, seek.displayedPosition(40_000))
        now = 2_000
        seek.requestAbsolute(70_000)
        seek.consumeCommit()
        now = 2_800
        // VLC/MPV first answer with the target itself on the stopped clock ...
        assertEquals(70_000L, seek.displayedPosition(70_000))
        now += PlaybackSeekCoordinator.IDLE_MS
        assertEquals(70_000L, seek.displayedPosition(70_000))
        // ... then settle on the keyframe 2 s before it. The OSD keeps the target.
        now += 250
        assertEquals(70_000L, seek.displayedPosition(68_000))
        now += PlaybackSeekCoordinator.IDLE_MS
        assertEquals(70_000L, seek.displayedPosition(68_000))
        assertEquals(70_000L, seek.pendingTarget)
    }

    @Test fun aShortForwardSeekDoesNotLandOnTheStillRunningOldClock() {
        var now = 0L
        val seek = PlaybackSeekCoordinator(nowMs = { now })
        assertEquals(20_000L, seek.displayedPosition(20_000))
        now = 400
        assertEquals(20_400L, seek.displayedPosition(20_400))
        seek.requestAbsolute(23_000)
        seek.consumeCommit()
        var engine = 20_400L
        repeat(6) {
            now += 250
            engine += 250
            // The pre-seek clock keeps running inside the behind-window; that is not a landing.
            assertEquals(23_000L, seek.displayedPosition(engine))
        }
        // The engine jumps to the keyframe just before the target and plays on.
        engine = 22_600L
        now += 250
        assertEquals(23_000L, seek.displayedPosition(engine))
        repeat(3) {
            now += 250
            engine += 250
            seek.displayedPosition(engine)
        }
        assertNull(seek.pendingTarget)
    }
}
