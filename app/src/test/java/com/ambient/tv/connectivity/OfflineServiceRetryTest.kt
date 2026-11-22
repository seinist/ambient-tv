package com.ambient.tv.connectivity

import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineServiceRetryTest {
    private class Attempt {
        val events = mutableListOf<String>()
        var serviceReady = false
        var hostReady = false
        var wakeSucceeded = true
        var reconnectSucceeded = true
        var current = true
        var recovering = false
        var afterService: suspend () -> Unit = {}
        var afterHost: suspend () -> Unit = {}
        var afterWake: suspend () -> Unit = {}
        var afterReconnect: suspend () -> Unit = {}

        suspend fun retry(hasMac: Boolean = true): Boolean = retryOfflineService(
            checkService = { events += "service"; afterService(); serviceReady },
            machineOnline = { events += "host"; afterHost(); hostReady },
            reconnect = { events += "reconnect"; afterReconnect(); reconnectSucceeded },
            wake = if (hasMac) ({ events += "wake"; afterWake(); wakeSucceeded }) else null,
            reload = { events += "reload" },
            stillCurrent = { current },
            recoveryInProgress = { recovering },
        )
    }

    @Test fun recoveredServiceReloadsWithoutProbingOrWakingItsHost() = runBlocking {
        val attempt = Attempt().apply { serviceReady = true }
        assertTrue(attempt.retry())
        assertEquals(listOf("service", "reload"), attempt.events)
    }

    @Test fun retainedRetryForAnOnlineServiceStillReloadsFreshData() = runBlocking {
        val attempt = Attempt().apply { serviceReady = true; hostReady = true }
        assertTrue(attempt.retry())
        assertEquals(listOf("service", "reload"), attempt.events)
    }

    @Test fun awakeMachineOnlyReconnectsTheUnavailableService() = runBlocking {
        val attempt = Attempt().apply { hostReady = true }
        assertTrue(attempt.retry())
        assertEquals(listOf("service", "host", "reconnect", "reload"), attempt.events)
    }

    @Test fun offlineMachineIsWokenOnlyAfterBothFreshChecks() = runBlocking {
        val attempt = Attempt()
        assertTrue(attempt.retry())
        assertEquals(listOf("service", "host", "wake", "reconnect", "reload"), attempt.events)
    }

    @Test fun missingMacStillAllowsServiceReconnect() = runBlocking {
        val attempt = Attempt()
        assertTrue(attempt.retry(hasMac = false))
        assertEquals(listOf("service", "host", "reconnect", "reload"), attempt.events)
    }

    @Test fun failedWakeDoesNotStartASecondAutomaticWakeThroughReconnect() = runBlocking {
        val attempt = Attempt().apply { wakeSucceeded = false }
        assertFalse(attempt.retry())
        assertEquals(listOf("service", "host", "wake"), attempt.events)
    }

    @Test fun failedServiceReconnectDoesNotReloadContent() = runBlocking {
        val attempt = Attempt().apply { hostReady = true; reconnectSucceeded = false }
        assertFalse(attempt.retry())
        assertEquals(listOf("service", "host", "reconnect"), attempt.events)
    }

    @Test fun existingRecoveryIsJoinedWithoutAnotherHostProbeOrWake() = runBlocking {
        val attempt = Attempt().apply { recovering = true }
        assertTrue(attempt.retry())
        assertEquals(listOf("service", "reconnect", "reload"), attempt.events)
    }

    @Test fun recoveryStartingDuringHostProbeOwnsTheWake() = runBlocking {
        val attempt = Attempt().apply { afterHost = { recovering = true } }
        assertTrue(attempt.retry())
        assertEquals(listOf("service", "host", "reconnect", "reload"), attempt.events)
    }

    @Test fun staleEndpointDoesNotStartAProbe() = runBlocking {
        val attempt = Attempt().apply { current = false }
        assertFalse(attempt.retry())
        assertTrue(attempt.events.isEmpty())
    }

    @Test fun endpointChangedDuringServiceCheckDoesNotReloadOrWake() = runBlocking {
        for (ready in listOf(false, true)) {
            val attempt = Attempt().apply {
                serviceReady = ready
                afterService = { current = false }
            }
            assertFalse(attempt.retry())
            assertEquals(listOf("service"), attempt.events)
        }
    }

    @Test fun endpointChangedDuringHostCheckDoesNotWakeOrReconnect() = runBlocking {
        val attempt = Attempt().apply { afterHost = { current = false } }
        assertFalse(attempt.retry())
        assertEquals(listOf("service", "host"), attempt.events)
    }

    @Test fun endpointChangedDuringWakeDoesNotReconnectTheNewEndpoint() = runBlocking {
        val attempt = Attempt().apply { afterWake = { current = false } }
        assertFalse(attempt.retry())
        assertEquals(listOf("service", "host", "wake"), attempt.events)
    }

    @Test fun endpointChangedDuringReconnectDoesNotReloadTheNewEndpoint() = runBlocking {
        val attempt = Attempt().apply { afterReconnect = { current = false } }
        assertFalse(attempt.retry())
        assertEquals(listOf("service", "host", "wake", "reconnect"), attempt.events)
    }

    @Test fun cancelledNonCooperativeHostProbeCannotStartAWake() = runBlocking {
        val attempt = Attempt().apply { afterHost = { currentCoroutineContext().cancel() } }
        val job = launch { attempt.retry() }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("service", "host"), attempt.events)
    }

    @Test fun cancelledNonCooperativeServiceProbeCannotReloadOrWake() = runBlocking {
        val attempt = Attempt().apply {
            serviceReady = true
            afterService = { currentCoroutineContext().cancel() }
        }
        val job = launch { attempt.retry() }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("service"), attempt.events)
    }

    @Test fun cancelledNonCooperativeWakeCannotStartAnotherReconnect() = runBlocking {
        val attempt = Attempt().apply { afterWake = { currentCoroutineContext().cancel() } }
        val job = launch { attempt.retry() }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("service", "host", "wake"), attempt.events)
    }

    @Test fun cancelledNonCooperativeReconnectCannotReload() = runBlocking {
        val attempt = Attempt().apply { afterReconnect = { currentCoroutineContext().cancel() } }
        val job = launch { attempt.retry() }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("service", "host", "wake", "reconnect"), attempt.events)
    }
}
