package com.ambient.tv.data.local

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockWeatherLayoutSettingsTest {
    @Test fun layoutChoicesSurvivePersistenceAndNormalize() {
        ClockSettings.weatherLayouts.forEach { layout ->
            val source = ClockSettings(
                weatherLayout = layout, weatherAccent = false, weatherTransitions = false,
            )
            val saved = Json.encodeToString(ClockSettings.serializer(), source)
            val restored = Json.decodeFromString(ClockSettings.serializer(), saved).normalized()
            assertEquals(source, restored)
            assertFalse(restored.weatherAccent)
            assertFalse(restored.weatherTransitions)
        }
    }

    @Test fun automaticLayoutUsesCompactHeaderButVerticalScreensaver() {
        val defaults = ClockSettings()
        assertEquals(ClockSettings.WEATHER_LAYOUT_INTEGRATED, defaults.resolvedWeatherLayout())
        assertEquals(ClockSettings.WEATHER_LAYOUT_BELOW, defaults.resolvedWeatherLayout(screensaver = true))
        val explicit = defaults.copy(weatherLayout = ClockSettings.WEATHER_LAYOUT_RIGHT)
        assertEquals(ClockSettings.WEATHER_LAYOUT_RIGHT, explicit.resolvedWeatherLayout())
        assertEquals(ClockSettings.WEATHER_LAYOUT_RIGHT, explicit.resolvedWeatherLayout(screensaver = true))
    }

    @Test fun oldBackupsAndInvalidLayoutsRemainSafe() {
        val older = Json.decodeFromString(ClockSettings.serializer(), """{"display":"home","size":"large"}""").normalized()
        assertEquals(ClockSettings.WEATHER_LAYOUT_AUTO, older.weatherLayout)
        assertTrue(older.weatherAccent)
        assertTrue(older.weatherTransitions)
        assertEquals(ClockSettings.WEATHER_LAYOUT_AUTO,
            older.copy(weatherLayout = "unsupported").normalized().weatherLayout)
    }
}
