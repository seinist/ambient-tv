package com.ambient.tv.wol

import com.ambient.tv.notifications.AppNotice
import com.ambient.tv.notifications.NoticePhase
import com.ambient.tv.notifications.WakeNoticeTracker
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualWakePrecheckTest {
    private class Attempt {
        val events = mutableListOf<String>()
        val posted = mutableListOf<AppNotice>()
        var serviceReady = true
        var ownerCurrent = true
        private val notices = WakeNoticeTracker({ posted += it }, {})
        private val coordinator = ManualWakeCoordinator(
            notices = notices,
            group = { _, _, _ -> "mac:aabbccddeeff" to listOf("aabbccddeeff") },
            send = { _, _, _, _, _ -> events += "send"; Result.success(Unit) },
            reachable = { _, _ -> events += "service"; serviceReady },
            retries = { 2 },
            answered = { events += "answered" },
            windowMs = 1_000L,
            pollMs = 1L,
        )
        private val request = ManualWakeRequest(
            system = "plex", subject = "NAS", host = "nas.local",
            mac = "aa:bb:cc:dd:ee:ff", probePort = 32400,
        )

        suspend fun retry(probe: suspend () -> Boolean) = coordinator.wake(request, stillCurrent = { ownerCurrent }) {
            events += "probe"
            probe()
        }

        suspend fun explicitWake() = coordinator.wake(request)
    }

    @Test fun machineReturningDuringVpnSetupNeedsNoMagicPacket() = runBlocking {
        val attempt = Attempt()
        assertEquals(ManualWakeCoordinator.Outcome.AWAKE, attempt.retry { true })
        assertEquals(listOf("probe", "answered"), attempt.events)
        assertEquals(NoticePhase.SUCCESS, attempt.posted.last().phase)
        assertEquals(0, attempt.posted.last().packetsSent)
    }

    @Test fun stillOfflineMachineUsesExistingWakeAfterTheFreshProbe() = runBlocking {
        val attempt = Attempt()
        assertEquals(ManualWakeCoordinator.Outcome.AWAKE, attempt.retry { false })
        assertEquals(listOf("probe", "send", "probe", "service", "answered"), attempt.events)
        assertEquals(NoticePhase.SUCCESS, attempt.posted.last().phase)
        assertEquals(1, attempt.posted.last().packetsSent)
    }

    @Test fun siblingReturningWhileTheSelectedServiceStartsStopsFurtherWakeBursts() = runBlocking {
        val attempt = Attempt().apply { serviceReady = false }
        var probes = 0
        assertEquals(ManualWakeCoordinator.Outcome.AWAKE, attempt.retry { ++probes >= 3 })
        assertEquals(listOf("probe", "send", "probe", "service", "probe", "answered"), attempt.events)
        assertEquals(NoticePhase.SUCCESS, attempt.posted.last().phase)
        assertEquals(1, attempt.posted.last().packetsSent)
    }

    @Test fun cancellationDuringANonCooperativeProbeCannotSendAMagicPacket() = runBlocking {
        for (ready in listOf(false, true)) {
            val attempt = Attempt()
            val job = launch {
                attempt.retry {
                    currentCoroutineContext().cancel()
                    ready
                }
            }
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(listOf("probe"), attempt.events)
            assertEquals(NoticePhase.INFO, attempt.posted.last().phase)
            assertTrue(attempt.posted.last().cancelled)
            assertEquals(0, attempt.posted.last().packetsSent)
        }
    }

    @Test fun explicitSettingsWakeRetainsItsIntentAndSendsWithoutAPrecheck() = runBlocking {
        val attempt = Attempt()
        assertEquals(ManualWakeCoordinator.Outcome.AWAKE, attempt.explicitWake())
        assertEquals(listOf("send", "service", "answered"), attempt.events)
        assertEquals(1, attempt.posted.last().packetsSent)
    }

    @Test fun changedOwnerAfterCachedProbeCannotSendAMagicPacket() = runBlocking {
        val attempt = Attempt()
        val job = launch {
            attempt.retry {
                attempt.ownerCurrent = false
                false
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("probe"), attempt.events)
        assertTrue(attempt.posted.last().cancelled)
        assertEquals(0, attempt.posted.last().packetsSent)
    }
}
