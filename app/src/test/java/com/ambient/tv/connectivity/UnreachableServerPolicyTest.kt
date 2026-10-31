package com.ambient.tv.connectivity

import com.ambient.tv.server.ServerConnectionState
import com.ambient.tv.ui.common.MediaSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnreachableServerPolicyTest {
    @Test
    fun `nothing else usable keeps the blocking state`() {
        val decision = decideUnreachableServers(
            UnreachableServerInput(
                probes = mapOf("plex" to ServerProbe.UNREACHABLE),
            ),
        )
        assertFalse(decision.canDismiss)
        assertEquals(setOf("plex"), decision.unreachable)
    }

    @Test
    fun `another reachable media server keeps the app usable`() {
        val decision = decideUnreachableServers(
            UnreachableServerInput(
                probes = mapOf(
                    "plex" to ServerProbe.UNREACHABLE,
                    "jellyfin" to ServerProbe.REACHABLE,
                ),
            ),
        )
        assertTrue(decision.canDismiss)
        assertEquals(setOf("plex"), nextOfflineSystems(emptySet(), mapOf("plex" to ServerProbe.UNREACHABLE, "jellyfin" to ServerProbe.REACHABLE), settled = true))
    }

    @Test
    fun `an independent system keeps the app usable`() {
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
    fun `a check still settling shows nothing offline yet`() {
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
        assertTrue(nextOfflineSystems(emptySet(), mapOf("plex" to ServerProbe.UNREACHABLE), settled = false).isEmpty())
    }

    @Test
    fun `an offline server stays offline through rechecks and wakes until it answers`() {
        val offline = setOf("plex")
        assertEquals(offline, nextOfflineSystems(offline, mapOf("plex" to ServerProbe.PENDING), settled = false))
        assertEquals(offline, nextOfflineSystems(offline, mapOf("plex" to ServerProbe.UNREACHABLE), settled = false))
        assertTrue(nextOfflineSystems(offline, mapOf("plex" to ServerProbe.REACHABLE), settled = true).isEmpty())
        assertEquals(offline, nextOfflineSystems(offline, mapOf("plex" to ServerProbe.HOST_REACHABLE), settled = true))
        assertTrue(nextOfflineSystems(offline, mapOf("plex" to ServerProbe.INACTIVE), settled = true).isEmpty())
    }

    @Test
    fun `a reachable host with an unready service remains offline`() {
        val probes = mapOf("plex" to ServerProbe.HOST_REACHABLE)
        assertEquals(setOf("plex"), decideUnreachableServers(UnreachableServerInput(probes)).unreachable)
        assertEquals(setOf("plex"), nextOfflineSystems(emptySet(), probes, settled = true))
        assertEquals(
            ServerProbe.HOST_REACHABLE,
            hostAwareProbe(true, ServerConnectionState.Offline, HostHealth.ONLINE),
        )
        assertEquals(
            ServerProbe.INACTIVE,
            hostAwareProbe(true, ServerConnectionState.NotConfigured, HostHealth.ONLINE),
        )
        assertTrue(nextOfflineSystems(setOf("plex"), mapOf("plex" to ServerProbe.REACHABLE), settled = true).isEmpty())
    }

    @Test
    fun `retry one offline server also includes wakeable siblings on its machine`() {
        val offline = setOf("plex", "jellyfin", "tvheadend")
        val machine: (String) -> String? = { system ->
            if (system == "plex" || system == "jellyfin") "host-A" else "host-B"
        }
        assertEquals(offline, offlineRetryTargets(offline, null, machine))
        assertEquals(setOf("plex", "jellyfin"), offlineRetryTargets(offline, "jellyfin", machine))
        assertEquals(setOf("tvheadend"), offlineRetryTargets(offline, "tvheadend", machine))
        assertTrue(offlineRetryTargets(offline, "emby", machine).isEmpty())
    }

    @Test
    fun `library routes name their media server`() {
        assertEquals("plex", libraryRouteSystem("plex"))
        assertEquals("plex", libraryRouteSystem("section/42"))
        assertEquals("jellyfin", libraryRouteSystem("section/j:abc"))
        assertEquals("emby", libraryRouteSystem("section/e:1"))
        assertEquals("emby", libraryRouteSystem("emby"))
        assertNull(libraryRouteSystem("player/123"))
        assertNull(libraryRouteSystem("settings"))
        assertNull(libraryRouteSystem("home"))
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
    fun `resume result handoff keeps the server out of offline until full wake starts`() {
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
        assertTrue(handoff.pending)
        assertTrue(nextOfflineSystems(emptySet(), mapOf("plex" to ServerProbe.UNREACHABLE), settled = !handoff.pending).isEmpty())
        assertTrue(resumeHoldsUnreachablePrompt(ResumeConnectivityStatus.Checking))
        assertFalse(resumeHoldsUnreachablePrompt(ResumeConnectivityStatus.Idle))
    }

    @Test
    fun `offline TVHeadend counts like a media server`() {
        val tvheadend = hostAwareProbe(true, ServerConnectionState.Offline, HostHealth.OFFLINE)
        val decision = decideUnreachableServers(UnreachableServerInput(
            probes = mapOf("plex" to ServerProbe.REACHABLE, "tvheadend" to tvheadend),
        ))
        assertTrue(decision.canDismiss)
        assertEquals(setOf("tvheadend"), decision.unreachable)
    }
}
