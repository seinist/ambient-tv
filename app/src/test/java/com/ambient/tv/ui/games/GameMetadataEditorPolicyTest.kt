package com.ambient.tv.ui.games

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import com.ambient.tv.games.metadata.GameArtworkKind
import com.ambient.tv.games.metadata.GameMetadata
import com.ambient.tv.games.metadata.GameMetadataCandidate
import com.ambient.tv.games.library.LibraryGame

class GameMetadataEditorPolicyTest {
    @Test fun correctedDatNameReachesTheSearchFieldWithoutOverridingAManualMatch() {
        val game = LibraryGame("game", "folder", "source", "Game.sfc", "Game.sfc", "snes",
            "My nickname", "SHORT ROM TITLE", 4096, 1, false, 0,
            customTitle = "My nickname", verifiedTitle = "Full Game (Europe)")
        val previous = GameMetadata(provider = "libretro", canonicalTitle = "SHORT ROM TITLE")
        assertEquals("Full Game (Europe)", gameMetadataSearchTitle(game, previous))
        assertEquals("SHORT ROM TITLE", gameMetadataSearchTitle(game, previous.copy(matchLocked = true)))
        assertEquals("Game", gameMetadataSearchTitle(game.copy(verifiedTitle = null), GameMetadata()))
    }

    @Test fun switchingToLibretroUsesTheDatNameAndSwitchingBackRestoresTheProviderDefault() {
        val game = LibraryGame("game", "folder", "source", "Zelda.zip", "Zelda.zip", "nes",
            "The Legend of Zelda", null, 4096, 1, false, 0,
            verifiedTitle = "Zelda no Densetsu (Japan)")
        val metadata = GameMetadata(provider = "thegamesdb", providerGameId = "123",
            canonicalTitle = "The Legend of Zelda", matchLocked = true)
        assertEquals("The Legend of Zelda", gameMetadataSearchTitle(game, metadata, "thegamesdb"))
        val libretro = gameMetadataProviderSearchText(game, metadata, "libretro", "The Legend of Zelda", edited = false)
        assertEquals("Zelda no Densetsu (Japan)", libretro)
        assertEquals("The Legend of Zelda",
            gameMetadataProviderSearchText(game, metadata, "thegamesdb", libretro, edited = false))
    }

    @Test fun switchingProvidersNeverReplacesAnEditedQueryEvenIfItMatchesAnEarlierDefault() {
        val game = LibraryGame("game", "folder", "source", "Game.sfc", "Game.sfc", "snes",
            "Game", null, 4096, 1, false, 0, verifiedTitle = "Verified name (Europe)")
        val metadata = GameMetadata(provider = "thegamesdb", canonicalTitle = "Provider title")
        for (query in listOf("My search", "Provider title", "")) {
            assertEquals(query, gameMetadataProviderSearchText(game, metadata, "libretro", query, edited = true))
            assertEquals(query, gameMetadataProviderSearchText(game, metadata, "thegamesdb", query, edited = true))
        }
    }

    @Test fun clearingYearIsAllowedButInvalidInputMustNotEraseAnExistingYear() {
        assertTrue(gameMetadataYearIsValid(""))
        assertTrue(gameMetadataYearIsValid("   "))
        assertTrue(gameMetadataYearIsValid("1994"))
        assertTrue(gameMetadataYearIsValid(" 2001 "))
        listOf("199x", "-1", "0", "1799", "2201", "200000000000", "2e3", "19.94").forEach {
            assertFalse("Invalid user year $it", gameMetadataYearIsValid(it))
        }
    }

    @Test fun alternateCoversOfTheSameGameDoNotCollideInTheChooser() {
        val european = GameMetadataCandidate(GameMetadata(provider = "screenscraper", providerGameId = "42",
            canonicalTitle = "A game", coverUrl = "https://images.example/eu.png"))
        val american = european.copy(metadata = european.metadata.copy(coverUrl = "https://images.example/us.png"))
        assertNotEquals(gameMetadataChoiceKey(european, GameArtworkKind.COVER),
            gameMetadataChoiceKey(american, GameArtworkKind.COVER))
    }

    @Test fun editingOtherFieldsMustPreserveATitleChosenBeforeMetadataWasAdded() {
        val provider = GameMetadata(title = "Provider name", lockedFields = setOf("cover"))
        val editable = gameMetadataWithExistingTitle(provider, "My earlier title")
        assertEquals("My earlier title", editable.title)
        assertEquals(setOf("cover", "title"), editable.lockedFields)
        // Once the user explicitly unlocks/clears the legacy custom title, automatic title
        // updates stay enabled; an unrelated reload must not lock the provider title again.
        assertEquals(provider, gameMetadataWithExistingTitle(provider, null))
    }
}
