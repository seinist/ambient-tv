package com.ambient.tv.ui.player.backend

import org.junit.Assert.*
import org.junit.Test

class AudioOutputReleaseGateTest {
    @Test fun queuedNativeCleanupMustFinishBeforeNextOwnerStarts() {
        val gate = AudioOutputReleaseGate()
        val scheduled = ArrayDeque<() -> Unit>()
        val queue = MpvCommandQueue({ scheduled.addLast(it) }, { throw AssertionError(it) })
        val released = gate.beginRelease()
        queue.close { released.complete(Unit) }
        var started = false
        gate.snapshot().thenRun { started = true }
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
        assertFalse(ready.isCompletedExceptionally)
    }

    @Test fun failedReleaseNeverPermitsAnotherOutput() {
        val gate = AudioOutputReleaseGate()
        gate.beginRelease().completeExceptionally(IllegalStateException("still owns output"))
        assertTrue(gate.snapshot().isCompletedExceptionally)
        gate.beginRelease().complete(Unit)
        assertTrue(gate.snapshot().isCompletedExceptionally)
    }

    @Test fun snapshotDoesNotWaitForItsOwnLaterRelease() {
        val gate = AudioOutputReleaseGate()
        val ready = gate.snapshot()
        gate.beginRelease()
        assertTrue(ready.isDone)
    }
}
