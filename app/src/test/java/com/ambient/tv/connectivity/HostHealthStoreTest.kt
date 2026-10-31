package com.ambient.tv.connectivity

import com.ambient.tv.server.ServerConnectionState
import com.ambient.tv.wol.WakeEndpoint
import com.ambient.tv.wol.resolveWakeGroupKeys
import com.ambient.tv.data.browse.SourceHealth
import com.ambient.tv.data.browse.hostAwareSourceHealth
import org.junit.Assert.*
import org.junit.Test

class HostHealthStoreTest {
    private val store = HostHealthStore()

    @Test fun `playback recovery invalidates all outstanding failures on the host`() {
        val plexRequest = store.begin("nas")
        val tvhRequest = store.begin("nas")
        store.offline(store.begin("nas"))
        store.online("nas")
        assertFalse(store.offline(plexRequest))
        assertFalse(store.offline(tvhRequest))
        assertEquals(HostHealth.ONLINE, store.current("nas").health)
    }

    @Test fun `a genuinely new outage after recovery is still accepted`() {
        store.online("nas")
        assertTrue(store.offline(store.begin("nas")))
        assertEquals(HostHealth.OFFLINE, store.current("nas").health)
    }

    @Test fun `host aliases propagate both directions without touching another host`() {
        val keys = resolveWakeGroupKeys(listOf(
            WakeEndpoint("plex", "NAS.local.", "02:11:22:33:44:55"),
            WakeEndpoint("jellyfin", "nas.local", null),
            WakeEndpoint("files", "192.168.1.10", "02:11:22:33:44:55"),
            WakeEndpoint("tvheadend", "other.local", null),
        ))
        store.offline(store.begin(keys[0]))
        keys.take(3).forEach { assertEquals(HostHealth.OFFLINE, store.current(it).health) }
        assertEquals(HostHealth.UNKNOWN, store.current(keys[3]).health)
        store.online(keys[2])
        keys.take(3).forEach { assertEquals(HostHealth.ONLINE, store.current(it).health) }
        assertEquals(HostHealth.UNKNOWN, store.current(keys[3]).health)
    }

    @Test fun `a recovered host stays offline until the service itself answers`() {
        store.offline(store.begin("nas"))
        fun probe(service: ServerConnectionState) = hostAwareProbe(true, service, store.current("nas").health)
        assertEquals(ServerProbe.UNREACHABLE, probe(ServerConnectionState.Offline))
        store.online("nas")
        // An open machine port alone is not proof that Plex is ready to serve its library.
        val stillStarting = probe(ServerConnectionState.Offline)
        assertEquals(ServerProbe.HOST_REACHABLE, stillStarting)
        assertEquals(setOf("plex"), decideUnreachableServers(UnreachableServerInput(
            mapOf("plex" to stillStarting), setOf("youtube"),
        )).unreachable)
        assertEquals(setOf("plex"), nextOfflineSystems(
            setOf("plex"), mapOf("plex" to stillStarting), settled = true,
        ))
        val identity = com.ambient.tv.domain.model.PlexServerIdentity("id", "1", false)
        val confirmed = probe(ServerConnectionState.Online(identity))
        assertEquals(ServerProbe.REACHABLE, confirmed)
        assertTrue(nextOfflineSystems(setOf("plex"), mapOf("plex" to confirmed), settled = true).isEmpty())
    }

    @Test fun `active waking remains pending until service check finishes`() {
        assertEquals(ServerProbe.PENDING, hostAwareProbe(true, ServerConnectionState.Waking(20), HostHealth.ONLINE))
        assertEquals(ServerProbe.INACTIVE, hostAwareProbe(false, ServerConnectionState.Offline, HostHealth.OFFLINE))
    }

    @Test fun `offline host cannot hide recovery progress or present a failure dialog`() {
        val active = listOf(ServerConnectionState.Checking, ServerConnectionState.SendingWakePacket,
            ServerConnectionState.Waking(12), ServerConnectionState.Connecting)
        for (service in active) {
            for (health in HostHealth.entries) {
                val visible = hostAwareConnectionState(service, health)
                assertEquals(service, visible)
                val decision = decideUnreachableServers(UnreachableServerInput(
                    mapOf("plex" to hostAwareProbe(true, visible, health)), setOf("youtube")))
                assertTrue(decision.pending)
                assertTrue(decision.unreachable.isEmpty())
                assertFalse(decision.canDismiss)
            }
        }
        val failed = hostAwareConnectionState(ServerConnectionState.WakeTimedOut, HostHealth.OFFLINE)
        assertEquals(ServerConnectionState.WakeTimedOut, failed)
        assertEquals(ServerProbe.UNREACHABLE, hostAwareProbe(true, failed, HostHealth.OFFLINE))
        val error = ServerConnectionState.Error("Wake failed")
        assertEquals(error, hostAwareConnectionState(error, HostHealth.OFFLINE))
    }

    @Test fun `a reachable machine with rejected service access is not a usable alternative`() {
        val decision = decideUnreachableServers(UnreachableServerInput(mapOf(
            "plex" to ServerProbe.UNREACHABLE,
            "jellyfin" to hostAwareProbe(true, ServerConnectionState.Error("Unauthorized"), HostHealth.ONLINE),
        )))
        assertEquals(setOf("plex", "jellyfin"), decision.unreachable)
        assertFalse(decision.canDismiss)
    }

    @Test fun `host outage overrides a cached service online state`() {
        assertEquals(SourceHealth.OFFLINE, hostAwareSourceHealth(HostHealth.OFFLINE, SourceHealth.ONLINE))
    }

    @Test fun `host recovery does not grant authenticated service access`() {
        assertEquals(SourceHealth.AUTH_REQUIRED, hostAwareSourceHealth(HostHealth.ONLINE, SourceHealth.AUTH_REQUIRED))
        assertEquals(SourceHealth.UNKNOWN, hostAwareSourceHealth(HostHealth.ONLINE, SourceHealth.OFFLINE))
    }

    @Test fun `success on another host cannot suppress a real outage`() {
        val request = store.begin("nas")
        store.online("other")
        assertTrue(store.offline(request))
        assertEquals(HostHealth.ONLINE, store.current("other").health)
    }
}
