package com.ambient.tv.ui.player.backend

import com.ambient.tv.ui.player.isPlaybackEndSeek
import com.ambient.tv.ui.player.safePlaybackEndSeekTarget
import org.junit.Assert.*
import org.junit.Test

class MpvSeekStateTest {
    @Test fun seekToStartBeforeSurfaceReplacesTheSavedResume() {
        val seeks = MpvDeferredSeek(90_000L)

        assertTrue(seeks.defer(0L, loadRequested = false, fileReady = false))

        assertEquals(0L, seeks.takeOpenPosition())
        assertNull(seeks.takeOpenPosition())
        assertNull(seeks.takePendingPosition())
    }

    @Test fun latestSeekDuringLoadIsReplayedOnceWhenFileIsReady() {
        val seeks = MpvDeferredSeek(90_000L)
        val native = mutableListOf<Long>()
        assertEquals(90_000L, seeks.takeOpenPosition())
        fun seek(positionMs: Long, ready: Boolean) {
            if (!seeks.defer(positionMs, loadRequested = true, fileReady = ready)) native += positionMs
        }

        seek(120_000L, ready = false)
        seek(150_000L, ready = false)
        seek(0L, ready = false)
        assertTrue(native.isEmpty())

        seeks.takePendingPosition()?.let { seek(it, ready = true) }
        assertEquals(listOf(0L), native)
        assertNull(seeks.takePendingPosition())
        seek(30_000L, ready = true)
        assertEquals(listOf(0L, 30_000L), native)
    }

    @Test fun newOpenReplacesAnObsoleteDeferredSeek() {
        val seeks = MpvDeferredSeek()
        seeks.defer(120_000L, loadRequested = true, fileReady = false)

        seeks.resetForOpen(45_000L)

        assertNull(seeks.takePendingPosition())
        assertEquals(45_000L, seeks.takeOpenPosition())
    }

    @Test fun restartAfterPrematureEndSupersedesAnOlderPreLoadSeek() {
        val seeks = MpvDeferredSeek()
        seeks.defer(120_000L, loadRequested = true, fileReady = false)
        // The opening file ended before FILE_LOADED; the user now chooses Restart.
        assertEquals(0L, seeks.takeReopenPosition(0L, preferPendingSeek = false))
        assertNull(seeks.takePendingPosition())
        assertNull(seeks.takeOpenPosition())
    }

    @Test fun outputRecoveryRetainsTheLatestPreLoadSeekInsteadOfTheOldClock() {
        val seeks = MpvDeferredSeek()
        seeks.defer(120_000L, loadRequested = true, fileReady = false)

        assertEquals(120_000L, seeks.takeReopenPosition(45_000L, preferPendingSeek = true))
        assertNull(seeks.takePendingPosition())
        assertEquals(46_000L, seeks.takeReopenPosition(46_000L, preferPendingSeek = true))
    }

    @Test fun deferredEndSeekRetainsExactTailIntentUntilFileLoaded() {
        val seeks = MpvDeferredSeek()
        val duration = 120_000L
        val targetBeforeLoad = requireNotNull(safePlaybackEndSeekTarget(30_000L, duration))
        seeks.defer(duration, loadRequested = true, fileReady = false, openPositionMs = targetBeforeLoad)

        val replay = requireNotNull(seeks.takePendingPosition())
        assertEquals(duration, replay)
        assertTrue("FILE_LOADED must still classify the request as an exact end seek",
            isPlaybackEndSeek(replay, duration))
        assertEquals(118_500L, safePlaybackEndSeekTarget(40_000L, duration))
        // If opening already reached the tail, replay must not seek backward to play it again.
        assertNull(safePlaybackEndSeekTarget(119_000L, duration))
    }

    @Test fun deferredEndSeekUsesSafeTailForInitialOpenAndOutputRecovery() {
        val duration = 120_000L
        val target = requireNotNull(safePlaybackEndSeekTarget(30_000L, duration))
        val initial = MpvDeferredSeek()
        initial.defer(duration, loadRequested = false, fileReady = false, openPositionMs = target)
        assertEquals(118_500L, initial.takeOpenPosition())
        assertNull(initial.takePendingPosition())

        val recovery = MpvDeferredSeek()
        recovery.defer(duration, loadRequested = true, fileReady = false, openPositionMs = target)
        // Reopen and DV surface restart use loadfile/prime's existing exact start target;
        // they must not pass the logical duration to loadfile or subtract the tail twice.
        val reopen = recovery.takeReopenPosition(30_000L, preferPendingSeek = true)
        assertEquals(118_500L, reopen)
        recovery.resetForOpen(reopen)
        assertEquals(118_500L, recovery.takeOpenPosition())
        assertNull(recovery.takePendingPosition())
    }

