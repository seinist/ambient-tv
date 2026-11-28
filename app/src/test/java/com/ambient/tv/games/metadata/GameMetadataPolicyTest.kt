package com.ambient.tv.games.metadata

import com.ambient.tv.games.library.LibraryGame
import org.junit.Assert.*
import org.junit.Test

class GameMetadataPolicyTest {
    private fun game() = LibraryGame(
        fileId = "rom", folderId = "folder", sourceId = "nas", path = "Games/Original.sfc",
        filename = "Original (Europe).sfc", systemId = "snes", title = "My nickname",
        internalTitle = "SHORT ROM LABEL", size = 4096, modified = 12, favorite = false, lastPlayed = 0,
        customTitle = "My nickname", verifiedTitle = "Original (Europe)",
    )

    @Test fun catalogFallbackUsesExactReleaseNamesAndDoesNotGuessDifferentGames() {
        val titles = listOf("Ridge Racer (Europe)", "Ridge Racer (USA)", "Ridge Racer 2 (Europe)")
        assertEquals(titles.take(2), matchingGameCatalogTitles("Ridge Racer", titles))
        assertEquals(listOf(titles.first()), matchingGameCatalogTitles("Ridge Racer (Europe)", titles))
        assertTrue(matchingGameCatalogTitles("Ridge", titles).isEmpty())
        assertTrue(matchingGameCatalogTitles("Ridge Racer (Japan)", titles).isEmpty())
        assertEquals("Mario Kart DS (Europe)", gameCatalogFilename("nds", "0123 - Mario Kart DS (Europe).nds"))
        assertEquals("1942 - Joint Strike", gameCatalogFilename("psp", "1942 - Joint Strike.iso"))
    }

    @Test fun releaseAnnotationsBeforeAndAfterTitleAreSearchOnly() {
        for (system in listOf("snes", "nds", "psp", "n64", "psx", "saturn", "gba", "c64")) {
            val name = gameCatalogFilename(system, "[Release] (Europe) Ridge Racer [!] (Rev 1).zip")
            assertEquals("Ridge Racer", gameMetadataSearchName(name))
            assertEquals(listOf("Ridge Racer (Europe)"), matchingGameCatalogTitles(name,
                listOf("Ridge Racer (Europe)", "Ridge Racer (USA)", "Ridge Racer 2 (Europe)")))
        }
        assertEquals("Game (Part II) - Adventure", gameMetadataSearchName("Game (Part II) - Adventure (USA)"))
        assertEquals("[!]", gameMetadataSearchName("[!]"))
        assertEquals(listOf("Game (Part II)"), matchingGameCatalogTitles("Game (Part II)",
            listOf("Game (Part I)", "Game (Part II)")))
    }

    @Test fun manualRenameNeverBecomesAutomaticSearchOrCoverIdentity() {
        val query = gameMetadataQuery(game(), null, "de", "eu", "0123ABCD")
        assertEquals("Original (Europe)", query.title)
        assertEquals("Original (Europe)", query.canonicalTitle)
        assertEquals("0123ABCD", query.crc32)
        assertFalse(query.title.contains("nickname"))
    }

    @Test fun explicitRematchSearchDoesNotReuseOldHashOrPinnedGameId() {
        val old = GameMetadata(provider = "screenscraper", providerGameId = "123",
            canonicalTitle = "Original", matchLocked = true)
        val query = gameMetadataQuery(game(), old, "de", "eu", "0123ABCD", "Different game")
        assertEquals("Different game", query.title)
        assertNull(query.crc32)
        assertNull(query.canonicalTitle)
        assertNull(query.providerGameId)
    }

    @Test fun refreshKeepsProviderIdentityWhileUserMayRename() {
        val old = GameMetadata(title = "Edited", providerGameId = "42", canonicalTitle = "Database name")
        val query = gameMetadataQuery(game(), old, "en", "us", null)
        assertEquals("42", query.providerGameId)
        assertEquals("Database name", query.title)
    }

    @Test fun switchingProvidersDoesNotReuseTheirUnrelatedGameIds() {
        val old = GameMetadata(provider = GameMetadataProviderIds.THEGAMESDB, providerGameId = "42",
            canonicalTitle = "A provider display name")
        val query = gameMetadataQuery(game(), old, "en", "eu", null,
            provider = GameMetadataProviderIds.LIBRETRO)
        assertEquals("Original (Europe)", query.canonicalTitle)
        assertNull(query.providerGameId)
    }

    @Test fun loadedDatIdentityReplacesAnEarlierAutomaticLibretroGuess() {
        val old = GameMetadata(provider = GameMetadataProviderIds.LIBRETRO,
            providerGameId = "Nintendo - Super Nintendo Entertainment System|SHORT ROM LABEL",
            canonicalTitle = "SHORT ROM LABEL")
        val query = gameMetadataQuery(game(), old, "en", "eu", null,
            provider = GameMetadataProviderIds.LIBRETRO)
        assertEquals("Original (Europe)", query.canonicalTitle)
        assertNull(query.providerGameId)
        val pinned = gameMetadataQuery(game(), old.copy(matchLocked = true), "en", "eu", null,
            provider = GameMetadataProviderIds.LIBRETRO)
        assertEquals(old.canonicalTitle, pinned.canonicalTitle)
        assertEquals(old.providerGameId, pinned.providerGameId)
    }

