package com.ambient.tv.games.metadata

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.data.local.memoryPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GameMetadataCredentialsTest {
    @Test fun incompleteOrInvalidCredentialPairsAreRejectedBeforeStorageChanges() {
        val preferences = AppPreferences(memoryPreferences())
        val tokens = TokenStore(memoryPreferences(), preferences)
        val existing = GameMetadataCredentials(screenScraperDevId = "application", screenScraperDevPassword = "secret",
            screenScraperUser = "user", screenScraperPassword = "password", theGamesDbApiKey = "key")
        tokens.setGameMetadataCredentials(existing)
        for (invalid in listOf(GameMetadataCredentials(screenScraperDevId = "other"),
            GameMetadataCredentials(screenScraperUser = "incomplete"), GameMetadataCredentials(theGamesDbApiKey = "\n"),
            GameMetadataCredentials(theGamesDbApiKey = "x".repeat(257)))) {
            assertTrue(runCatching { tokens.setGameMetadataCredentials(invalid) }.isFailure)
            assertEquals(existing, tokens.gameMetadataCredentials())
        }
    }

    @Test fun runtimeCredentialsStayOutOfPortablePreferencesAndDebugStrings() {
        val plain = memoryPreferences()
        val encrypted = memoryPreferences()
        val preferences = AppPreferences(plain)
        val tokens = TokenStore(encrypted, preferences)
        val credentials = GameMetadataCredentials(theGamesDbApiKey = "private-value")
        tokens.setGameMetadataCredentials(credentials)
        preferences.gameMetadataOptions = GameMetadataOptions(provider = GameMetadataProviderIds.THEGAMESDB)
        assertFalse(plain.all.values.any { it.toString().contains("private-value") })
        assertFalse(credentials.toString().contains("private-value"))
        assertTrue(Json.encodeToString(credentials).contains("private-value")) // explicit credential export only
        assertEquals(credentials, tokens.exportGameMetadataCredentials())
    }

    @Test fun importingOnlyOneProviderPreservesTheOtherAndClearingIsExplicit() {
        val preferences = AppPreferences(memoryPreferences())
        val tokens = TokenStore(memoryPreferences(), preferences)
        val screenScraper = GameMetadataCredentials(screenScraperDevId = "application", screenScraperDevPassword = "secret")
        tokens.setGameMetadataCredentials(screenScraper)
        tokens.importGameMetadataCredentials(GameMetadataCredentials(theGamesDbApiKey = "new-key"))
        assertEquals(screenScraper.copy(theGamesDbApiKey = "new-key"), tokens.gameMetadataCredentials())
        tokens.importGameMetadataCredentials(GameMetadataCredentials())
        assertEquals("new-key", tokens.gameMetadataCredentials().theGamesDbApiKey)
        tokens.setGameMetadataCredentials(GameMetadataCredentials())
        assertNull(tokens.exportGameMetadataCredentials())
    }

    @Test fun optionsRoundTripAndNormalizeUnknownOrLegacyValues() {
        val preferences = AppPreferences(memoryPreferences())
        preferences.gameMetadataOptions = GameMetadataOptions(provider = "invalid", automatic = false, region = "DE", language = "xx")
        assertEquals(GameMetadataOptions(automatic = false, region = "de"), preferences.gameMetadataOptions)
        preferences.gameMetadataOptions = GameMetadataOptions(provider = "none")
        assertFalse(preferences.gameMetadataOptions.enabled)
    }
}
