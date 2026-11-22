package com.ambient.tv.ui.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GameNativeSessionOwnerTest {
    @Test fun abandonedCompositionBeforeViewCreationReleasesReservation() {
        val gate = GameNativeSessionGate(timeoutMs = 20)
        val owner = GameNativeSessionOwner(gate)
        owner.onAbandoned()
        gate.awaitClosed()
        assertNotNull(gate.tryOpen())
    }

    @Test fun viewDetachBeforeComposeDisposalRunsCleanupOnce() {
        val gate = GameNativeSessionGate(timeoutMs = 20)
        val owner = GameNativeSessionOwner(gate)
        var cleanups = 0
        owner.cleanup = { cleanups++; owner.closed?.complete(Unit) }
        owner.release() // GLRetroView.beforeDetach
        owner.onForgotten()
        assertEquals(1, cleanups)
        gate.awaitClosed()
        assertNotNull(gate.tryOpen())
    }

    @Test fun composeDisposalBeforeViewDetachRunsCleanupOnce() {
        val gate = GameNativeSessionGate(timeoutMs = 20)
        val owner = GameNativeSessionOwner(gate)
        var cleanups = 0
        owner.cleanup = { cleanups++; owner.closed?.complete(Unit) }
        owner.onForgotten()
        owner.release()
        assertEquals(1, cleanups)
        gate.awaitClosed()
    }

    @Test fun initialViewAttachDoesNotRestartLiveSession() {
        val owner = GameNativeSessionOwner(GameNativeSessionGate(timeoutMs = 20))
        var recoveries = 0
        owner.onRetainedAttach = { recoveries++ }
        owner.onRemembered()
        owner.viewAttached()
        assertEquals(0, recoveries)
        owner.onForgotten()
    }

    @Test fun retainedCompositionReattachRequestsOneRecovery() {
        val owner = GameNativeSessionOwner(GameNativeSessionGate(timeoutMs = 20))
        var recoveries = 0
        owner.onRetainedAttach = { recoveries++ }
        owner.onRemembered()
        owner.release() // A resolution-host replacement ends the original GL thread.
        owner.viewAttached()
        owner.viewAttached()
        assertEquals(1, recoveries)
        owner.onForgotten()
    }

    @Test fun forgettingBeforePostedAttachSuppressesRecovery() {
        val owner = GameNativeSessionOwner(GameNativeSessionGate(timeoutMs = 20))
        var recoveries = 0
        owner.onRetainedAttach = { recoveries++ }
        owner.onRemembered()
        owner.release()
        owner.onForgotten()
        owner.viewAttached()
        assertEquals(0, recoveries)
    }

    @Test fun abandonedCompositionCannotRestartFromAnAttachCallback() {
        val owner = GameNativeSessionOwner(GameNativeSessionGate(timeoutMs = 20))
        var recoveries = 0
        owner.onRetainedAttach = { recoveries++ }
        owner.onAbandoned()
        owner.viewAttached()
        assertEquals(0, recoveries)
    }
}
