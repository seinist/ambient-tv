package com.ambient.tv.data.local

import com.ambient.tv.data.radio.RadioStation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioBackupTest {
    private fun station(id: String, name: String) = RadioStation(
        id = id,
        name = name,
        streamUrl = "https://example.test/$id",
        homepage = null,
        artworkUrl = null,
        country = "Germany",
        countryCode = "DE",
        state = null,
        languages = listOf("German"),
        tags = listOf("music"),
        codec = "MP3",
        bitrateKbps = 128,
        isHls = false,
        votes = 1,
        clickCount = 2,
    )

    @Test fun radioEnabledAndFavoritesRoundTripButHistoryStaysLocal() {
        val source = AppPreferences(memoryPreferences())
        val sourceTokens = TokenStore(memoryPreferences(), source)
        source.radioEnabled = true
        source.setRadioFavorite(station("favorite", "Favorite"), true)
        source.recordRadioStation(station("history-only", "History"))

        val raw = SettingsBackupManager(source, sourceTokens).exportJson()
        assertEquals("75", Json.parseToJsonElement(raw).jsonObject["schemaVersion"].toString())
        assertTrue(raw.contains("favorite"))
        assertFalse(raw.contains("history-only"))

        val target = AppPreferences(memoryPreferences())
        val targetTokens = TokenStore(memoryPreferences(), target)
        target.recordRadioStation(station("local-history", "Local History"))
        SettingsBackupManager(target, targetTokens).importJson(raw)

        assertTrue(target.radioEnabled)
        assertEquals(listOf("favorite"), target.radioFavorites.map { it.id })
        assertEquals(listOf("local-history"), target.recentRadioStations.map { it.id })
    }

    @Test fun olderBackupDisablesRadioAndClearsFavorites() {
        val target = AppPreferences(memoryPreferences())
        val tokens = TokenStore(memoryPreferences(), target)
        target.radioEnabled = true
        target.setRadioFavorite(station("old", "Old"), true)

        SettingsBackupManager(target, tokens).importJson(
            """{"schemaVersion":74,"exportedAtEpochMs":0}""",
        )

        assertFalse(target.radioEnabled)
        assertTrue(target.radioFavorites.isEmpty())
    }
}
