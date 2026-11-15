package com.ambient.tv.connectivity

import com.ambient.tv.notifications.AppNotice
import com.ambient.tv.notifications.ConnectivityDependency
import com.ambient.tv.notifications.ConnectivitySnapshot
import com.ambient.tv.notifications.NoticeEntry
import com.ambient.tv.notifications.NoticeKind
import com.ambient.tv.notifications.NoticePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStatusActionPlanTest {
    private fun failed(system: String, machine: String, blockedBy: ConnectivityDependency? = null) = AppNotice(
        "connection:$machine", 0, systems = listOf(system), phase = NoticePhase.ERROR,
        kind = NoticeKind.CONNECTION, machine = machine, blockedBy = blockedBy,
    )

    @Test fun aDeviceRetryIncludesItsFailedSiblingsButNoOtherMachine() {
        val failed = failed("plex", "mac:a")
        val plan = planConnectionRetry(failed, listOf(failed), listOf(
            ConnectionRetryTarget("plex", "plex", "mac:a"),
            ConnectionRetryTarget("jellyfin", "jellyfin", "mac:a"),
            ConnectionRetryTarget("tvheadend", "tvheadend", "mac:b"),
            ConnectionRetryTarget("files:1", "files", "mac:a", manual = true),
        ), vpnConfigured = false)
        assertEquals(setOf("plex", "jellyfin"), plan.systems)
        assertTrue(plan.manualIds.isEmpty())
        assertFalse(plan.restoreVpn)
    }

    @Test fun aVpnRootRetriesOnlyItsFailedDependantsEvenOnASharedMachine() {
        val vpn = AppNotice("vpn", 0, phase = NoticePhase.ERROR, kind = NoticeKind.VPN)
        val plan = planConnectionRetry(vpn, listOf(
            failed("plex", "mac:a", ConnectivityDependency.VPN),
            failed("jellyfin", "mac:a"),
            failed("tvheadend", "mac:b", ConnectivityDependency.NETWORK),
            failed("emby", "mac:c", ConnectivityDependency.VPN).copy(phase = NoticePhase.SUCCESS),
        ), listOf(
            ConnectionRetryTarget("plex", "plex", "mac:a", requiresVpn = true),
            ConnectionRetryTarget("jellyfin", "jellyfin", "mac:a", requiresVpn = true),
            ConnectionRetryTarget("tvheadend", "tvheadend", "mac:b"),
        ), vpnConfigured = true)
        assertTrue(plan.restoreVpn)
        assertEquals(setOf("plex"), plan.systems)
        assertTrue(plan.manualIds.isEmpty())
    }

    @Test fun aNasRetryPreparesItsVpnAndKeepsTheExactManualEndpoint() {
        val failure = failed("files", "host:nas", ConnectivityDependency.VPN).copy(key = "wake:host:nas", kind = NoticeKind.WAKE)
        val plan = planConnectionRetry(failure, listOf(failure), listOf(
            ConnectionRetryTarget("files:saved", "files", "host:nas", manual = true, requiresVpn = true),
            ConnectionRetryTarget("manual:host:nas", "files", "host:nas", manual = true, requiresVpn = true, preferred = true),
            ConnectionRetryTarget("files:other", "files", "host:other", manual = true),
        ), vpnConfigured = true)
        assertTrue(plan.restoreVpn)
        assertEquals(setOf("manual:host:nas"), plan.manualIds)
        assertTrue(plan.systems.isEmpty())
    }

    @Test fun aDlnaRetryChoosesItsProbeInsteadOfAnUnrelatedManualNasEndpoint() {
        val failure = failed("dlna", "mac:a").copy(key = "wake:mac:a", kind = NoticeKind.WAKE)
        val plan = planConnectionRetry(failure, listOf(failure), listOf(
            ConnectionRetryTarget("manual:mac:a", "files", "mac:a", manual = true, preferred = true),
            ConnectionRetryTarget("dlna:server", "dlna", "mac:a", manual = true),
        ), vpnConfigured = false)
        assertEquals(setOf("dlna:server"), plan.manualIds)
    }

    @Test fun aGenericFilesNoticeDoesNotWakeEveryConfiguredNas() {
        val failure = AppNotice("unscoped", 0, listOf("files"), phase = NoticePhase.ERROR, kind = NoticeKind.WAKE)
        val plan = planConnectionRetry(failure, listOf(failure), listOf(
            ConnectionRetryTarget("files:1", "files", "mac:a", manual = true),
            ConnectionRetryTarget("files:2", "files", "mac:b", manual = true),
        ), vpnConfigured = false)
        assertFalse(plan.possible)
    }

    @Test fun cancellationIsRetryableButACompletedSuccessIsNot() {
        val cancelled = failed("plex", "mac:a").copy(phase = NoticePhase.INFO, cancelled = true)
        val targets = listOf(ConnectionRetryTarget("plex", "plex", "mac:a"))
        assertEquals(setOf("plex"), planConnectionRetry(cancelled, listOf(cancelled), targets, false).systems)
        val success = cancelled.copy(phase = NoticePhase.SUCCESS, cancelled = false)
        assertFalse(planConnectionRetry(success, listOf(success), targets, false).possible)
        assertFalse(planConnectionRetry(cancelled.copy(phase = NoticePhase.WORKING, cancelled = false),
            emptyList(), targets, false).possible)
    }

    @Test fun theTypedMachineIdentityOverridesPresentationKeys() {
        val notice = failed("plex", "host:real").copy(key = "connection:host:old:vpn")
        assertEquals("host:real", connectionNoticeMachine(notice))
        assertEquals("mac:1234", connectionNoticeMachine(notice.copy(machine = null, key = "wake:mac:1234")))
        assertEquals("host:real", connectionNoticeMachine(notice.copy(machine = null, key = "connection:host:real:vpn")))
    }

    @Test fun aVpnWithoutConfiguredTargetsDoesNotOfferAnEmptyRetry() {
        val vpn = AppNotice("vpn", 0, phase = NoticePhase.ERROR, kind = NoticeKind.VPN)
        assertFalse(planConnectionRetry(vpn, emptyList(), emptyList(), vpnConfigured = false).possible)
        assertTrue(planConnectionRetry(vpn, emptyList(), emptyList(), vpnConfigured = true).restoreVpn)
    }

    @Test fun aSyntheticVpnFailureRemainsRetryableWhenTheControllerIsIdle() {
        val idle = AppNotice("vpn", 0, phase = NoticePhase.INFO, kind = NoticeKind.VPN)
        val blocked = failed("plex", "mac:a", ConnectivityDependency.VPN)
        fun snapshot(vpn: AppNotice) = ConnectivitySnapshot(entries = listOf(
            NoticeEntry(1, vpn, 0, 0, 0), NoticeEntry(2, blocked, 0, 0, 0),
        ))
        val current = currentConnectionRetryNotice(idle.copy(phase = NoticePhase.ERROR), snapshot(idle))!!
        assertEquals(NoticePhase.ERROR, current.phase)
        val targets = listOf(ConnectionRetryTarget("plex", "plex", "mac:a", requiresVpn = true))
        assertTrue(planConnectionRetry(current, listOf(blocked), targets, vpnConfigured = true).restoreVpn)
        val recovered = currentConnectionRetryNotice(current, snapshot(idle.copy(phase = NoticePhase.SUCCESS)))!!
        assertFalse(planConnectionRetry(recovered, listOf(blocked), targets, vpnConfigured = true).possible)
        assertNull(currentConnectionRetryNotice(blocked, ConnectivitySnapshot()))
    }

    @Test fun networkRetryChecksTheNetworkEvenWithoutFailedMediaTargets() {
        val network = AppNotice("network", 0, phase = NoticePhase.ERROR)
        val plan = planConnectionRetry(network, listOf(network), emptyList(), vpnConfigured = false)
        assertTrue(plan.possible)
        assertTrue(plan.checkNetwork)
        assertTrue(plan.systems.isEmpty())
        assertTrue(plan.manualIds.isEmpty())
        assertFalse(plan.restoreVpn)
    }

    @Test fun networkRetryRechecksOnlyFailedTargetsAndHonoursExplicitCancellation() {
        val network = AppNotice("network", 0, phase = NoticePhase.ERROR)
        val cancelled = failed("jellyfin", "mac:b").copy(phase = NoticePhase.INFO, cancelled = true)
        val plan = planConnectionRetry(network, listOf(network, cancelled), listOf(
            // Actual healthy and in-flight media services are already absent from these targets.
            ConnectionRetryTarget("plex", "plex", "mac:a"),
            ConnectionRetryTarget("jellyfin", "jellyfin", "mac:b"),
            ConnectionRetryTarget("files:unrelated", "files", "mac:c", manual = true),
        ), vpnConfigured = false)
        assertTrue(plan.checkNetwork)
        assertEquals(setOf("plex"), plan.systems)
        assertTrue(plan.manualIds.isEmpty())
    }

    @Test fun batchSelectionIncludesBothMachinesOnceAndExcludesHealthySiblings() {
        val offline = setOf("plex", "jellyfin", "tvheadend")
        val machines = mapOf("plex" to "a", "jellyfin" to "a", "emby" to "a", "tvheadend" to "b")
        assertEquals(offline, selectedOfflineRetryTargets(offline, setOf("plex", "tvheadend"), machines::get))
        assertEquals(setOf("plex", "jellyfin"), selectedOfflineRetryTargets(offline, setOf("jellyfin"), machines::get))
        assertTrue(selectedOfflineRetryTargets(offline, setOf("emby"), machines::get).isEmpty())
    }
}
