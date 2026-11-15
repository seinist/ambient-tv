package com.ambient.tv.data.local

import com.ambient.tv.data.weather.WeatherLocation
import com.ambient.tv.data.weather.WeatherSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class WeatherSettingsBackupTest {
    private val chosen = WeatherSettings(
        enabled = true,
        showInSidebar = false,
        locations = listOf(
            WeatherLocation("geonames:2950159", "Berlin", "Deutschland", "Berlin", 52.52, 13.41, "Europe/Berlin"),
            WeatherLocation("geonames:5128581", "New York", "United States", "New York", 40.71, -74.01, "America/New_York"),
        ),
        selectedLocationId = "geonames:5128581",
        showInHeader = false,
        showInScreensaver = true,
        temperatureUnit = "fahrenheit",
        windUnit = "mph",
        radarProvider = "rainviewer",
        satelliteProvider = "eumetsat",
    )

    @Test fun locationsDisplayChoicesUnitsAndManualProvidersRoundTrip() {
        val storage = memoryPreferences()
        val source = AppPreferences(storage).apply { weatherSettings = chosen }
        assertEquals(chosen, AppPreferences(storage).weatherSettings)
        val exported = manager(source).exportJson()
        val wire = Json.parseToJsonElement(exported).jsonObject
        assertEquals("78", wire.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("false", wire.getValue("weather").jsonObject.getValue("showInSidebar").jsonPrimitive.content)

        val target = AppPreferences(memoryPreferences())
        manager(target).importJson(exported)
        assertEquals(chosen, target.weatherSettings)
    }

    @Test fun oldMissingAndNullWeatherBackupsPreserveTheReceivingChoice() {
        for (document in listOf(
            """{"schemaVersion":77,"exportedAtEpochMs":0}""",
            """{"schemaVersion":78,"exportedAtEpochMs":0}""",
            """{"schemaVersion":78,"exportedAtEpochMs":0,"weather":null}""",
        )) {
            val target = AppPreferences(memoryPreferences()).apply { weatherSettings = chosen }
            manager(target).importJson(document)
            assertEquals(chosen, target.weatherSettings)
        }
    }

    @Test fun unsupportedProviderAndUnitValuesAreNormalizedOnImport() {
        val target = AppPreferences(memoryPreferences()).apply { weatherSettings = chosen }
        manager(target).importJson("""{
            "schemaVersion":78,"exportedAtEpochMs":0,
            "weather":{"enabled":true,"radarProvider":"unknown","satelliteProvider":"rainviewer",
                "temperatureUnit":"kelvin","windUnit":"unsupported"}
        }""")
        val restored = target.weatherSettings
        assertEquals("", restored.radarProvider)
        assertEquals("", restored.satelliteProvider)
        assertEquals("celsius", restored.temperatureUnit)
        assertEquals("kmh", restored.windUnit)
        assertTrue(restored.showInSidebar)
        assertTrue(restored.enabled)
    }

    private fun manager(preferences: AppPreferences) =
        SettingsBackupManager(preferences, TokenStore(memoryPreferences(), preferences))
}
