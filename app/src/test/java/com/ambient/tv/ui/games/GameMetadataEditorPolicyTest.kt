package com.ambient.tv.ui.games

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import com.ambient.tv.games.metadata.GameArtworkKind
import com.ambient.tv.games.metadata.GameMetadata
import com.ambient.tv.games.metadata.GameMetadataCandidate

class GameMetadataEditorPolicyTest {
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
