package com.ambient.tv.notifications

import com.ambient.tv.R
import org.junit.Assert.*
import org.junit.Test

class ConnectivityStatusTest {
    private var now = 0L
    private val store = NotificationStore({ now }, { now + 1_000 })
    private val status get() = store.state.value.connectivity

    private fun wake(machine: String, phase: NoticePhase = NoticePhase.WORKING) = AppNotice(
        "wake:$machine", if (phase == NoticePhase.SUCCESS) R.string.notice_awake else R.string.notice_waking,
        listOf("plex"), "Media server", phase, NoticeKind.WAKE, machine = machine,
    )

    private fun connection(machine: String, phase: NoticePhase = NoticePhase.WORKING, system: String = "plex") = AppNotice(
        "connection:$machine", if (phase == NoticePhase.SUCCESS) R.string.notice_connected else R.string.notice_connecting,
        listOf(system), "Media server", phase, NoticeKind.CONNECTION, quietSuccess = true, machine = machine,
    )

    private fun vpn(phase: NoticePhase) = AppNotice(
        "vpn", if (phase == NoticePhase.ERROR) R.string.notice_vpn_failed else R.string.notice_vpn_connecting,
        listOf("plex", "tvheadend"), "WireGuard", phase, NoticeKind.VPN, quietSuccess = true,
    )

    @Test fun progressDebouncesButTheSameStateIsAvailableWithNotificationsHidden() {
        store.post(wake("nas"))
        now = 699; store.tick(presenting = false)
        assertFalse(status.visible)
        assertEquals("wake:nas", status.activeWakes.single().key)
        now++; store.tick(presenting = false)
        assertTrue(status.visible)
        assertTrue(store.state.value.visible.isEmpty())
    }

    @Test fun minimizationAndTransientDismissalNeverDiscardOrRestartTheOperation() {
        store.post(wake("nas"))
        now = 700; store.tick()
        store.markConnectivityPresented()
        val session = status.sessionId
        store.minimizeConnectivity()
        store.dismiss("wake:nas")
        assertTrue(status.working)
        assertEquals(1, status.activeWakes.size)
        now = 8_000
        store.post(wake("nas").copy(packetsSent = 2, packets = 3))
        store.tick()
        assertFalse(status.visible)
        assertTrue(status.minimized)
        assertEquals(session, status.sessionId)
        store.restoreConnectivity(); store.tick()
        assertTrue(status.visible)
        assertEquals(2, status.activeWakes.single().packetsSent)
    }

    @Test fun activeOperationOutlivesTheLegacyToastQueueTimeout() {
        store.post(wake("nas"))
        now = 4 * 60_000 + 1L
        store.tick(presenting = false)
        assertTrue(store.state.value.pending.isEmpty())
        assertTrue(status.working)
        assertEquals(1, status.activeWakes.size)
        assertEquals("wake:nas", status.rows.single().cancelKey)
    }

    @Test fun wakeAndServiceStartupShareOneMachineRowAndOneSession() {
        store.post(wake("nas"))
        now = 700; store.tick(); store.markConnectivityPresented()
        val session = status.sessionId
        store.post(wake("nas", NoticePhase.SUCCESS))
        store.post(connection("nas").copy(message = R.string.notice_service_starting))
        store.tick()
        assertEquals(1, status.rows.size)
        assertEquals(session, status.sessionId)
        assertTrue(status.working)
        assertEquals(R.string.notice_service_starting, status.primary?.message)
        assertTrue(status.activeWakes.isEmpty())
        assertEquals(setOf("wake:nas", "connection:nas"), status.rows.single().sourceKeys)
    }

    @Test fun vpnFailureHasOneCauseAndDoesNotMaskAnIndependentServerFailure() {
        store.post(vpn(NoticePhase.ERROR))
        store.post(connection("vpn-a", NoticePhase.ERROR).copy(blockedBy = ConnectivityDependency.VPN))
        store.post(connection("vpn-b", NoticePhase.ERROR, "tvheadend").copy(blockedBy = ConnectivityDependency.VPN))
        store.post(connection("local", NoticePhase.ERROR, "jellyfin"))
        store.tick()
        assertEquals(2, status.rows.count { it.notice.phase == NoticePhase.ERROR })
        assertEquals("vpn", status.primary?.key)
        assertEquals(NoticePhase.INFO, status.rows.first { it.key == "machine:vpn-a" }.notice.phase)
        assertEquals(R.string.notice_waiting_for_vpn, status.rows.first { it.key == "machine:vpn-a" }.notice.message)
        assertEquals(NoticePhase.ERROR, status.rows.first { it.key == "machine:local" }.notice.phase)
        assertFalse(status.working)
    }

