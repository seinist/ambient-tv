package com.ambient.tv.wol

import com.ambient.tv.notifications.AppNotice
import com.ambient.tv.notifications.NoticePhase
import com.ambient.tv.notifications.WakeNoticeTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class ManualWakeCoordinatorTest {
    private val posted = mutableListOf<AppNotice>()
    private val notices = WakeNoticeTracker({ posted += it }, {})
    private val sent = mutableListOf<String>()

    private val endpoints = listOf(
        WakeEndpoint("plex", "192.168.1.10", "AA:BB:CC:DD:EE:FF"),
        WakeEndpoint("tvheadend", "192.168.1.10", null),
        WakeEndpoint("files", "nas.local", "aa-bb-cc-dd-ee-ff"),
        WakeEndpoint("emby", "192.168.1.10", "11:22:33:44:55:66"),
    )

    private fun coordinator(reachable: suspend () -> Boolean = { true }) = ManualWakeCoordinator(
        notices = notices,
        group = { system, host, mac -> macsOfGroup(endpoints + WakeEndpoint(system, host, mac)) },
        send = { mac, _, _, _, _ -> sent += mac; Result.success(Unit) },
        reachable = { _, _ -> reachable() },
        windowMs = 10, pollMs = 1,
    )

    @Test
    fun pressingWakeForOneSystemWakesEveryNicOfTheMachineOnce() = runTest {
        val outcome = coordinator().wake(ManualWakeRequest("tvheadend", subject = "TV", host = "192.168.1.10", mac = null, probePort = 9981))
        // TVHeadend has no MAC itself, but its host joins Plex's machine.
        assertEquals(ManualWakeCoordinator.Outcome.AWAKE, outcome)
        assertEquals(listOf("aabbccddeeff", "112233445566"), sent)
        assertEquals(setOf("wake:mac:112233445566"), posted.map { it.key }.toSet())
        assertEquals(NoticePhase.SUCCESS, posted.last().phase)
    }

    @Test
    fun secondPressWhileWakingDoesNotOpenASecondCardOrBurst() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val c = coordinator(reachable = { gate.await() })
        val first = async { c.wake(ManualWakeRequest("plex", subject = "Plex", host = "192.168.1.10", mac = "AA:BB:CC:DD:EE:FF", probePort = 32400)) }
        yield()
        val burstsAfterFirst = sent.size
        // The first wake's card is up (and updated with its sent packet); the second press adds nothing.
        val workingAfterFirst = posted.count { it.phase == NoticePhase.WORKING }
        val second = c.wake(ManualWakeRequest("emby", subject = "Emby", host = "192.168.1.10", mac = "11:22:33:44:55:66", probePort = 8096))
        assertEquals(ManualWakeCoordinator.Outcome.ALREADY_WAKING, second)
        assertEquals(burstsAfterFirst, sent.size)
        assertEquals(workingAfterFirst, posted.count { it.phase == NoticePhase.WORKING })
        gate.complete(true)
        assertEquals(ManualWakeCoordinator.Outcome.AWAKE, first.await())
        assertEquals(1, posted.map { it.key }.distinct().size)
        assertEquals(1, posted.filter { it.phase == NoticePhase.WORKING }.maxOf { it.packetsSent })
    }

    @Test
    fun unreachableAfterTheWaitReportsFailureOnTheSharedCard() = runTest {
        val outcome = coordinator(reachable = { false })
            .wake(ManualWakeRequest("files", "files:1", "NAS", "nas.local", "aa:bb:cc:dd:ee:ff", 445))
        assertEquals(ManualWakeCoordinator.Outcome.FAILED, outcome)
        assertEquals(NoticePhase.ERROR, posted.last().phase)
        assertEquals(listOf("files"), posted.last().systems)
    }

    @Test
    fun withoutAnyMacNothingIsSent() = runTest {
        val outcome = coordinator().wake(ManualWakeRequest("dlna", subject = "x", host = "other.lan", mac = "", probePort = 80))
        assertEquals(ManualWakeCoordinator.Outcome.NO_MAC, outcome)
        assertEquals(emptyList<String>(), sent)
        assertEquals(emptyList<AppNotice>(), posted)
    }
}
