package com.ambient.tv.data.files

import com.ambient.tv.games.library.GameLibraryFolder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameOnlyFilePathsTest {
    @Test fun sharedNasHidesOnlyAssignedRomTreeAndKeepsMoviesVisible() {
        val assigned = listOf(
            GameLibraryFolder(id = "snes", sourceId = "nas", path = "Games/SNES",
                systemId = "snes", alsoShowInFiles = false),
        )
        assertFalse(isGamesOnlyFilePath("nas", "", assigned))
        assertFalse(isGamesOnlyFilePath("nas", "Movies", assigned))
        assertFalse(isGamesOnlyFilePath("nas", "Movies/film.mkv", assigned))
        assertFalse(isGamesOnlyFilePath("nas", "Games", assigned))
        assertFalse(isGamesOnlyFilePath("nas", "Games/SNES-Extras", assigned))
        assertTrue(isGamesOnlyFilePath("nas", "Games/SNES", assigned))
        assertTrue(isGamesOnlyFilePath("nas", "Games/SNES/Metroid.sfc", assigned))
        assertFalse(isGamesOnlyFilePath("different-nas", "Games/SNES/Metroid.sfc", assigned))
    }

    @Test fun entireGamesShareMayBeHiddenFromFilesWithoutAffectingSiblingSource() {
        val root = GameLibraryFolder(id = "library", sourceId = "retro", path = "",
            systemId = "auto", alsoShowInFiles = false)
        assertTrue(isGamesOnlyFilePath("retro", "", listOf(root)))
        assertTrue(isGamesOnlyFilePath("retro", "SNES/title.sfc", listOf(root)))
        assertFalse(isGamesOnlyFilePath("movies", "Movies/movie.mkv", listOf(root)))
    }

    @Test fun optingInToFilesMakesTheRomDirectoryVisibleAgain() {
        val show = GameLibraryFolder(id = "snes", sourceId = "nas", path = "Games/SNES",
            systemId = "snes", alsoShowInFiles = true)
        assertFalse(isGamesOnlyFilePath("nas", "Games/SNES/Metroid.sfc", listOf(show)))
    }

    @Test fun localDocumentIdsCanHideAnAssignedDirectoryEntry() {
        val target = "primary:Media/Games/SNES"
        val local = GameLibraryFolder(id = "local", sourceId = "local-saf",
            path = target, systemId = "snes", alsoShowInFiles = false)
        assertTrue(isGamesOnlyFilePath("local-saf", target, listOf(local)))
        assertTrue(isGamesOnlyFilePath("local-saf", "$target/Metroid.sfc", listOf(local)))
        assertFalse(isGamesOnlyFilePath("local-saf", "primary:Media/Films", listOf(local)))
    }
}
