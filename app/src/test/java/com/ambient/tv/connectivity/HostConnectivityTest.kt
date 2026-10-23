package com.ambient.tv.connectivity

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.memoryPreferences
import com.ambient.tv.data.dlna.DlnaStore
import com.ambient.tv.wol.WakeMachines
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HostConnectivityTest {
    private val preferences = AppPreferences(memoryPreferences()).apply {
        plexEnabled = true
        host = "127.0.0.1"
        tvheadendEnabled = true
        tvheadendHost = "127.0.0.1"
        localNetworkOnly = false
    }
    private val dlna = DlnaStore(memoryPreferences())
    private val hosts = HostConnectivity(preferences, dlna, WakeMachines(preferences, dlna))

    @Test fun `one failed service port does not take down a reachable sibling`() = runBlocking {
        val closed = ServerSocket(0).use { it.localPort }
        ServerSocket(0).use { live ->
            preferences.port = closed
            preferences.tvheadendPort = live.localPort
            assertTrue(hosts.verify(hosts.begin("plex")!!))
            assertEquals(HostHealth.ONLINE, hosts.health("plex"))
            assertEquals(HostHealth.ONLINE, hosts.health("tvheadend"))
        }
    }

    @Test fun `all failed ports mark the host offline and fresh connectivity recovers all systems`() = runBlocking {
        val closed = ServerSocket(0).use { it.localPort }
        preferences.port = closed
        preferences.tvheadendPort = closed
        assertFalse(hosts.verify(hosts.begin("plex")!!))
        assertEquals(HostHealth.OFFLINE, hosts.health("tvheadend"))
        ServerSocket(0).use { live ->
            preferences.tvheadendPort = live.localPort
            assertTrue(hosts.verify(hosts.begin("tvheadend")!!))
            assertEquals(HostHealth.ONLINE, hosts.health("plex"))
        }
    }

    @Test fun `old failure cannot recheck and overwrite a newer recovery`() = runBlocking {
        val closed = ServerSocket(0).use { it.localPort }
        preferences.port = closed
        preferences.tvheadendPort = closed
        val old = hosts.begin("plex")!!
        hosts.online(hosts.begin("tvheadend"))
        assertTrue(hosts.verify(old))
        assertEquals(HostHealth.ONLINE, hosts.health("plex"))
    }

    @Test fun `a changed host does not inherit the previous host state`() {
        hosts.online(hosts.begin("plex"))
        preferences.host = "192.0.2.20"
        assertEquals(HostHealth.UNKNOWN, hosts.health("plex"))
        assertEquals(HostHealth.ONLINE, hosts.health("tvheadend"))
    }

    @Test fun `cloud and local playback do not borrow plex host identity`() {
        assertNull(hosts.mediaSystem("youtube", "abc"))
        assertNull(hosts.mediaSystem("spotify", "abc"))
        assertNull(hosts.mediaSystem("shared", "abc"))
        assertEquals("tvheadend", hosts.mediaSystem("tvheadend", "channel"))
    }
}
