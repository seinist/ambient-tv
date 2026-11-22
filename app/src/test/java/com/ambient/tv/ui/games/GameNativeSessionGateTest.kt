package com.ambient.tv.ui.games

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameNativeSessionGateTest {
    @Test fun uiTimeoutDoesNotAllowNewCoreBeforeActualDestroyCompletes() {
        val gate = GameNativeSessionGate(timeoutMs = 20)
        val old = checkNotNull(gate.tryOpen())
        assertTrue(runCatching { gate.awaitClosed() }.isFailure)
        assertNull(gate.tryOpen())
        old.complete(Unit) // Actual late GL-thread teardown acknowledgement.
        gate.awaitClosed()
        assertNotNull(gate.tryOpen())
    }

    @Test fun duplicateOldAcknowledgementCannotReleaseNewSession() {
        val gate = GameNativeSessionGate(timeoutMs = 20)
        val old = checkNotNull(gate.tryOpen())
        old.complete(Unit)
        assertNotNull(gate.tryOpen())
        old.complete(Unit)
        assertNull(gate.tryOpen())
        assertTrue(runCatching { gate.awaitClosed() }.isFailure)
    }

    @Test fun failedNativeDestroyKeepsSharedFrontendUnavailable() {
        val gate = GameNativeSessionGate(timeoutMs = 20)
        val old = checkNotNull(gate.tryOpen())
        old.completeExceptionally(IllegalStateException("native destruction failed"))
        assertTrue(runCatching { gate.awaitClosed() }.isFailure)
        assertNull(gate.tryOpen())
    }
}
