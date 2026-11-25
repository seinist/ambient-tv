package com.ambient.tv.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class MousePointerSettingsTest {
    private fun manager(preferences: AppPreferences) =
        SettingsBackupManager(preferences, TokenStore(memoryPreferences(), preferences))

    @Test fun defaultAndEverySupportedTimeoutPersistAcrossRestartIncludingOff() {
        val storage = memoryPreferences()
        val preferences = AppPreferences(storage)
        assertEquals(2, preferences.mousePointerHideSeconds)
        for (seconds in listOf(0, 1, 2, 3, 5)) {
            preferences.mousePointerHideSeconds = seconds
            assertEquals(seconds, AppPreferences(storage).mousePointerHideSeconds)
        }
        for (invalid in listOf(-1, 4, 60, Int.MAX_VALUE)) {
            preferences.mousePointerHideSeconds = invalid
            assertEquals(2, AppPreferences(storage).mousePointerHideSeconds)
            storage.edit().putInt(MousePointerSettings.PREFERENCE_KEY, invalid).apply()
            assertEquals(2, AppPreferences(storage).mousePointerHideSeconds)
        }
    }

    @Test fun backupRoundTripsTheActualChoiceIncludingAnExplicitZero() {
        val source = AppPreferences(memoryPreferences())
        val target = AppPreferences(memoryPreferences())
        for (seconds in listOf(0, 1, 2, 3, 5)) {
            source.mousePointerHideSeconds = seconds
            val raw = manager(source).exportJson()
            val portable = Json.parseToJsonElement(raw).jsonObject.getValue("extras").jsonObject
                .getValue("portable").jsonObject
            assertEquals(seconds.toString(), portable.getValue("mousePointerHideSeconds").jsonPrimitive.content)
            target.mousePointerHideSeconds = if (seconds == 5) 0 else 5
            manager(target).importJson(raw)
            assertEquals(seconds, target.mousePointerHideSeconds)
        }
    }

    @Test fun oldAndPartialBackupsPreserveTheReceiverAndInvalidValuesUseTheDefault() {
        val target = AppPreferences(memoryPreferences()).apply { mousePointerHideSeconds = 5 }
        val schema = SettingsBackupManager.CURRENT_SCHEMA_VERSION
        val documents = listOf(
            """{"schemaVersion":79,"exportedAtEpochMs":0}""",
            """{"schemaVersion":$schema,"exportedAtEpochMs":0}""",
            """{"schemaVersion":$schema,"exportedAtEpochMs":0,"extras":{"portable":{}}}""",
            """{"schemaVersion":$schema,"exportedAtEpochMs":0,"extras":{"portable":{"mousePointerHideSeconds":null}}}""",
        )
        for (document in documents) {
            manager(target).importJson(document)
            assertEquals(5, target.mousePointerHideSeconds)
        }
        manager(target).importJson("""{"schemaVersion":$schema,"exportedAtEpochMs":0,
            "extras":{"portable":{"mousePointerHideSeconds":-1}}}""")
        assertEquals(2, target.mousePointerHideSeconds)
    }
}