    @Test fun newerRestartDiscardsDeferredEndSeekIntentAndSafeTail() {
        val seeks = MpvDeferredSeek()
        seeks.defer(120_000L, loadRequested = true, fileReady = false, openPositionMs = 118_500L)
        seeks.defer(0L, loadRequested = true, fileReady = false)

        assertEquals(0L, seeks.takeReopenPosition(30_000L, preferPendingSeek = true))
        assertNull(seeks.takePendingPosition())
    }

    @Test fun endRequestAlreadyInTheTailStillSupersedesAnOlderDeferredBackwardSeek() {
        val seeks = MpvDeferredSeek()
        seeks.defer(10_000L, loadRequested = true, fileReady = false)
        val current = 119_000L
        val duration = 120_000L
        val openPosition = safePlaybackEndSeekTarget(current, duration) ?: current
        seeks.defer(duration, loadRequested = true, fileReady = false, openPositionMs = openPosition)

        assertEquals(duration, seeks.takePendingPosition())
        assertNull(safePlaybackEndSeekTarget(current, duration))
        assertNull(seeks.takePendingPosition())
    }

    @Test fun nextControllerDoesNotInheritPreviousTitlesDeferredSeek() {
        val previous = MpvDeferredSeek()
        previous.defer(120_000L, loadRequested = true, fileReady = false)
        val next = MpvDeferredSeek()

        assertNull(next.takeOpenPosition())
        assertNull(next.takePendingPosition())
        assertEquals(120_000L, previous.takePendingPosition())
    }

    @Test fun validZeroClockReplacesThePreSeekPosition() {
        val zero = mpvConfirmedPositionMs(0.0, 120_000L)
        assertEquals(0L, zero)
        assertEquals(0L, mpvConfirmedPositionMs(Double.NaN, zero))
        assertEquals(250L, mpvConfirmedPositionMs(0.25, zero))
    }

    @Test fun unavailableNativeClockPreservesTheLastConfirmedPosition() {
        for (unavailable in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
            assertEquals(120_000L, mpvConfirmedPositionMs(unavailable, 120_000L))
        }
    }

    @Test fun commandAcceptanceAndUnrelatedCommandsDoNotAcknowledgeSeekLanding() {
        var now = 0L
        val seek = MpvPendingSeek { now }
        seek.observe(0L, 10_000L)
        seek.request(1L, 30_000L)

        seek.observe(1L, 10_000L) // mpv_command returned; the decoder still has its old clock.
        now = 750L
        seek.observe(2L, 10_750L) // A later pause/image command can complete before the seek lands.

        assertTrue(seek.isPending)
        assertEquals(30_000L, seek.targetMs)
        assertEquals(40_000L, mpvSeekByTarget(seek.targetMs, 10_750L, 10_000L))
    }

    @Test fun oldSampleCannotAcknowledgeAReplacementSeek() {
        var now = 0L
        val seek = MpvPendingSeek { now }
        seek.observe(0L, 10_000L)
        seek.request(2L, 30_000L)

        seek.observe(1L, 30_000L)
        now = 1_000L
        seek.observe(1L, 30_000L)

        assertTrue(seek.isPending)
        seek.observe(2L, 30_000L)
        now = 1_750L
        seek.observe(2L, 30_750L)
        assertFalse(seek.isPending)
        assertNull(seek.targetMs)
        assertEquals(40_750L, mpvSeekByTarget(seek.targetMs, 30_750L, 10_000L))
    }

    @Test fun failedSeekDoesNotLeaveTheNextButtonRelativeToAnUnreachableTarget() {
        val seek = MpvPendingSeek { 0L }
        seek.request(1L, 30_000L)
        seek.observe(1L, 10_000L, failed = true)

        assertFalse(seek.isPending)
        assertNull(seek.targetMs)
        assertEquals(20_000L, mpvSeekByTarget(seek.targetMs, 10_000L, 10_000L))
    }

    @Test fun oldFailureCannotDiscardTheLatestRequest() {
        val seek = MpvPendingSeek { 0L }
        seek.request(2L, 30_000L)
        seek.observe(1L, 10_000L, failed = true)

        assertEquals(30_000L, seek.targetMs)
    }
}