    @Test fun vpnConfigurationFailuresBeforeTunnelStartupStillShareOneCause() {
        store.post(connection("a", NoticePhase.ERROR).copy(blockedBy = ConnectivityDependency.VPN))
        store.post(connection("b", NoticePhase.ERROR).copy(blockedBy = ConnectivityDependency.VPN))
        assertEquals(1, status.rows.count { it.notice.phase == NoticePhase.ERROR })
        assertEquals("vpn", status.primary?.key)
        assertEquals(3, status.rows.size)
    }

    @Test fun syntheticVpnCauseKeepsASafeReasonAndOriginalSourceDetails() {
        store.post(connection("a", NoticePhase.ERROR).copy(
            blockedBy = ConnectivityDependency.VPN,
            message = R.string.notice_vpn_failed_reason,
            args = listOf(sanitizeFailureDetail("Consent required token=private")),
        ))
        assertEquals(R.string.notice_vpn_failed_reason, status.primary?.message)
        assertEquals(listOf("Consent required token=…"), status.primary?.args)
        val dependent = status.rows.first { it.key == "machine:a" }
        assertEquals(R.string.notice_waiting_for_vpn, dependent.notice.message)
        assertEquals(listOf("Consent required token=…"), dependent.details.single().args)
    }

    @Test fun aWaitingWakeRemainsCancellableWhenItsDependencyFailed() {
        store.post(vpn(NoticePhase.ERROR))
        store.post(wake("a").copy(blockedBy = ConnectivityDependency.VPN))
        assertEquals(NoticePhase.INFO, status.rows.first { it.key == "machine:a" }.notice.phase)
        assertEquals("wake:a", status.rows.first { it.key == "machine:a" }.cancelKey)
        assertEquals("wake:a", status.activeWakes.single().key)
    }

    @Test fun aHealthyTunnelDoesNotHideAnEndpointSpecificVpnFailure() {
        store.post(vpn(NoticePhase.SUCCESS))
        store.post(connection("outside-route", NoticePhase.ERROR).copy(blockedBy = ConnectivityDependency.VPN))
        assertEquals(NoticePhase.ERROR, status.rows.first { it.key == "machine:outside-route" }.notice.phase)
        assertTrue(status.hasErrors)
    }

    @Test fun pendingDependantsDoNotRunForeverAfterTheirVpnFailed() {
        store.post(vpn(NoticePhase.WORKING))
        store.post(connection("a").copy(blockedBy = ConnectivityDependency.VPN))
        assertTrue(status.working)
        store.post(vpn(NoticePhase.ERROR))
        assertFalse(status.working)
        assertTrue(status.hasErrors)
    }

    @Test fun retryKeepsUnrelatedErrorsAndReplacesOnlyTheRestartedMachineFailure() {
        store.post(connection("a", NoticePhase.ERROR))
        store.post(connection("b", NoticePhase.ERROR, "jellyfin"))
        now = 700; store.tick(); store.markConnectivityPresented()
        store.minimizeConnectivity()
        now = 2_000
        store.post(wake("a"))
        assertEquals(NoticePhase.WORKING, status.rows.first { it.key == "machine:a" }.notice.phase)
        assertEquals(NoticePhase.ERROR, status.rows.first { it.key == "machine:b" }.notice.phase)
        assertEquals(1, status.activeWakes.size)
        assertFalse(status.minimized)
    }

    @Test fun fastAutomaticSuccessStaysSilentEvenWhenHomeFirstAppearsLater() {
        store.post(connection("a"))
        now = 300
        store.post(connection("a", NoticePhase.SUCCESS))
        now = 5_000; store.tick()
        assertFalse(status.visible)
        assertFalse(status.presented)
        assertEquals(NoticePhase.SUCCESS, status.rows.single().notice.phase)
    }

