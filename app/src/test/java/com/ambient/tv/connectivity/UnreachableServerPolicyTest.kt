package com.ambient.tv.connectivity

import com.ambient.tv.server.ServerConnectionState
import com.ambient.tv.ui.common.MediaSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnreachableServerPolicyTest {
    @Test
    fun `confirm does nothing when no other system works`() {
        val decision = decideUnreachableServers(
            UnreachableServerInput(
                probes = mapOf("plex" to ServerProbe.UNREACHABLE),
            ),
        )
        assertFalse(decision.canDismiss)
        assertEquals(setOf("plex"), decision.unreachable)
        assertEquals(emptySet<String>(), acknowledgedAfterConfirm(emptySet(), decision))
        assertTrue(decision.promptBlocked())
    }

    @Test
    fun `confirm dismisses when another media server is reachable`() {
        val decision = decideUnreachableServers(
            UnreachableServerInput(
                probes = mapOf(
                    "plex" to ServerProbe.UNREACHABLE,
                    "jellyfin" to ServerProbe.REACHABLE,
                ),
            ),
        )
        assertTrue(decision.canDismiss)
        assertEquals(setOf("plex"), acknowledgedAfterConfirm(emptySet(), decision))
        assertEquals(setOf("plex"), hiddenUnreachableSystems(decision.unreachable, setOf("plex")))
    }

    @Test
    fun `confirm dismisses when an independent system is active`() {
        val decision = decideUnreachableServers(
            UnreachableServerInput(
                probes = mapOf("plex" to ServerProbe.UNREACHABLE, "emby" to ServerProbe.INACTIVE),
                activeIndependentSystems = setOf("youtube"),
            ),
        )
        assertTrue(decision.canDismiss)
        assertFalse(decision.pending)
    }

    @Test
    fun `a server that is still waking does not open the dialog yet`() {
        val decision = decideUnreachableServers(
            UnreachableServerInput(
                probes = mapOf(
                    "plex" to ServerProbe.UNREACHABLE,
                    "jellyfin" to ServerProbe.PENDING,
                ),
                activeIndependentSystems = setOf("youtube"),
            ),
        )
        assertTrue(decision.pending)
        assertFalse(decision.canDismiss)
    }

    @Test
    fun `hiding does not outlive reachability and does not imply deletion`() {
        val hidden = hiddenUnreachableSystems(setOf("plex"), setOf("plex", "jellyfin"))
        assertEquals(setOf("plex"), hidden)
        assertEquals(emptySet<String>(), hiddenUnreachableSystems(emptySet(), setOf("plex")))
    }

    @Test
    fun `continue remains acknowledged across checking recovery and subsequent failure`() {
        val acknowledged = setOf("plex")
        for (failed in listOf(emptySet(), setOf("plex"))) {
            assertFalse(UnreachableServerGateState(
                unreachable = failed, acknowledged = acknowledged, canDismiss = true,
            ).prompt)
        }
    }

    @Test
    fun `library routes of a hidden server are left, playback and settings are not`() {
        assertTrue(libraryRouteOwnedByHiddenSystem("plex", setOf("plex")))
        assertTrue(libraryRouteOwnedByHiddenSystem("section/42", setOf("plex")))
        assertTrue(libraryRouteOwnedByHiddenSystem("section/j:abc", setOf("jellyfin")))
        assertFalse(libraryRouteOwnedByHiddenSystem("section/j:abc", setOf("plex")))
        assertFalse(libraryRouteOwnedByHiddenSystem("section/e:1", setOf("plex")))
        assertTrue(libraryRouteOwnedByHiddenSystem("emby", setOf("emby")))
        assertFalse(libraryRouteOwnedByHiddenSystem("player/123", setOf("plex")))
        assertFalse(libraryRouteOwnedByHiddenSystem("settings", setOf("plex")))
        assertFalse(libraryRouteOwnedByHiddenSystem("home", setOf("plex")))
    }

    @Test
    fun `dialog is presented only when continue can dismiss`() {
        assertTrue(shouldPresentUnreachableDialog("home", prompt = true, canDismiss = true))
        assertFalse(shouldPresentUnreachableDialog("home", prompt = true, canDismiss = false))
        assertFalse(shouldPresentUnreachableDialog("settings", prompt = true, canDismiss = true))
        assertFalse(shouldPresentUnreachableDialog("player/1", prompt = true, canDismiss = true))
        assertFalse(shouldPresentUnreachableDialog("initial_setup", prompt = true, canDismiss = true))
    }

    @Test
    fun `connection states map onto probes without treating a check as failure`() {
        assertEquals(ServerProbe.INACTIVE, probeOf(false, ServerConnectionState.Offline))
        assertEquals(ServerProbe.INACTIVE, probeOf(true, ServerConnectionState.NotConfigured))
        assertEquals(ServerProbe.PENDING, probeOf(true, ServerConnectionState.Waking(3)))
        assertEquals(ServerProbe.UNREACHABLE, probeOf(true, ServerConnectionState.Offline))
        assertEquals(ServerProbe.UNREACHABLE, probeOf(true, ServerConnectionState.WakeTimedOut))
        assertEquals(ServerProbe.UNREACHABLE, probeOf(true, ServerConnectionState.Error("down")))
        assertEquals(
            ServerProbe.REACHABLE,
            probeOf(true, ServerConnectionState.Online(com.ambient.tv.domain.model.PlexServerIdentity("m", "n", false))),
        )
    }

    @Test
    fun `resume result handoff keeps unreachable prompt pending until full wake starts`() {
        val timedOut = decideUnreachableServers(
            UnreachableServerInput(
                probes = mapOf("plex" to ServerProbe.UNREACHABLE),
                activeIndependentSystems = setOf("youtube"),
            ),
        )
        assertTrue(timedOut.canDismiss)

        val completedBriefWake = ResumeConnectivityStatus.Progress(
            networkOk = true,
            vpnLabel = null,
            servers = listOf(
                ResumeConnectivityStatus.ServerProgress(
                    MediaSystem.PLEX,
                    ResumeConnectivityStatus.ServerPhase.OFFLINE,
                ),
            ),
        )
        val handoff = UnreachableServerGateState(
            unreachable = timedOut.unreachable,
            pending = timedOut.pending || resumeHoldsUnreachablePrompt(completedBriefWake),
            canDismiss = timedOut.canDismiss,
        )
        assertFalse(handoff.prompt)
        assertTrue(resumeHoldsUnreachablePrompt(ResumeConnectivityStatus.Checking))
        assertFalse(resumeHoldsUnreachablePrompt(ResumeConnectivityStatus.Idle))

        val fullWake = decideUnreachableServers(
            UnreachableServerInput(
                probes = mapOf("plex" to ServerProbe.PENDING),
                activeIndependentSystems = setOf("youtube"),
            ),
        )
        assertTrue(fullWake.pending)
        assertFalse(UnreachableServerGateState(
            unreachable = fullWake.unreachable,
            pending = fullWake.pending,
            canDismiss = fullWake.canDismiss,
        ).prompt)
    }

    @Test
    fun `offline TVHeadend is hidden after continue like a media server`() {
        assertEquals(ServerProbe.UNREACHABLE, tvheadendProbeOf(true, HostHealth.OFFLINE, servingCache = false))
        assertEquals(ServerProbe.UNREACHABLE, tvheadendProbeOf(true, HostHealth.UNKNOWN, servingCache = true))
        assertEquals(ServerProbe.REACHABLE, tvheadendProbeOf(true, HostHealth.ONLINE, servingCache = true))
        assertEquals(ServerProbe.INACTIVE, tvheadendProbeOf(true, HostHealth.UNKNOWN, servingCache = false))
        assertEquals(ServerProbe.INACTIVE, tvheadendProbeOf(false, HostHealth.OFFLINE, servingCache = true))
        val decision = decideUnreachableServers(UnreachableServerInput(
            probes = mapOf("plex" to ServerProbe.REACHABLE, "tvheadend" to tvheadendProbeOf(true, HostHealth.OFFLINE, false)),
        ))
        assertTrue(decision.canDismiss)
        assertEquals(setOf("tvheadend"), hiddenUnreachableSystems(decision.unreachable, acknowledgedAfterConfirm(emptySet(), decision)))
    }

    private fun UnreachableServerDecision.promptBlocked(): Boolean = !canDismiss && unreachable.isNotEmpty() && !pending
}
