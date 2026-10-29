package com.ambient.tv.connectivity

import org.junit.Assert.*
import org.junit.Test

class HostReconnectGenerationTest {
    @Test fun normalSuccessfulRequestsNeverTriggerBrowseRecovery() {
        val store = HostHealthStore()
        repeat(30) { store.online("nas") }
        assertEquals(0L, store.current("nas").recoveries)
        assertEquals(30L, store.current("nas").generation)
    }

    @Test fun recoverySurvivesConflationWithSubsequentSuccessfulRequests() {
        val store = HostHealthStore()
        store.online("nas")
        store.offline(store.begin("nas"))
        repeat(20) { store.online("nas") }
        assertEquals(HostHealth.ONLINE, store.current("nas").health)
        assertEquals(1L, store.current("nas").recoveries)
        store.offline(store.begin("nas"))
        store.online("nas")
        assertEquals(2L, store.current("nas").recoveries)
    }

    @Test fun delayedFailureCannotStartAnotherRecoveryCycle() {
        val store = HostHealthStore()
        val old = store.begin("nas")
        store.offline(store.begin("nas"))
        store.online("nas")
        assertFalse(store.offline(old))
        store.online("nas")
        assertEquals(1L, store.current("nas").recoveries)
    }

    @Test fun recoveringOneMachineDoesNotRefreshAnother() {
        val store = HostHealthStore()
        store.online("other")
        store.offline(store.begin("nas"))
        store.online("nas")
        assertEquals(1L, store.current("nas").recoveries)
        assertEquals(0L, store.current("other").recoveries)
    }
}
