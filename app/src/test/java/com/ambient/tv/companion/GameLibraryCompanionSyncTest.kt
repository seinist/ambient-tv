package com.ambient.tv.companion

import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.local.PortableSettingsExtras
import com.ambient.tv.data.local.SettingsBackup
import com.ambient.tv.data.local.SettingsBackupExtras
import com.ambient.tv.games.library.GameLibraryFolder
import com.ambient.tv.games.library.GamePortableState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLibraryCompanionSyncTest {
    private val json = CompanionBackupPolicy.json
    private val videoSource = FileSource(id = "tv-media", name = "Video NAS",
        kind = "smb", host = "nas.home", share = "Media", showInFiles = true)
    private val gameSource = videoSource.copy(id = "handheld-retro",
        name = "Retro NAS", showInFiles = false)

    private fun extras(folders: List<GameLibraryFolder> = emptyList(),
        games: List<GamePortableState>? = null) = SettingsBackupExtras(
        portable = PortableSettingsExtras(gameLibraryFolders = folders, gameLibraryOverrides = games))

    private fun transfer(source: SettingsBackup, target: SettingsBackup): SettingsBackup {
        val prepared = CompanionBackupPolicy.prepare(source, credentials = false, mode = "settings")
        val incoming = json.decodeFromString<SettingsBackup>(prepared)
        return json.decodeFromString<SettingsBackup>(
            CompanionBackupPolicy.merge(incoming, target, "settings",
                overwrite = true, deviceSettings = true))
    }

    @Test fun gameOnlySourceReusesReceiverVideoShareWithoutHidingIt() {
        val folder = GameLibraryFolder(id = "folder-snes", sourceId = gameSource.id,
            path = "Games/SNES", systemId = "snes")
        val source = SettingsBackup(schemaVersion = 83, exportedAtEpochMs = 2,
            fileSources = listOf(gameSource), extras = extras(listOf(folder)))
        val target = SettingsBackup(schemaVersion = 83, exportedAtEpochMs = 1,
            fileSources = listOf(videoSource), extras = extras())
        val merged = transfer(source, target)
        assertEquals(1, merged.fileSources.size)
        assertEquals(videoSource.id, merged.fileSources.single().id)
        assertTrue(merged.fileSources.single().showInFiles)
        val mapped = requireNotNull(merged.extras?.portable?.gameLibraryFolders).single()
        assertEquals(videoSource.id, mapped.sourceId)
        assertFalse(mapped.alsoShowInFiles)
    }

    @Test fun newGameOnlyNetworkSourceStaysHiddenFromFilesAfterSync() {
        val source = SettingsBackup(schemaVersion = 83, exportedAtEpochMs = 2,
            fileSources = listOf(gameSource), extras = extras(listOf(
                GameLibraryFolder(id = "folder-snes", sourceId = gameSource.id,
                    path = "SNES", systemId = "snes"))))
        val merged = transfer(source, SettingsBackup(schemaVersion = 83, exportedAtEpochMs = 1))
        assertEquals(1, merged.fileSources.size)
        assertFalse(merged.fileSources.single().showInFiles)
        assertEquals(gameSource.id, merged.extras!!.portable!!.gameLibraryFolders!!.single().sourceId)
    }

    @Test fun companionMovesPerGameUserChoicesToTheMatchedNas() {
        val source = SettingsBackup(schemaVersion = 83, exportedAtEpochMs = 2,
            fileSources = listOf(gameSource), extras = extras(
                folders = listOf(GameLibraryFolder(id = "games", sourceId = gameSource.id,
                    path = "Games/SNES", systemId = "snes")),
                games = listOf(GamePortableState(
                    sourceId = gameSource.id, path = "Games/SNES/Chrono.sfc",
                    title = "Chrono Trigger", favorite = true, lastPlayed = 1234L))))
        val target = SettingsBackup(schemaVersion = 83, exportedAtEpochMs = 1,
            fileSources = listOf(videoSource))
        val result = transfer(source, target)
        val titles = requireNotNull(result.extras?.portable?.gameLibraryOverrides)
        assertEquals(1, titles.size)
        assertEquals(videoSource.id, titles.single().sourceId)
        assertTrue(titles.single().favorite)
        assertEquals("Chrono Trigger", titles.single().title)
    }

    @Test fun gameOnlySafPathsNeverTravelToAnotherDevice() {
        val local = FileSource(id = "phone-local", name = "Phone games",
            kind = "local", treeUri = "content://provider/tree/primary%3AGames",
            showInFiles = false)
        val source = SettingsBackup(schemaVersion = 83, exportedAtEpochMs = 2,
            fileSources = listOf(local), extras = extras(
                folders = listOf(GameLibraryFolder(id = "local-games",
                    sourceId = local.id, path = "primary:Games", systemId = "snes")),
                games = listOf(GamePortableState(sourceId = local.id,
                    path = "primary:Games/Metroid.sfc", favorite = true))))
        val payload = CompanionBackupPolicy.prepare(source, false, "settings")
        val prepared = json.decodeFromString<SettingsBackup>(payload)
        assertTrue(prepared.fileSources.isEmpty())
        assertTrue(prepared.extras?.portable?.gameLibraryFolders.orEmpty().isEmpty())
        assertTrue(prepared.extras?.portable?.gameLibraryOverrides.orEmpty().isEmpty())
    }
}