    @Test fun completionPresentedInIntroDoesNotGetANewLifetimeOnHome() {
        store.post(connection("a"))
        now = 700; store.tick(presenting = false); store.markConnectivityPresented()
        now = 1_200; store.post(connection("a", NoticePhase.SUCCESS)); store.tick(presenting = false)
        store.markConnectivityPresented()
        assertEquals(1_200L, status.completionPresentedAt)
        now = 4_199; store.tick()
        assertTrue(status.visible)
        store.markConnectivityPresented()
        assertEquals(1_200L, status.completionPresentedAt)
        now++; store.tick(); store.restoreConnectivity(); store.tick()
        assertFalse(status.visible)
        assertEquals(1, status.rows.size)
    }

    @Test fun aNewIndependentErrorGetsReadingTimeWithoutReplayingUnchangedErrors() {
        store.post(connection("a", NoticePhase.ERROR))
        store.tick(); store.markConnectivityPresented()
        now = 8_000; store.tick()
        assertFalse(status.visible)
        store.post(connection("b", NoticePhase.ERROR, "jellyfin"))
        store.tick(); store.markConnectivityPresented()
        assertTrue(status.visible)
        assertEquals(8_000L, status.completionPresentedAt)
        assertEquals(2, status.rows.count { it.notice.phase == NoticePhase.ERROR })
        now = 15_000; store.tick()
        assertFalse(status.visible)
        store.post(connection("b", NoticePhase.ERROR, "jellyfin"))
        store.tick()
        assertFalse(status.visible)
    }

    @Test fun retainedStatusReservesOneOrdinarySlotWithoutConsumingTheQueuedErrorsReadingTime() {
        store.post(wake("a"))
        now = 700; store.tick(); store.markConnectivityPresented()
        store.minimizeConnectivity(); store.dismiss("wake:a")
        store.post(AppNotice("first", 1, phase = NoticePhase.ERROR, kind = NoticeKind.SETTINGS))
        store.post(AppNotice("second", 1, phase = NoticePhase.ERROR, kind = NoticeKind.SETTINGS))
        store.tick()
        assertEquals(1, store.state.value.visible.count { it.notice.kind == NoticeKind.SETTINGS })
        val hidden = store.state.value.pending.first { it.notice.kind == NoticeKind.SETTINGS && it.shownAt == null }
        val visible = store.state.value.visible.single()
        now = 5_000; store.dismiss(visible.notice.key); store.tick()
        assertEquals(hidden.id, store.state.value.visible.single().id)
        assertEquals(now, store.state.value.visible.single().shownAt)
    }

    @Test fun cancelledResultKeepsItsBadgeSlotAfterTheBriefCompletionExpired() {
        store.post(wake("a"))
        now = 700; store.tick(); store.markConnectivityPresented()
        store.post(wake("a", NoticePhase.INFO).copy(cancelled = true, message = R.string.notice_wake_cancelled))
        store.tick(); store.markConnectivityPresented()
        now = 5_000; store.tick()
        assertFalse(status.visible)
        assertTrue(status.rows.single().notice.cancelled)
        store.post(AppNotice("first", 1, phase = NoticePhase.ERROR, kind = NoticeKind.SETTINGS))
        store.post(AppNotice("second", 1, phase = NoticePhase.ERROR, kind = NoticeKind.SETTINGS))
        store.tick()
        assertEquals(1, store.state.value.visible.size)
        assertEquals(1, store.state.value.pending.count { it.notice.kind == NoticeKind.SETTINGS && it.shownAt == null })
    }

    @Test fun intermediateSuccessCannotCompleteTheSessionWhileAnotherMachineIsWaking() {
        store.post(wake("a")); store.post(wake("b"))
        now = 700; store.tick(); store.markConnectivityPresented()
        store.post(wake("a", NoticePhase.SUCCESS)); store.tick()
        assertTrue(status.working)
        assertEquals(1, status.workingCount)
        assertEquals(1, status.readyCount)
        assertNull(status.completedAt)
        assertEquals(NoticePhase.WORKING, status.primary?.phase)
    }

    @Test fun removingAnOperationActuallyClearsItsWorkingState() {
        store.post(wake("a")); store.post(wake("b"))
        store.removeConnectivity("wake:a")
        assertEquals(listOf("wake:b"), status.activeWakes.map(AppNotice::key))
        store.removeConnectivity("wake:b")
        assertFalse(status.working)
        assertTrue(status.rows.isEmpty())
    }
}
