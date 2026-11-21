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

    @Test fun explicitFilesSubfolderIsReachableInsideGamesOnlyShare() {
        val folders = listOf(
            GameLibraryFolder(id = "games-root", sourceId = "nas", path = "Games",
                alsoShowInFiles = false),
            GameLibraryFolder(id = "shared-snes", sourceId = "nas", path = "Games/SNES",
                systemId = "snes", alsoShowInFiles = true),
        )
        assertTrue(filePathVisibleInBrowser("nas", "", false, folders))
        assertTrue(filePathVisibleInBrowser("nas", "Games", false, folders))
        assertTrue(filePathVisibleInBrowser("nas", "Games/SNES", false, folders))
        assertTrue(filePathVisibleInBrowser("nas", "Games/SNES/Mario.sfc", false, folders))
        assertFalse(filePathVisibleInBrowser("nas", "Games/PSX", false, folders))
        assertFalse(filePathVisibleInBrowser("nas", "Movies/movie.mkv", false, folders))
    }

    @Test fun mostSpecificAssignmentOverridesHiddenParentButNotHiddenChild() {
        val folders = listOf(
            GameLibraryFolder(id = "hidden", sourceId = "nas", path = "Games", alsoShowInFiles = false),
            GameLibraryFolder(id = "visible", sourceId = "nas", path = "Games/SNES",
                systemId = "snes", alsoShowInFiles = true),
            GameLibraryFolder(id = "private", sourceId = "nas", path = "Games/SNES/Private",
                systemId = "snes", alsoShowInFiles = false),
        )
        assertTrue(filePathVisibleInBrowser("nas", "Games", true, folders))
        assertTrue(filePathVisibleInBrowser("nas", "Games/SNES/Metroid.sfc", true, folders))
        assertFalse(filePathVisibleInBrowser("nas", "Games/SNES/Private/secrets.sfc", true, folders))
        assertFalse(filePathVisibleInBrowser("nas", "Games/PSX", true, folders))
        assertFalse(isGamesOnlyFilePath("nas", "Games/SNES/Metroid.sfc", folders))
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
