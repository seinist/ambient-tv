package com.ambient.tv.games.metadata

import com.ambient.tv.games.library.LibraryGame
import org.junit.Assert.*
import org.junit.Test

class GameMetadataRefreshPolicyTest {
    private val verified = "Verified Game (World)"
    private fun game(verifiedTitle: String? = verified) = LibraryGame(
        fileId = "rom", folderId = "folder", sourceId = "nas", path = "Games/Filename guess.zip",
        filename = "Filename guess.zip", systemId = "gb", title = "INTERNAL NAME",
        internalTitle = "INTERNAL NAME", size = 4096, modified = 1, favorite = false,
        lastPlayed = 0, verifiedTitle = verifiedTitle,
    )

    @Test fun verifiedIdentityKeepsEveryCompatibleNamespaceButExcludesFilenameGuesses() {
        val query = gameMetadataQuery(game(), null, "en", "eu", null,
            provider = GameMetadataProviderIds.LIBRETRO)
        val candidates = GameMetadataPlatforms.libretroCandidates(query, listOf(verified))
        assertTrue(candidates.any { it.metadata.canonicalTitle == "Filename guess" })
        val selected = automaticLibretroCandidates(candidates, verified, matchLocked = false)
        assertEquals(listOf("Nintendo - Game Boy", "Nintendo - Game Boy Color"),
            selected.map { it.metadata.providerGameId.substringBefore('|') })
        assertTrue(selected.all { it.metadata.canonicalTitle == verified })
    }

    @Test fun missingVerifiedArtworkCannotFallThroughToAnImageForTheFilename() {
        val query = gameMetadataQuery(game(), null, "en", "eu", null,
            provider = GameMetadataProviderIds.LIBRETRO)
        val candidates = GameMetadataPlatforms.libretroCandidates(query, listOf(verified))
        // The thumbnail server contains a different title matching the filename, but none
        // of the verified edition. No automatic choice may replace the checksum identity.
        val availableImages = candidates.filter { !it.catalogMatch }.map { it.metadata.coverUrl }.toSet()
        assertTrue(availableImages.isNotEmpty())
        val match = automaticLibretroCandidates(candidates, verified, matchLocked = false)
            .firstOrNull { it.metadata.coverUrl in availableImages }
        assertNull(match)
    }

    @Test fun filenameBasedMatchingRemainsAvailableBeforeTheRomHasAVerifiedIdentity() {
        val query = gameMetadataQuery(game(null), null, "en", "eu", null,
            provider = GameMetadataProviderIds.LIBRETRO)
        val candidates = GameMetadataPlatforms.libretroCandidates(query)
        val selected = automaticLibretroCandidates(candidates, null, matchLocked = false)
        assertEquals(candidates, selected)
        assertTrue(selected.any { it.metadata.canonicalTitle == "Filename guess" })
    }

    @Test fun explicitlyLockedDifferentMatchRetainsItsSelectedIdentity() {
        val previous = GameMetadata(provider = GameMetadataProviderIds.LIBRETRO,
            canonicalTitle = "Chosen edition", matchLocked = true,
            providerGameId = "Nintendo - Game Boy Color|Chosen edition")
        val query = gameMetadataQuery(game(), previous, "en", "eu", null,
            provider = GameMetadataProviderIds.LIBRETRO)
        val candidates = GameMetadataPlatforms.libretroCandidates(query, listOf(verified))
        val selected = automaticLibretroCandidates(candidates, verified, matchLocked = previous.matchLocked)
        assertEquals(listOf(previous.providerGameId), selected.map { it.metadata.providerGameId })
        assertTrue(gameMetadataMatchesQueryIdentity(previous, query))
    }

    @Test fun cachedSuccessForAnEarlierFilenameGuessCannotSatisfyTheVerifiedDatQuery() {
        val previous = GameMetadata(title = verified, provider = GameMetadataProviderIds.LIBRETRO,
            canonicalTitle = "Filename guess", providerGameId = "Nintendo - Game Boy|Filename guess")
        val query = gameMetadataQuery(game(), previous, "en", "eu", null,
            provider = GameMetadataProviderIds.LIBRETRO)
        // The offline display repair intentionally preserves canonical identity until a
        // real rematch. A cached "found" result must not reuse that old artwork identity.
        assertEquals(verified, query.canonicalTitle)
        assertFalse(gameMetadataMatchesQueryIdentity(previous, query))
        assertTrue(gameMetadataMatchesQueryIdentity(previous.copy(canonicalTitle = verified), query))
    }

    @Test fun changedSearchPolicyVersionsAllProviderLookupCaches() {
        val query = gameMetadataQuery(game(), null, "en", "eu", null,
            provider = GameMetadataProviderIds.LIBRETRO)
        val legacy = gameMetadataFingerprint(game(), query)
        assertNotEquals(legacy, gameMetadataLookupFingerprint(GameMetadataProviderIds.LIBRETRO, legacy))
        for (provider in listOf(GameMetadataProviderIds.THEGAMESDB, GameMetadataProviderIds.SCREENSCRAPER)) {
            assertNotEquals(legacy, gameMetadataLookupFingerprint(provider, legacy))
        }
        assertNotEquals(gameMetadataLookupFingerprint(GameMetadataProviderIds.LIBRETRO, legacy),
            gameMetadataLookupFingerprint(GameMetadataProviderIds.LIBRETRO,
                gameMetadataFingerprint(game(), query.copy(region = "jp"))))
    }
}