    @Test fun lookupMissFingerprintChangesForRegionLanguageFileAndIdentity() {
        val game = game()
        val query = gameMetadataQuery(game, null, "de", "eu", null)
        val key = gameMetadataFingerprint(game, query)
        assertNotEquals(key, gameMetadataFingerprint(game, query.copy(language = "en")))
        assertNotEquals(key, gameMetadataFingerprint(game, query.copy(region = "jp")))
        assertNotEquals(key, gameMetadataFingerprint(game.copy(modified = 13), query))
        assertNotEquals(key, gameMetadataFingerprint(game, query.copy(providerGameId = "5")))
        assertNotEquals(key, gameMetadataFingerprint(game.copy(datVersion = 2), query))
        assertNotEquals(key, gameMetadataFingerprint(game.copy(verifiedTitle = "Original (USA)"), query))
    }

    @Test fun lockedFieldsAndIndependentArtworkSurviveRematch() {
        val old = GameMetadata(title = "My title", summary = "My text", year = 1991,
            provider = "screenscraper", coverUrl = "https://cdn.thegamesdb.net/selected.jpg",
            artworkProviders = mapOf("cover" to "thegamesdb"), artworkSources = mapOf("cover" to "https://thegamesdb.net/1"),
            lockedFields = setOf("title", "summary", "cover"))
        val next = GameMetadata(title = "Remote", summary = "Remote summary", year = 1992,
            provider = "libretro", coverUrl = "https://thumbnails.libretro.com/other.png", backdropUrl = "https://example.com/bg.png")
        val result = preserveGameMetadataLocks(old, next)
        assertEquals("My title", result.title)
        assertEquals("My text", result.summary)
        assertEquals(1992, result.year)
        assertEquals(old.coverUrl, result.coverUrl)
        assertEquals("thegamesdb", result.artworkProviders["cover"])
        assertEquals(next.backdropUrl, result.backdropUrl)
    }

    @Test fun choosingOneArtworkKeepsOtherFieldsAndProvenance() {
        val metadata = GameMetadata(title = "Game", provider = "screenscraper", providerGameId = "1",
            coverUrl = "https://example.com/cover.png", backdropUrl = "https://example.com/bg.png")
        val changed = metadata.withArtwork(GameArtworkKind.COVER, "https://cdn.thegamesdb.net/cover.png",
            "thegamesdb", "https://thegamesdb.net/game.php?id=2")
        assertEquals(metadata.title, changed.title)
        assertEquals(metadata.providerGameId, changed.providerGameId)
        assertEquals(metadata.backdropUrl, changed.backdropUrl)
        assertEquals("thegamesdb", changed.artworkProviders["cover"])
    }

    @Test fun sidecarPathsRejectOutsideFoldersUrlsAndTraversal() {
        assertEquals("images/Cover.png", gameSidecarRelativePath("./images/Cover.png"))
        assertEquals("images/Cover.png", gameSidecarRelativePath(".\\images\\Cover.png"))
        for (bad in listOf("../cover.png", "images/../../cover.png", "/private/cover.png",
                "file:///private/cover.png", "https://example.com/cover.png", "C:\\cover.png", "")) {
            assertNull(bad, gameSidecarRelativePath(bad))
        }
    }

    @Test fun localImportFillsMissingFieldsAndRetainsCuratedMetadata() {
        val existing = GameMetadata(title = "My name", summary = "", provider = "screenscraper", providerGameId = "123",
            canonicalTitle = "Original", matchLocked = true, lockedFields = setOf("title"))
        val imported = GameMetadata(title = "Sidecar title", summary = "Local description", year = 1993,
            provider = "local", canonicalTitle = "Sidecar title")
        val merged = mergeLocalFields(existing, imported)
        assertEquals("My name", merged.title)
        assertEquals("Local description", merged.summary)
        assertEquals("123", merged.providerGameId)
        assertEquals("Original", merged.canonicalTitle)
        assertTrue(merged.matchLocked)
        assertTrue("summary" in merged.lockedFields)
    }

    @Test fun curatedLocalSidecarReplacesUnlockedOnlineFieldsButNotLockedBlanks() {
        val existing = GameMetadata(title = "Online name", summary = "Online summary", year = 1990,
            developer = "", publisher = "Online publisher", lockedFields = setOf("developer"),
            provider = "screenscraper", providerGameId = "123")
        val imported = GameMetadata(title = "Curated name", summary = "Curated summary", year = 1991,
            developer = "Local developer", publisher = "", provider = "local")
        val merged = mergeLocalFields(existing, imported)
        assertEquals("Curated name", merged.title)
        assertEquals("Curated summary", merged.summary)
        assertEquals(1991, merged.year)
        assertEquals("", merged.developer)
        assertEquals("Online publisher", merged.publisher)
        assertTrue(merged.lockedFields.containsAll(setOf("title", "summary", "year", "developer")))
        assertFalse("publisher" in merged.lockedFields)
        assertEquals("123", merged.providerGameId)
    }
}
