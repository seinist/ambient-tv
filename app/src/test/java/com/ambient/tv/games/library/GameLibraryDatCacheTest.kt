package com.ambient.tv.games.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLibraryDatCacheTest {
    private fun game(checked: Boolean, system: String = "snes", modified: Long = 123L): LibraryGame =
        LibraryGame(
            fileId = "files:nas:rom", folderId = "snes", sourceId = "nas", path = "SNES/rom.sfc",
            filename = "rom.sfc", systemId = system, title = "ROM",
            internalTitle = null, size = 42, modified = modified,
            favorite = false, lastPlayed = 0, verifiedTitle = null, datChecked = checked,
        )

    @Test fun negativeDatLookupIsCachedWhenFileAndSystemAreUnchanged() {
        assertTrue(reuseDatLookup(game(checked = true), "snes", 42, 123))
        assertFalse(reuseDatLookup(game(checked = false), "snes", 42, 123))
        assertFalse(reuseDatLookup(null, "snes", 42, 123))
    }

    @Test fun anyFileOrSystemChangeInvalidatesCachedChecksum() {
        assertFalse(reuseDatLookup(game(checked = true), "gba", 42, 123))
        assertFalse(reuseDatLookup(game(checked = true), "snes", 43, 123))
        assertFalse(reuseDatLookup(game(checked = true), "snes", 42, 124))
        assertFalse(reuseDatLookup(game(checked = true, modified = 0), "snes", 42, 0))
    }

    @Test fun completedNegativeChecksumAttemptsAreReusedUntilTheCatalogChanges() {
        val missing = game(checked = true).copy(datVersion = 2)
        assertTrue(reuseScanChecksums(missing, 2))
        assertFalse(reuseScanChecksums(missing, 3))
        assertTrue(reuseScanChecksums(missing.copy(archiveCrc = "DEADBEEF"), 3))
        assertFalse(reuseScanChecksums(null, 2))
    }

    @Test fun loadingADatRetriesAPreviouslyBoundedSevenZipProbeWithoutRepeatedHashing() {
        val bounded = game(checked = false).copy(filename = "game.7z", archiveCrc = null, datVersion = 0)
        assertFalse(needsArchiveDatChecksum("game.7z", bounded, 0))
        assertTrue(needsArchiveDatChecksum("game.7z", bounded, 1))
        assertFalse(needsArchiveDatChecksum("game.7z", bounded.copy(datVersion = 1), 1))
        assertFalse(needsArchiveDatChecksum("game.7z", bounded.copy(archiveCrc = "DEADBEEF"), 1))
        assertFalse(needsArchiveDatChecksum("game.zip", bounded, 1))
        assertFalse(needsArchiveDatChecksum("game.7z", null, 1))
    }
}
