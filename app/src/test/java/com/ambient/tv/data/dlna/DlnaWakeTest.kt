package com.ambient.tv.data.dlna

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.SettingsBackupManager
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.data.local.memoryPreferences
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DlnaWakeTest {
    private fun server(mac: String = "", auto: Boolean = false) = DlnaServer(
        id = "server-1", udn = "uuid:server-1", name = "NAS",
        descriptionUrl = "http://192.168.1.2:8200/rootDesc.xml",
        contentDirectoryUrl = "http://192.168.1.2:8200/ctl/ContentDir",
        wolMac = mac, wolAuto = auto,
    )

    @Test fun oldSavedServersWithoutWakeFieldsStillDecode() {
        val json = """[{"id":"server-1","udn":"uuid:server-1","name":"NAS","descriptionUrl":"http://192.168.1.2:8200/rootDesc.xml","contentDirectoryUrl":"http://192.168.1.2:8200/ctl/ContentDir"}]"""
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<List<DlnaServer>>(json).single().validated()
        assertEquals("", decoded.wolMac)
        assertFalse(decoded.wolAuto)
    }

    @Test fun validationRejectsInvalidMacAndAutoWakeWithoutMac() {
        assertThrows(IllegalArgumentException::class.java) { server(mac = "not-a-mac").validated() }
        assertThrows(IllegalArgumentException::class.java) { server(auto = true).validated() }
        assertEquals("AA:BB:CC:DD:EE:01", server(mac = " AA:BB:CC:DD:EE:01 ", auto = true).validated().wolMac)
    }

    @Test fun storeUpdatesWakeSettingsAndRediscoveryKeepsThem() {
        val store = DlnaStore(memoryPreferences())
        store.save(server())
        assertTrue(store.updateWake("server-1", "AA:BB:CC:DD:EE:01", true))
        assertFalse(store.updateWake("server-1", "bogus", true))

        store.refreshSaved(listOf(server().copy(id = "other", name = "NAS renamed", contentDirectoryUrl = "http://192.168.1.3:8200/ctl/ContentDir")))

        val saved = store.server("server-1")!!
        assertEquals("NAS renamed", saved.name)
        assertEquals("AA:BB:CC:DD:EE:01", saved.wolMac)
        assertTrue(saved.wolAuto)

        assertTrue(store.updateWake("server-1", "", true))
        assertFalse(store.server("server-1")!!.wolAuto)
    }

    @Test fun wakeSettingsRoundTripThroughBackup() {
        val sourcePrefs = memoryPreferences()
        val sourceApp = AppPreferences(sourcePrefs)
        val sourceStore = DlnaStore(sourcePrefs)
        sourceStore.save(server(mac = "AA:BB:CC:DD:EE:01", auto = true))
        val backup = SettingsBackupManager(sourceApp, TokenStore(memoryPreferences(), sourceApp), sourceStore).exportJson()

        val targetPrefs = memoryPreferences()
        val targetApp = AppPreferences(targetPrefs)
        val targetStore = DlnaStore(targetPrefs)
        SettingsBackupManager(targetApp, TokenStore(memoryPreferences(), targetApp), targetStore).importJson(backup)

        assertEquals("AA:BB:CC:DD:EE:01", targetStore.server("server-1")!!.wolMac)
        assertTrue(targetStore.server("server-1")!!.wolAuto)
    }

    @Test fun wakeEndpointIsTheContentDirectoryHostAndPort() {
        assertEquals("192.168.1.2" to 8200, dlnaWakeEndpoint(server()))
        assertEquals("nas.local" to 80, dlnaWakeEndpoint(server().copy(contentDirectoryUrl = "http://nas.local/cd")))
    }
}
