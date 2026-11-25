package com.ambient.tv.connectivity

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.memoryPreferences
import com.ambient.tv.data.dlna.DlnaStore
import com.ambient.tv.data.dlna.DlnaServer
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.domain.model.JellyfinServerProfile
import com.ambient.tv.wol.WakeMachines
import java.net.ServerSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HostConnectivityTest {
    private val storedPreferences = memoryPreferences()
    private val preferences = AppPreferences(storedPreferences).apply {
        plexEnabled = true
        host = "127.0.0.1"
        tvheadendEnabled = true
        tvheadendHost = "127.0.0.1"
        localNetworkOnly = false
    }
    private val dlna = DlnaStore(memoryPreferences())
    private val machines = WakeMachines(preferences, dlna)
    private val hosts = HostConnectivity(preferences, dlna, machines)

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

    @Test fun `recovery rechecks an online host that has since gone to sleep`() = runBlocking {
        val live = ServerSocket(0)
        preferences.port = live.localPort
        preferences.tvheadendPort = live.localPort
        try {
            assertTrue(hosts.verifySystem("plex"))
        } finally { live.close() }
        assertEquals(HostHealth.ONLINE, hosts.health("plex"))
        // This result controls whether recovery may skip sending a wake packet.
        assertFalse(hosts.verifySystem("plex"))
        assertEquals(HostHealth.OFFLINE, hosts.health("plex"))
    }

    @Test fun `fresh recovery check still accepts a live sibling service`() = runBlocking {
        preferences.port = ServerSocket(0).use { it.localPort }
        ServerSocket(0).use { live ->
            preferences.tvheadendPort = live.localPort
            assertTrue(hosts.verifySystem("plex"))
            assertEquals(HostHealth.ONLINE, hosts.health("tvheadend"))
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

    @Test fun `repeated health reads and unrelated settings reuse the same routing topology`() {
        val endpoints = hosts.endpoints()
        val topology = machines.snapshot()
        repeat(100) {
            hosts.health("plex")
            hosts.health("tvheadend")
            assertSame(endpoints, hosts.endpoints())
            assertSame(topology, machines.snapshot())
        }
        hosts.online(hosts.begin("plex"))
        storedPreferences.edit().putInt("game_library_grid_columns", 10).commit()
        assertSame(endpoints, hosts.endpoints())
        assertSame(topology, machines.snapshot())
    }

    @Test fun `direct import edits immediately replace host port and enabled endpoints`() {
        val before = hosts.endpoints()
        storedPreferences.edit().putString("server_host", "192.0.2.40").putInt("server_port", 43210).commit()
        val imported = hosts.endpoints()
        assertNotSame(before, imported)
        assertEquals("192.0.2.40", imported.first { it.system == "plex" }.host)
        assertEquals(43210, imported.first { it.system == "plex" }.port)
        storedPreferences.edit().putBoolean("plex_enabled", false).commit()
        assertNull(hosts.machine("plex"))
        assertNotNull(hosts.machine("tvheadend"))
    }

    @Test fun `saved profile and NAS changes immediately update transitive machine membership`() {
        preferences.jellyfinEnabled = true
        preferences.upsertJellyfinServerProfile(JellyfinServerProfile(
            id = "saved", name = "Saved server", host = "archive.local", userId = "viewer",
            macAddress = "AA:BB:CC:DD:EE:10",
        ), makeActive = false)
        preferences.fileSources = listOf(FileSource(
            id = "nas", name = "NAS", kind = "smb", host = "127.0.0.1", share = "media",
            wolMac = "AA:BB:CC:DD:EE:10",
        ))
        val profile = "jellyfin:saved:viewer"
        assertEquals("mac:aabbccddee10", hosts.machine(profile))
        assertEquals(hosts.machine(profile), hosts.machine("plex"))
        assertEquals(hosts.machine(profile), hosts.machine("files:nas"))
        preferences.fileSources = emptyList()
        assertNull(hosts.machine("files:nas"))
        assertNotEquals(hosts.machine(profile), hosts.machine("plex"))
    }

    @Test fun `DLNA discovery edits refresh cached endpoints and machine groups immediately`() {
        val server = DlnaServer(
            id = "dlna", udn = "uuid:dlna", name = "Media server",
            descriptionUrl = "http://127.0.0.1:8200/root.xml",
            contentDirectoryUrl = "http://127.0.0.1:8200/ContentDirectory",
        )
        val before = hosts.endpoints()
        dlna.save(server)
        assertNotSame(before, hosts.endpoints())
        assertEquals(hosts.machine("plex"), hosts.machine("dlna:dlna"))
        dlna.refreshSaved(listOf(server.copy(
            descriptionUrl = "http://192.0.2.50:8201/root.xml",
            contentDirectoryUrl = "http://192.0.2.50:8201/ContentDirectory",
        )))
        assertEquals("host:192.0.2.50", hosts.machine("dlna:dlna"))
        assertEquals(8201, hosts.endpoints().first { it.system == "dlna:dlna" }.port)
        dlna.remove("dlna")
        assertNull(hosts.machine("dlna:dlna"))
    }

    @Test fun `RomM authentication and address edits invalidate its cached endpoint`() {
        preferences.rommEnabled = true
        preferences.rommServerUrl = "https://127.0.0.1:8443"
        assertNull(hosts.machine("romm"))
        preferences.rommAuthMode = "token"
        assertEquals(hosts.machine("plex"), hosts.machine("romm"))
        preferences.rommServerUrl = "https://192.0.2.60:8444"
        assertEquals("host:192.0.2.60", hosts.machine("romm"))
        assertEquals(8444, hosts.endpoints().first { it.system == "romm" }.port)
        preferences.rommAuthMode = ""
        assertNull(hosts.machine("romm"))
    }

    @Test fun `malformed disabled provider metadata retains per target wake fallback`() {
        preferences.macAddress = "AA:BB:CC:DD:EE:10"
        storedPreferences.edit().putInt("emby_host", 42).putString("jellyfin_port", "broken").commit()
        assertEquals("mac:aabbccddee10", machines.keyFor("plex", preferences.host, preferences.macAddress))
        assertEquals("host:127.0.0.1", machines.keyFor("tvheadend", preferences.tvheadendHost, null))
        assertEquals("mac:aabbccddee10", hosts.machine("plex"))
        assertEquals("host:127.0.0.1", hosts.machine("tvheadend"))
    }

    @Test fun `verified external recovery releases only its machine pause for later outages`() = runBlocking {
        val closed = ServerSocket(0).use { it.localPort }
        preferences.port = closed
        preferences.tvheadendPort = closed
        val machine = hosts.machine("plex")!!
        pauseResumeWake(machine)
        assertFalse(hosts.verifySystem("plex"))
        assertTrue(hosts.resumeWakePauses.isPaused(machine))

        hosts.online(HostCheck("host:unrelated.local", 0L))
        assertTrue(hosts.resumeWakePauses.isPaused(machine))
        // A manual wake or successful NAS request reports through this shared evidence path.
        hosts.online(hosts.begin("plex"))
        assertFalse(hosts.resumeWakePauses.isPaused(machine))

        assertFalse(hosts.verifySystem("plex"))
        assertEquals(HostHealth.OFFLINE, hosts.health("plex"))
        assertFalse(hosts.resumeWakePauses.isPaused(machine))
    }

    @Test fun `successful passive host probe releases the cancelled outage before a later sleep`() = runBlocking {
        val live = ServerSocket(0)
        preferences.port = live.localPort
        preferences.tvheadendPort = live.localPort
        val machine = hosts.machine("plex")!!
        pauseResumeWake(machine)
        try {
            assertTrue(hosts.verifySystem("plex"))
            assertEquals(HostHealth.ONLINE, hosts.health("plex"))
            assertFalse(hosts.resumeWakePauses.isPaused(machine))
        } finally {
            live.close()
        }

        assertFalse(hosts.verifySystem("plex"))
        assertEquals(HostHealth.OFFLINE, hosts.health("plex"))
        assertFalse(hosts.resumeWakePauses.isPaused(machine))
    }

    private suspend fun pauseResumeWake(machine: String) {
        val endpoint = ResumeWakeEndpoint("127.0.0.1", preferences.port, "", "255.255.255.255", 9, true, false)
        val sweep = hosts.resumeWakePauses.sweep(mapOf(machine to setOf(endpoint)))
        assertFalse(sweep.check(machine) { throw CancellationException("Wake cancelled") })
        assertTrue(hosts.resumeWakePauses.isPaused(machine))
    }
}
