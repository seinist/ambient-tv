package com.ambient.tv.companion

import com.ambient.tv.data.local.EmbyServerBackup
import com.ambient.tv.data.local.JellyfinServerBackup
import com.ambient.tv.data.local.ServerBackup
import com.ambient.tv.data.local.SettingsBackup
import com.ambient.tv.data.local.TitleVideoSettings
import com.ambient.tv.domain.model.EmbyServerProfile
import com.ambient.tv.domain.model.JellyfinServerProfile
import com.ambient.tv.domain.model.ServerProfile
import org.junit.Assert.*
import org.junit.Test

class CompanionVideoProfilesTest {
    private val remembered = TitleVideoSettings(rendererKey = "mpv", savedAt = 123L)

    @Test fun settingsTransferUsesExistingReceiverAccountIdsForRememberedProfiles() {
        val source = SettingsBackup(schemaVersion = 80, exportedAtEpochMs = 1,
            servers = listOf(ServerBackup(ServerProfile("p:phone", "Plex", "plex.test"))),
            jellyfinServers = listOf(JellyfinServerBackup(JellyfinServerProfile("j:phone", "Jellyfin", "jellyfin.test"))),
            embyServers = listOf(EmbyServerBackup(EmbyServerProfile("e:phone", "Emby", "emby.test"))),
            titleVideoSettings = mapOf("plex:7:p:phone:42" to remembered, "jellyfin:jf:item" to remembered),
            scopedVideoSettings = mapOf(
                "season:plex:7:p:phone:5" to remembered,
                "series:jellyfin:7:j:phone:jf:series" to remembered,
                "collection:emby:7:e:phone:emby:collection" to remembered))
        val target = SettingsBackup(schemaVersion = 80, exportedAtEpochMs = 2,
            servers = listOf(ServerBackup(ServerProfile("p:tv", "Plex", "plex.test"))),
            jellyfinServers = listOf(JellyfinServerBackup(JellyfinServerProfile("j:tv", "Jellyfin", "jellyfin.test"))),
            embyServers = listOf(EmbyServerBackup(EmbyServerProfile("e:tv", "Emby", "emby.test"))))
        val wire = CompanionBackupPolicy.prepare(source, credentials = false, mode = "settings")
        val received = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(wire)
        val restored = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(
            CompanionBackupPolicy.merge(received, target, "settings", overwrite = true, deviceSettings = true))
        assertEquals(setOf("plex:4:p:tv:42", "jellyfin:jf:item"), restored.titleVideoSettings.keys)
        assertEquals(setOf("season:plex:4:p:tv:5", "series:jellyfin:4:j:tv:jf:series", "collection:emby:4:e:tv:emby:collection"),
            restored.scopedVideoSettings.keys)
        assertEquals(remembered, restored.titleVideoSettings["plex:4:p:tv:42"])
    }

    @Test fun collidingAccountIdUsesTheNewIdChosenByTheAccountMerge() {
        val source = SettingsBackup(schemaVersion = 80, exportedAtEpochMs = 1,
            servers = listOf(ServerBackup(ServerProfile("same", "New Plex", "new.test"))),
            titleVideoSettings = mapOf("plex:4:same:42" to remembered))
        val target = SettingsBackup(schemaVersion = 80, exportedAtEpochMs = 2,
            servers = listOf(ServerBackup(ServerProfile("same", "Existing Plex", "old.test"))))
        val restored = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(
            CompanionBackupPolicy.merge(source, target, "settings", overwrite = true, deviceSettings = true))
        val importedId = restored.servers.single { it.profile.host == "new.test" }.profile.id
        assertNotEquals("same", importedId)
        assertEquals(mapOf("plex:${importedId.length}:$importedId:42" to remembered), restored.titleVideoSettings)
        val deviceOff = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(
            CompanionBackupPolicy.merge(source, target, "settings", overwrite = true, deviceSettings = false))
        assertEquals(target.titleVideoSettings, deviceOff.titleVideoSettings)
    }

    @Test fun unmappedLegacyAndMalformedKeysRemainUnchanged() {
        val keys = listOf("plex:42", "plex:2147483647:x:42", "plex:3:abcd:42", "plex:-1:x:42",
            "plex:4:else:42", "live-channel:3:tvh:42", "youtube:video")
        val source = SettingsBackup(exportedAtEpochMs = 1, titleVideoSettings = keys.associateWith { remembered })
        assertEquals(source.titleVideoSettings, remapCompanionVideoProfiles(source, mapOf("servers" to mapOf("abc" to "tv"))).titles)
    }

    @Test fun aliasesOfOneAccountKeepTheNewestRememberedChoice() {
        val source = SettingsBackup(exportedAtEpochMs = 1, titleVideoSettings = linkedMapOf(
            "plex:3:one:42" to remembered,
            "plex:3:two:42" to remembered.copy(rendererKey = "vlc", savedAt = 100L)))
        assertEquals(mapOf("plex:2:tv:42" to remembered), remapCompanionVideoProfiles(source,
            mapOf("servers" to mapOf("one" to "tv", "two" to "tv"))).titles)
    }

    @Test fun partialAccountTransferPreservesOtherReceiverProfilesAndSkipsUnselectedSourceProfiles() {
        val source = SettingsBackup(schemaVersion = 80, exportedAtEpochMs = 1,
            servers = listOf(ServerBackup(ServerProfile("phone", "Shared Plex", "shared.test")),
                ServerBackup(ServerProfile("other", "Unselected Plex", "other.test"))),
            titleVideoSettings = mapOf("plex:5:phone:42" to remembered, "plex:5:other:42" to remembered))
        val localChoice = remembered.copy(rendererKey = "vlc")
        val target = SettingsBackup(schemaVersion = 80, exportedAtEpochMs = 2,
            servers = listOf(ServerBackup(ServerProfile("tv", "Shared Plex", "shared.test")),
                ServerBackup(ServerProfile("local", "Local Plex", "local.test"))),
            titleVideoSettings = mapOf("plex:2:tv:old" to localChoice, "plex:5:local:42" to localChoice))
        val selected = selectSyncAccounts(source, setOf("plex:phone"))
        val restored = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(
            CompanionBackupPolicy.merge(selected, target, "settings", overwrite = true, deviceSettings = true))
        assertEquals(mapOf("plex:2:tv:42" to remembered, "plex:5:local:42" to localChoice), restored.titleVideoSettings)
    }
}
