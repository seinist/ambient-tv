package com.ambient.tv.util

import com.ambient.tv.connectivity.activeConnectivityFileSources
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.games.library.GameLibraryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkAccessTest {
    @Test fun permissionRequiresBothRuntimeAndTarget37() {
        assertFalse(requiresLocalNetworkPermission(36, 37))
        assertFalse(requiresLocalNetworkPermission(37, 36))
        assertTrue(requiresLocalNetworkPermission(37, 37))
        assertTrue(requiresLocalNetworkPermission(38, 38))
    }
    @Test fun configuredServiceIntroducesMissingPermission() {
        assertTrue(shouldIntroduceLocalNetworkAccess(true, false, true, false))
    }
    @Test fun freshSetupAndCloudOnlyDoNotShowIntroduction() {
        assertFalse(shouldIntroduceLocalNetworkAccess(true, false, false, false))
    }
    @Test fun oldOsAndGrantedPermissionDoNotShowIntroduction() {
        assertFalse(shouldIntroduceLocalNetworkAccess(false, false, true, false))
        assertFalse(shouldIntroduceLocalNetworkAccess(true, true, true, false))
    }
    @Test fun decliningDoesNotCreateAnIntroductionLoop() {
        assertFalse(shouldIntroduceLocalNetworkAccess(true, false, true, true))
    }

    @Test fun dlnaOnlySetupCountsAsAConfiguredLanService() {
        val preferences = com.ambient.tv.data.local.AppPreferences(com.ambient.tv.data.local.memoryPreferences()).apply {
            plexEnabled = false
            jellyfinEnabled = false
            embyEnabled = false
            tvheadendEnabled = false
            spotifyEnabled = false
            filesEnabled = false
        }

        assertTrue(LocalNetworkAccess.hasConfiguredService(preferences, dlnaConfigured = true))
    }
    @Test fun gameOnlyNasCountsWhenFilesIsDisabled() {
        val preferences = com.ambient.tv.data.local.AppPreferences(com.ambient.tv.data.local.memoryPreferences()).apply {
            plexEnabled = false
            jellyfinEnabled = false
            embyEnabled = false
            tvheadendEnabled = false
            spotifyEnabled = false
            filesEnabled = false
        }
        val roms = FileSource(id = "roms", name = "ROMs", kind = "smb",
            host = "192.0.2.5", share = "ROMs", showInFiles = false)
        val media = roms.copy(id = "media", name = "Movies", share = "Movies", showInFiles = true)
        val orphan = roms.copy(id = "orphan")
        preferences.fileSources = listOf(roms, media, orphan)
        assertFalse(LocalNetworkAccess.hasConfiguredService(preferences))
        preferences.gameLibraryFolders = listOf(
            GameLibraryFolder(sourceId = roms.id, path = "NES"),
            GameLibraryFolder(sourceId = roms.id, path = "SNES"),
            GameLibraryFolder(sourceId = "missing", path = "PSX"),
        )
        assertEquals(listOf(roms.id), preferences.activeConnectivityFileSources().map { it.id })
        assertTrue(LocalNetworkAccess.hasConfiguredService(preferences))
        preferences.filesEnabled = true
        assertEquals(listOf(roms.id, media.id), preferences.activeConnectivityFileSources().map { it.id })
        preferences.filesEnabled = false
        preferences.gameLibraryFolders = emptyList()
        assertFalse(LocalNetworkAccess.hasConfiguredService(preferences))
    }

    @Test fun localGameFoldersDoNotRequireNetworkPermission() {
        val preferences = com.ambient.tv.data.local.AppPreferences(com.ambient.tv.data.local.memoryPreferences()).apply {
            plexEnabled = false
            jellyfinEnabled = false
            embyEnabled = false
            tvheadendEnabled = false
            spotifyEnabled = false
            filesEnabled = false
        }
        val source = FileSource(id = "local", name = "Local", kind = "local", showInFiles = false)
        preferences.fileSources = listOf(source)
        preferences.gameLibraryFolders = listOf(GameLibraryFolder(sourceId = source.id, path = "ROMs"))
        assertFalse(LocalNetworkAccess.hasConfiguredService(preferences))
    }
}
