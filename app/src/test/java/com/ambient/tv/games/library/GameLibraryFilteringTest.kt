package com.ambient.tv.games.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLibraryFilteringTest {
    private val snes = GameLibraryFolder(id = "snes", sourceId = "nas", path = "Games/SNES", systemId = "snes")

    @Test fun previouslyIndexedForeignFilesDisappearWithoutNetworkReads() {
        assertFalse(cachedGameMatchesFolder(snes, "nas", "Mario.gba", "gba"))
        assertFalse(cachedGameMatchesFolder(snes, "nas", "Mario.gba", "snes"))
        assertFalse(cachedGameMatchesFolder(snes, "nas", "Disc.iso", "snes"))
        assertFalse(cachedGameMatchesFolder(snes, "nas", "Disk.d64", "snes"))
        assertFalse(cachedGameMatchesFolder(snes, "nas", "Other.zip", "psx"))
        assertTrue(cachedGameMatchesFolder(snes, "nas", "Metroid.sfc", "snes"))
    }

    @Test fun validOfflineArchiveCacheIsPreservedUntilExplicitRefresh() {
        assertTrue(cachedGameMatchesFolder(snes, "nas", "Metroid.zip", "snes"))
        assertTrue(cachedGameMatchesFolder(snes, "nas", "Metroid.sfc.gz", "snes"))
        assertFalse(cachedGameMatchesFolder(snes, "nas", "snes9x_libretro_android.so.zip", "snes"))
    }

    @Test fun restoredAssignmentsCannotReuseRowsFromAnotherSourceOrSystem() {
        assertFalse(cachedGameMatchesFolder(snes, "old-nas", "Metroid.sfc", "snes"))
        assertFalse(cachedGameMatchesFolder(snes.copy(systemId = "psx"), "nas", "Metroid.sfc", "snes"))
        assertFalse(cachedGameMatchesFolder(snes.copy(systemId = "auto"), "nas", "Disc.iso", "snes"))
        assertTrue(cachedGameMatchesFolder(snes.copy(systemId = "auto"), "nas", "Disc.cue", "psx"))
    }
}
