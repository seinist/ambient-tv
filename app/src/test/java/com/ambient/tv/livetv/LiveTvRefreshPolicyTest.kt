package com.ambient.tv.livetv

import org.junit.Assert.*
import org.junit.Test

class LiveTvRefreshPolicyTest {
    @Test fun reconnectWaitsForRecordingActionAndForcesTheNextLoadSilently() {
        val policy = LiveTvRefreshPolicy()
        policy.reconnected()
        assertNull(policy.request(force = false, announceSuccess = false, actionInProgress = true))
        assertTrue(policy.recoveryPending)

        val request = requireNotNull(policy.request(force = false, announceSuccess = false, actionInProgress = false))
        assertTrue(request.force)
        assertFalse(request.announceSuccess)
        // Both mutation success and failure can drain the same pending request.
        policy.completed(request)
        assertFalse(policy.recoveryPending)
    }

    @Test fun interruptedReconnectLoadRemainsPendingForThePostMutationLoad() {
        val policy = LiveTvRefreshPolicy()
        policy.reconnected()
        val interrupted = requireNotNull(policy.request(false, false, false))
        assertTrue(interrupted.force)

        // Starting a recording cancels this request; no completion is reported.
        assertNull(policy.request(false, false, true))
        val afterMutation = requireNotNull(policy.request(false, false, false))
        assertTrue(afterMutation.force)
        assertFalse(afterMutation.announceSuccess)
        policy.completed(afterMutation)
        assertFalse(requireNotNull(policy.request(false, false, false)).force)
    }

    @Test fun completingAnOlderAttemptCannotConsumeANewerReconnect() {
        val policy = LiveTvRefreshPolicy()
        policy.reconnected()
        val first = requireNotNull(policy.request(false, false, false))
        policy.reconnected()
        policy.completed(first)
        assertTrue(policy.recoveryPending)

        val latest = requireNotNull(policy.request(false, false, false))
        policy.completed(latest)
        assertFalse(policy.recoveryPending)
    }

    @Test fun reconnectsDuringOneMutationCoalesceIntoOneCompletedRefresh() {
        val policy = LiveTvRefreshPolicy()
        repeat(3) {
            policy.reconnected()
            assertNull(policy.request(false, false, true))
        }
        val request = requireNotNull(policy.request(false, false, false))
        assertTrue(request.force)
        policy.completed(request)
        assertFalse(requireNotNull(policy.request(false, false, false)).force)
    }

    @Test fun manualRefreshKeepsItsConfirmationAndOrdinaryLoadsRemainCached() {
        val policy = LiveTvRefreshPolicy()
        assertFalse(requireNotNull(policy.request(false, false, false)).force)
        policy.reconnected()
        val manual = requireNotNull(policy.request(true, true, false))
        assertTrue(manual.force)
        assertTrue(manual.announceSuccess)
        policy.completed(manual)
        assertFalse(policy.recoveryPending)
    }
}
