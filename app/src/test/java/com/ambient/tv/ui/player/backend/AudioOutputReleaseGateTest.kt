package com.ambient.tv.ui.player.backend

import org.junit.Assert.*
import org.junit.Test

class AudioOutputReleaseGateTest {
    /** Manual clock for [PreviousRelease.whenSettled]. */
    private class Timers {
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val delay: (Long, () -> Unit) -> Unit = { ms, block -> scheduled += ms to block }
        fun fire() { scheduled.toList().forEach { it.second() }; scheduled.clear() }
    }

    @Test fun queuedNativeCleanupMustFinishBeforeNextOwnerStarts() {
        val gate = AudioOutputReleaseGate()
        val scheduled = ArrayDeque<() -> Unit>()
        val queue = MpvCommandQueue({ scheduled.addLast(it) }, { throw AssertionError(it) })
        val released = gate.beginRelease()
        queue.close { released.complete(Unit) }
        var started = false
        gate.snapshot().future.thenRun { started = true }
        assertFalse(started)
        scheduled.removeFirst()()
        assertTrue(started)
    }

    @Test fun waitsForEveryRetiringOwner() {
        val gate = AudioOutputReleaseGate()
        val first = gate.beginRelease()
        val second = gate.beginRelease()
        val ready = gate.snapshot()
        second.complete(Unit)
        assertFalse(ready.isDone)
        first.complete(Unit)
        assertTrue(ready.isDone)
        assertFalse(ready.failed)
    }

    @Test fun failedReleaseOnlyReachesTheNextStart() {
        val gate = AudioOutputReleaseGate()
        gate.beginRelease().completeExceptionally(IllegalStateException("still owns output"))
        val next = gate.snapshot()
        assertTrue(next.failed)
        assertEquals("still owns output", next.failure?.message)
        // The start after that is no longer held back by the old failure.
        assertFalse(gate.snapshot().failed)
        assertEquals(0, gate.pendingCount())
    }

    @Test fun failureWhileWaitingIsSeenByTheWaitingStartOnly() {
        val gate = AudioOutputReleaseGate()
        val release = gate.beginRelease()
        val waiting = gate.snapshot()
        release.completeExceptionally(IllegalStateException("boom"))
        assertTrue(waiting.failed)
        gate.beginRelease().complete(Unit)
        assertFalse(gate.snapshot().failed)
    }

    @Test fun beginReleaseDropsFinishedEntriesIncludingFailed() {
        val gate = AudioOutputReleaseGate()
        gate.beginRelease().completeExceptionally(IllegalStateException())
        gate.beginRelease().complete(Unit)
        gate.beginRelease()
        assertEquals(1, gate.pendingCount())
    }

    @Test fun snapshotDoesNotWaitForItsOwnLaterRelease() {
        val gate = AudioOutputReleaseGate()
        val ready = gate.snapshot()
        gate.beginRelease()
        assertTrue(ready.isDone)
    }

    @Test fun settlesImmediatelyWithoutTimerWhenNothingIsClosing() {
        val timers = Timers()
        var outcome: PreviousReleaseOutcome? = null
        AudioOutputReleaseGate().snapshot().whenSettled(delay = timers.delay) { outcome = it }
        assertEquals(PreviousReleaseOutcome.READY, outcome)
        assertTrue(timers.scheduled.isEmpty())
    }

    @Test fun settlesWhenThePreviousPlayerClosesAndIgnoresTheLaterTimer() {
        val gate = AudioOutputReleaseGate()
        val release = gate.beginRelease()
        val timers = Timers()
        val outcomes = mutableListOf<PreviousReleaseOutcome>()
        gate.snapshot().whenSettled(delay = timers.delay) { outcomes += it }
        assertTrue(outcomes.isEmpty())
        assertEquals(PREVIOUS_RELEASE_LIMIT_MS, timers.scheduled.single().first)
        release.complete(Unit)
        timers.fire()
        assertEquals(listOf(PreviousReleaseOutcome.READY), outcomes)
    }

    @Test fun failedTeardownStartsAnywayWithFailureOutcome() {
        val gate = AudioOutputReleaseGate()
        val release = gate.beginRelease()
        val outcomes = mutableListOf<PreviousReleaseOutcome>()
        gate.snapshot().whenSettled(delay = Timers().delay) { outcomes += it }
        release.completeExceptionally(IllegalStateException())
        assertEquals(listOf(PreviousReleaseOutcome.READY_AFTER_FAILURE), outcomes)
    }

    @Test fun hungTeardownTimesOutOnceAndNoLongerBlocksLaterStarts() {
        val gate = AudioOutputReleaseGate()
        val hung = gate.beginRelease()
        val timers = Timers()
        val outcomes = mutableListOf<PreviousReleaseOutcome>()
        gate.snapshot().whenSettled(delay = timers.delay) { outcomes += it }
        timers.fire()
        assertEquals(listOf(PreviousReleaseOutcome.TIMED_OUT), outcomes)
        // Finishing late does not report a second time.
        hung.complete(Unit)
        assertEquals(1, outcomes.size)
        assertEquals(0, gate.pendingCount())
        assertTrue(gate.snapshot().isDone)
    }

    @Test fun outcomeRules() {
        assertNull(previousReleaseOutcome(done = false, failed = false, waitedMs = 14_999L))
        assertEquals(PreviousReleaseOutcome.TIMED_OUT, previousReleaseOutcome(false, false, 15_000L))
        assertEquals(PreviousReleaseOutcome.READY, previousReleaseOutcome(true, false, 20_000L))
        assertEquals(PreviousReleaseOutcome.READY_AFTER_FAILURE, previousReleaseOutcome(true, true, 0L))
    }
}
