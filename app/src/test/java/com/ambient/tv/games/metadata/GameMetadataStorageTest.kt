package com.ambient.tv.games.metadata

import org.junit.Assert.*
import org.junit.Test

class GameMetadataStorageTest {
    @Test fun separatelyIssuedTicketsWithTheSameGuardHaveValueEquality() {
        val first = GameMetadataTicket("rom", "folder", "folder-version", "file-version", "epoch", "1234ABCD")
        val second = GameMetadataTicket("rom", "folder", "folder-version", "file-version", "epoch", "1234ABCD")
        assertEquals(first, second)
        assertNotEquals(first, second.copy(epoch = "new-epoch"))
    }

    @Test fun sidecarNameFallbackNeverCrossesHierarchyOrAcceptsUnsafePaths() {
        assertEquals("Game.sfc", gameSidecarFilenameFallback("./Game.sfc", localSaf = true))
        assertNull(gameSidecarFilenameFallback("Game.sfc", localSaf = false))
        for (path in listOf("EU/Game.sfc", "/outside/Game.sfc", "../Game.sfc", "https://example.invalid/Game.sfc"))
            assertNull(path, gameSidecarFilenameFallback(path, localSaf = true))
    }

    @Test fun backgroundRefreshPreservesLockedImageSourceAndFields() {
        val old = GameMetadata(title = "My title", summary = "Old description", year = 1991,
            coverUrl = "https://example.invalid/my.png", artworkProviders = mapOf("cover" to "screenscraper"),
            artworkSources = mapOf("cover" to "https://example.invalid/source"),
            lockedFields = setOf("title", "year", "cover"))
        val incoming = GameMetadata(title = "Provider title", summary = "New description", year = 1992,
            coverUrl = "https://example.invalid/new.png", artworkProviders = mapOf("cover" to "libretro"))
        val merged = GameMetadataStorage.merge(old, incoming, manual = false)
        assertEquals("My title", merged.title)
        assertEquals("New description", merged.summary)
        assertEquals(1991, merged.year)
        assertEquals(old.coverUrl, merged.coverUrl)
        assertEquals("screenscraper", merged.artworkProviders["cover"])
        assertEquals("https://example.invalid/source", merged.artworkSources["cover"])
    }

    @Test fun selectedGameIdentityCannotBeReplacedByAnAutomaticNameMatch() {
        val old = GameMetadata(title = "Chosen", provider = "screenscraper", providerGameId = "12",
            canonicalTitle = "Chosen (Europe)", matchLocked = true)
        val candidate = old.copy(title = "Similar name", providerGameId = "13", matchLocked = false)
        assertEquals(old, GameMetadataStorage.merge(old, candidate, manual = false))
        assertEquals(candidate, GameMetadataStorage.merge(old, candidate, manual = true))
        assertTrue(GameMetadataStorage.merge(old, old.copy(matchLocked = false), manual = false).matchLocked)
    }

    @Test fun catalogOnlyChoiceCanAcquireItsImageNamespaceWithoutChangingTheSelectedTitle() {
        val old = GameMetadata(title = "Sonic (USA)", canonicalTitle = "Sonic (USA)",
            provider = GameMetadataProviderIds.LIBRETRO, matchLocked = true)
        val confirmed = old.copy(providerGameId = "Sega - Game Gear|Sonic (USA)",
            coverUrl = "https://thumbnails.libretro.com/cover.png")
        assertEquals(confirmed, GameMetadataStorage.merge(old, confirmed, manual = false))
        assertEquals(old, GameMetadataStorage.merge(old, confirmed.copy(canonicalTitle = "Other game"), manual = false))
        assertEquals(old, GameMetadataStorage.merge(old, old.copy(canonicalTitle = "Other game"), manual = false))
        assertEquals(old, GameMetadataStorage.merge(old, confirmed.copy(provider = GameMetadataProviderIds.THEGAMESDB), manual = false))
        val pinned = old.copy(providerGameId = "Sega - Mega Drive - Genesis|Sonic (USA)")
        assertEquals(pinned, GameMetadataStorage.merge(pinned, confirmed, manual = false))
    }

    @Test fun explicitEditCanClearAndUnlockAField() {
        val old = GameMetadata(summary = "Locked description", lockedFields = setOf("summary", "cover"))
        val edit = old.copy(summary = "", lockedFields = emptySet())
        val result = GameMetadataStorage.merge(old, edit, manual = true)
        assertEquals("", result.summary)
        assertTrue(result.lockedFields.isEmpty())
    }

    @Test fun aLockedLocalPictureKeepsItsLackOfRemoteProvenance() {
        val old = GameMetadata(lockedFields = setOf("cover"))
        val incoming = GameMetadata(coverUrl = "https://example.invalid/cover.png",
            artworkProviders = mapOf("cover" to "libretro"),
            artworkSources = mapOf("cover" to "https://example.invalid/game"))
        val merged = GameMetadataStorage.merge(old, incoming, manual = false)
        assertNull(merged.coverUrl)
        assertFalse(merged.artworkProviders.containsKey("cover"))
        assertFalse(merged.artworkSources.containsKey("cover"))
    }
}
