package com.ambient.tv.data.local

import org.junit.Assert.*
import org.junit.Test

class TrailerBackupTest {
    @Test fun trailerSettingsAndCredentialOptInRoundTrip() {
        val source = AppPreferences(memoryPreferences())
        val sourceTokens = TokenStore(memoryPreferences(), source)
        val exporter = SettingsBackupManager(source, sourceTokens)
        source.trailersEnabled = false
        source.trailerLanguage = "de"
        sourceTokens.tmdbApiKey = "private-tmdb-key"
        source.homeRows = listOf(HomeRowDefinition(system = "trailers", contentKey = "CINEMA", displayMode = "landscape"))
        val target = AppPreferences(memoryPreferences())
        val targetTokens = TokenStore(memoryPreferences(), target)
        val importer = SettingsBackupManager(target, targetTokens)
        val settingsOnly = exporter.exportJson()
        assertFalse(settingsOnly.contains("private-tmdb-key"))
        importer.importJson(exporter.exportJson(includeCredentials = true))
        assertEquals("private-tmdb-key", targetTokens.tmdbApiKey)
        assertFalse(target.trailersEnabled)
        assertEquals("de", target.trailerLanguage)
        assertEquals(source.homeRows, target.homeRows)
        // A settings-only backup carries no key and keeps the receiver's own key.
        importer.importJson(settingsOnly)
        assertEquals("private-tmdb-key", targetTokens.tmdbApiKey)
    }

    @Test fun preTrailerBackupDoesNotOverwriteExistingKey() {
        val prefs = AppPreferences(memoryPreferences())
        val tokens = TokenStore(memoryPreferences(), prefs)
        tokens.tmdbApiKey = "existing-key"
        SettingsBackupManager(prefs, tokens).importJson("""{"schemaVersion":46,"exportedAtEpochMs":0}""")
        assertEquals("existing-key", tokens.tmdbApiKey)
    }
}
