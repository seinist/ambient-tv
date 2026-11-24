package com.ambient.tv.ui.games

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import com.ambient.tv.games.metadata.GameArtworkKind
import com.ambient.tv.games.metadata.GameMetadata
import com.ambient.tv.games.metadata.GameMetadataCandidate
import com.ambient.tv.games.metadata.GameMetadataPlatforms
import com.ambient.tv.games.metadata.GameMetadataQuery
import com.ambient.tv.games.library.LibraryGame

class GameMetadataEditorPolicyTest {
    @Test fun correctedDatNameReachesTheSearchFieldWithoutOverridingAManualMatch() {
        val game = LibraryGame("game", "folder", "source", "Game.sfc", "Game.sfc", "snes",
            "My nickname", "SHORT ROM TITLE", 4096, 1, false, 0,
            customTitle = "My nickname", verifiedTitle = "Full Game (Europe)")
        val previous = GameMetadata(provider = "libretro", canonicalTitle = "SHORT ROM TITLE")
        assertEquals("Full Game", gameMetadataSearchTitle(game, previous))
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
        assertEquals("Zelda no Densetsu", libretro)
        assertEquals("The Legend of Zelda",
            gameMetadataProviderSearchText(game, metadata, "thegamesdb", libretro, edited = false))
    }

    @Test fun switchingProvidersNeverReplacesAnEditedQueryEvenIfItMatchesAnEarlierDefault() {
        val game = LibraryGame("game", "folder", "source", "Game.sfc", "Game.sfc", "snes",
            "Game", null, 4096, 1, false, 0, verifiedTitle = "Verified name (Europe)")
        val metadata = GameMetadata(provider = "thegamesdb", canonicalTitle = "Provider title")
        for (query in listOf("My search", "Provider title", "Game (USA)", "")) {
            assertEquals(query, gameMetadataProviderSearchText(game, metadata, "libretro", query, edited = true))
            assertEquals(query, gameMetadataProviderSearchText(game, metadata, "thegamesdb", query, edited = true))
        }
    }

    @Test fun suggestedSearchTitleDropsOnlyRegionTagsAndKeepsTheCanonicalMatchIntact() {
        val canonical = "Game (The Lost Levels) (USA, Europe) (Rev 1)"
        val game = LibraryGame("game", "folder", "source", "Game.sfc", "Game.sfc", "snes",
            canonical, null, 4096, 1, false, 0, verifiedTitle = canonical)
        val metadata = GameMetadata(provider = "libretro", canonicalTitle = canonical,
            providerGameId = "Nintendo - Super Nintendo Entertainment System|$canonical", matchLocked = true,
            coverUrl = "https://thumbnails.libretro.com/canonical-region-name.png")
        assertEquals("Game (The Lost Levels) (Rev 1)", gameMetadataSearchTitle(game, metadata))
        assertEquals(canonical, metadata.canonicalTitle)
        assertEquals("Nintendo - Super Nintendo Entertainment System|$canonical", metadata.providerGameId)
        assertEquals("https://thumbnails.libretro.com/canonical-region-name.png", metadata.coverUrl)
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

    @Test fun regionalDatResultsKeepTheirCanonicalIdentitiesAndDistinctRegionSubtitles() {
        val canonical = listOf("A game (Europe)", "A game (USA)")
        val choices = GameMetadataPlatforms.libretroCandidates(
            GameMetadataQuery("snes", "A game", "short.sfc", region = "eu"), canonical)
            .filter { it.catalogMatch }
        assertEquals(canonical, choices.map { it.metadata.title })
        assertEquals(canonical, choices.map { it.metadata.canonicalTitle })
        assertEquals(listOf("Europe", "USA"), choices.map { gameMetadataChoiceRegion(it.metadata) })
        assertNotEquals(gameMetadataChoiceKey(choices[0], null), gameMetadataChoiceKey(choices[1], null))
        assertTrue(choices[0].metadata.coverUrl!!.contains("Europe"))
        assertTrue(choices[1].metadata.coverUrl!!.contains("USA"))
    }

    @Test fun regionSubtitlesUseTheCanonicalEditionBeforeTheRequestRegionDefault() {
        assertEquals("USA", gameMetadataChoiceRegion(GameMetadata(title = "Localized title",
            canonicalTitle = "Original title (USA)", region = "eu")))
        assertEquals("jp", gameMetadataChoiceRegion(GameMetadata(title = "Title", region = "jp")))
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
