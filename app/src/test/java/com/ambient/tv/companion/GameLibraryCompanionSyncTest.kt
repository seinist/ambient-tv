package com.ambient.tv.companion

import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.local.SettingsBackup
import com.ambient.tv.data.local.SettingsBackupExtras
import com.ambient.tv.data.local.PortableSettingsExtras
import com.ambient.tv.games.library.GamePortableState
import com.ambient.tv.games.library.GameLibraryFolder
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLibraryCompanionSyncTest {
    private val json = CompanionBackupPolicy.json
    private val videoSource = FileSource(id = "tv-media", name = "Video NAS",
        kind = "smb", host = "nas.home", share = "Media", showInFiles = true)
    private val gameSource = videoSource.copy(id = "handheld-retro", name = "Retro NAS", showInFiles = false)

    @Test fun gameOnlySourceReusesReceiverVideoShareWithoutHidingIt() {
        val fromHandheld = SettingsBackup(schemaVersion = 83, exportedAtEpochMs = 2,
            fileSources = listOf(gameSource),
            gameLibraryFolders = listOf(GameLibraryFolder(id = "folder-snes",
                sourceId = gameSource.id, path = "Games/SNES", systemId = "snes")))
        val receiver = SettingsBackup(schemaVersion = 83, exportedAtEpochMs = 1,
            fileSources = listOf(videoSource), gameLibraryFolders = emptyList())
        val prepared = CompanionBackupPolicy.prepare(fromHandheld, false, "settings")
        val incoming = json.decodeFromString<SettingsBackup>(prepared)
        val merged = json.decodeFromString<SettingsBackup>(
            CompanionBackupPolicy.merge(incoming, receiver, "settings", overwrite = true, deviceSettings = true))
        assertEquals(1, merged.fileSources.size)
        assertEquals(videoSource.id, merged.fileSources.single().id)
        assertTrue(merged.fileSources.single().showInFiles)
        assertEquals(videoSource.id, merged.gameLibraryFolders!!.single().sourceId)
        assertFalse(merged.gameLibraryFolders!!.single().alsoShowInFiles)
    }

    @Test fun newGameOnlyNetworkSourceStaysHiddenFromFilesAfterSync() {
        val sender = SettingsBackup(schemaVersion = 83, exportedAtEpochMs = 2,
            fileSources = listOf(gameSource),
            gameLibraryFolders = listOf(GameLibraryFolder(id = "folder-snes",
                sourceId = gameSource.id, path = "SNES", systemId = "snes")))
        val target = SettingsBackup(schemaVersion = 83, exportedAtEpochMs = 1)
        val prepared = CompanionBackupPolicy.prepare(sender, false, "settings")
        val merged = json.decodeFromString<SettingsBackup>(
            CompanionBackupPolicy.merge(json.decodeFromString(prepared),
                target, "settings", overwrite = true, deviceSettings = true))
        assertEquals(1, merged.fileSources.size)
        assertFalse(merged.fileSources.single().showInFiles)
        assertEquals(gameSource.id, merged.gameLibraryFolders!!.single().sourceId)
    }

    @Test fun companionMovesPerGameUserChoicesToTheMatchedNas() {
        val sender = SettingsBackup(schemaVersion = 83, exportedAtEpochMs = 2,
            fileSources = listOf(gameSource),
            gameLibraryFolders = listOf(GameLibraryFolder(id = "games", sourceId = gameSource.id,
                path = "Games/SNES", systemId = "snes")),
            extras = SettingsBackupExtras(portable = PortableSettingsExtras(
                gameLibraryOverrides = listOf(GamePortableState(
                    sourceId = gameSource.id, path = "Games/SNES/Chrono.sfc",
                    title = "Chrono Trigger", favorite = true, lastPlayed = 1234L)))))
        val target = SettingsBackup(schemaVersion = 83, exportedAtEpochMs = 1,
            fileSources = listOf(videoSource))
        val payload = CompanionBackupPolicy.prepare(sender, false, "settings")
        val result = json.decodeFromString<SettingsBackup>(
            CompanionBackupPolicy.merge(json.decodeFromString(payload), target,
                "settings", overwrite = true, deviceSettings = false))
        val titles = requireNotNull(result.extras?.portable?.gameLibraryOverrides)
        assertEquals(1, titles.size)
        assertEquals(videoSource.id, titles.single().sourceId)
        assertTrue(titles.single().favorite)
        assertEquals("Chrono Trigger", titles.single().title)
    }

        @Test fun gameOnlySafPathsNeverTravelToAnotherDevice() {
        val local = FileSource(id = "phone-local", name = "Phone games", kind = "local",
            treeUri = "content://provider/tree/primary%3AGames", showInFiles = false)
        val sender = SettingsBackup(schemaVersion = 83, exportedAtEpochMs = 2,
            fileSources = listOf(local),
            gameLibraryFolders = listOf(GameLibraryFolder(id = "local-games",
                sourceId = local.id, path = "primary:Games", systemId = "snes")))
        val jsonOut = CompanionBackupPolicy.prepare(sender, false, "settings")
        val prepared = json.decodeFromString<SettingsBackup>(jsonOut)
        assertTrue(prepared.fileSources.isEmpty())
        assertTrue(prepared.gameLibraryFolders.orEmpty().isEmpty())
    }
}
