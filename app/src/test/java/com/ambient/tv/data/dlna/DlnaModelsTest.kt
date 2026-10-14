package com.ambient.tv.data.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaModelsTest {
    @Test fun providerQualifiedKeyKeepsUnicodeObjectIdRoundTrip() {
        val key = DlnaKeys.key("server-1", "Videos/Ärger & Spaß")

        assertTrue(DlnaKeys.owns(key))
        assertEquals("server-1" to "Videos/Ärger & Spaß", DlnaKeys.parse(key))
    }

    @Test fun serverAcceptsAContentDirectoryVersionAdvertisedByTheDevice() {
        val server = DlnaServer(
            id = "server-1",
            udn = "uuid:server-1",
            name = "Media Server",
            descriptionUrl = "http://192.168.1.2:8200/rootDesc.xml",
            contentDirectoryUrl = "http://192.168.1.2:8200/ctl/ContentDir",
            contentDirectoryType = "urn:schemas-upnp-org:service:ContentDirectory:3",
        ).validated()

        assertEquals("urn:schemas-upnp-org:service:ContentDirectory:3", server.contentDirectoryType)
    }

    @Test fun savedServersRoundTripThroughSettingsBackup() {
        val sourcePrefs = com.ambient.tv.data.local.memoryPreferences()
        val sourceApp = com.ambient.tv.data.local.AppPreferences(sourcePrefs)
        val sourceStore = DlnaStore(sourcePrefs)
        sourceStore.save(server())
        val backup = com.ambient.tv.data.local.SettingsBackupManager(
            sourceApp,
            com.ambient.tv.data.local.TokenStore(com.ambient.tv.data.local.memoryPreferences(), sourceApp),
            sourceStore,
        ).exportJson()

        val targetPrefs = com.ambient.tv.data.local.memoryPreferences()
        val targetApp = com.ambient.tv.data.local.AppPreferences(targetPrefs)
        val targetStore = DlnaStore(targetPrefs)
        com.ambient.tv.data.local.SettingsBackupManager(
            targetApp,
            com.ambient.tv.data.local.TokenStore(com.ambient.tv.data.local.memoryPreferences(), targetApp),
            targetStore,
        ).importJson(backup)

        assertEquals(listOf(server()), targetStore.servers())
    }

    @Test fun playbackProgressDoesNotInvalidateTheSidebarServerList() {
        val store = DlnaStore(com.ambient.tv.data.local.memoryPreferences())
        store.save(server())
        val serverRevision = store.serverRevision.value

        store.recordProgress(DlnaKeys.key("server-1", "movie-1"), 42_000)

        assertEquals(serverRevision, store.serverRevision.value)
        assertEquals(42_000L, store.progressFor(DlnaKeys.key("server-1", "movie-1"))?.positionMs)
    }

    @Test fun serviceTypeCannotInjectSoapMarkup() {
        assertThrows(IllegalArgumentException::class.java) {
            server().copy(contentDirectoryType = "urn:schemas-upnp-org:service:ContentDirectory:1\" bad=\"yes").validated()
        }
    }

    @Test fun rediscoveryRefreshesTheAddressWithoutChangingIdentityOrOrder() {
        val store = DlnaStore(com.ambient.tv.data.local.memoryPreferences())
        val first = server()
        val second = server().copy(id = "server-2", udn = "uuid:server-2", name = "Second")
        store.replace(listOf(first, second))

        store.refreshSaved(listOf(first.copy(
            id = "new-generated-id",
            contentDirectoryUrl = "http://192.168.1.99:8200/ctl/ContentDir",
        )))

        assertEquals(listOf("server-1", "server-2"), store.servers().map { it.id })
        assertEquals("http://192.168.1.99:8200/ctl/ContentDir", store.servers().first().contentDirectoryUrl)
    }

    private fun server() = DlnaServer(
        id = "server-1",
        udn = "uuid:server-1",
        name = "Media Server",
        descriptionUrl = "http://192.168.1.2:8200/rootDesc.xml",
        contentDirectoryUrl = "http://192.168.1.2:8200/ctl/ContentDir",
        contentDirectoryType = "urn:schemas-upnp-org:service:ContentDirectory:3",
    )
}
