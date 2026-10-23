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

    @Test fun `returning home after playback recovery cannot show the old dialog`() {
        store.offline(store.begin("nas"))
        fun decision() = decideUnreachableServers(UnreachableServerInput(
            mapOf("plex" to hostAwareProbe(true, ServerConnectionState.Offline, store.current("nas").health)),
            setOf("youtube"),
        ))
        assertTrue(decision().unreachable.isNotEmpty())
        store.online("nas")
        val recovered = decision()
        val gate = UnreachableServerGateState(unreachable = recovered.unreachable, canDismiss = recovered.canDismiss)
        assertFalse(gate.prompt)
        assertFalse(shouldPresentUnreachableDialog("home", gate.prompt, gate.canDismiss))
        assertTrue(gate.hidden.isEmpty())
    }

    @Test fun `old waking state cannot hold recovery pending`() {
        assertEquals(ServerProbe.HOST_REACHABLE, hostAwareProbe(true, ServerConnectionState.Waking(20), HostHealth.ONLINE))
        assertEquals(ServerProbe.INACTIVE, hostAwareProbe(false, ServerConnectionState.Offline, HostHealth.OFFLINE))
    }

    @Test fun `a reachable machine with rejected service access is not a usable alternative`() {
        val decision = decideUnreachableServers(UnreachableServerInput(mapOf(
            "plex" to ServerProbe.UNREACHABLE,
            "jellyfin" to hostAwareProbe(true, ServerConnectionState.Error("Unauthorized"), HostHealth.ONLINE),
        )))
        assertEquals(setOf("plex"), decision.unreachable)
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
