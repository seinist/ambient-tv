package com.ambient.tv.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class UiRenderResolutionPreferencesTest {
    @Test fun defaultsAndPersistenceStayIndependentOfUiScaleAndVideoResolution() {
        val storage = memoryPreferences()
        val preferences = AppPreferences(storage)
        assertEquals("system", preferences.uiRenderResolutionKey)
        preferences.uiScale = .9f
        preferences.gpuRenderResolutionKey = "panel"
        preferences.uiHdrModeKey = "force"
        preferences.sdrToHdrEnabled = true
        for (key in listOf("1080p", "panel", "system")) {
            preferences.uiRenderResolutionKey = key
            val restarted = AppPreferences(storage)
            assertEquals(key, restarted.uiRenderResolutionKey)
            assertEquals("system", UiRenderResolutionPolicy.effectiveKey(restarted.uiRenderResolutionKey, true, 30))
            assertEquals(key, UiRenderResolutionPolicy.effectiveKey(restarted.uiRenderResolutionKey, true, 31))
            assertEquals(.9f, restarted.uiScale, 0f)
            assertEquals("panel", restarted.gpuRenderResolutionKey)
            assertEquals("force", restarted.uiHdrModeKey)
            assertTrue(restarted.sdrToHdrEnabled)
        }
        preferences.uiRenderResolutionKey = "4k"
        assertEquals("system", AppPreferences(storage).uiRenderResolutionKey)
        storage.edit().putString("ui_render_resolution", "obsolete").apply()
        assertEquals("system", AppPreferences(storage).uiRenderResolutionKey)
    }

    @Test fun currentBackupsRoundTripUiResolutionIndependentlyOfVideoResolution() {
        val source = AppPreferences(memoryPreferences()).apply {
            uiRenderResolutionKey = "1080p"
            gpuRenderResolutionKey = "panel"
        }
        val exported = SettingsBackupManager(source, TokenStore(memoryPreferences(), source)).exportJson()
        val data = Json.parseToJsonElement(exported).jsonObject
        assertEquals(SettingsBackupManager.CURRENT_SCHEMA_VERSION.toString(), data.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("1080p", data.getValue("uiRenderResolutionKey").jsonPrimitive.content)
        val target = AppPreferences(memoryPreferences())
        SettingsBackupManager(target, TokenStore(memoryPreferences(), target)).importJson(exported)
        assertEquals("1080p", target.uiRenderResolutionKey)
        assertEquals("panel", target.gpuRenderResolutionKey)
    }

    @Test fun oldBackupsKeepTheDeviceChoiceAndInvalidChoicesFallBackToSystem() {
        val preferences = AppPreferences(memoryPreferences())
        val manager = SettingsBackupManager(preferences, TokenStore(memoryPreferences(), preferences))
        preferences.uiRenderResolutionKey = "panel"
        manager.importJson("""{"schemaVersion":76,"exportedAtEpochMs":0}""")
        assertEquals("panel", preferences.uiRenderResolutionKey)
        manager.importJson("""{"schemaVersion":77,"exportedAtEpochMs":0,"uiRenderResolutionKey":"4k"}""")
        assertEquals("system", preferences.uiRenderResolutionKey)
        preferences.uiRenderResolutionKey = "1080p"
        manager.importJson("""{"schemaVersion":77,"exportedAtEpochMs":0}""")
        assertEquals("system", preferences.uiRenderResolutionKey)
    }

    @Test fun unsupportedBackupSchemaIsRejectedBeforeWritingUiResolution() {
        val preferences = AppPreferences(memoryPreferences()).apply { uiRenderResolutionKey = "panel" }
        val manager = SettingsBackupManager(preferences, TokenStore(memoryPreferences(), preferences))
        assertTrue(runCatching {
            manager.importJson("""{"schemaVersion":${SettingsBackupManager.CURRENT_SCHEMA_VERSION + 1},"exportedAtEpochMs":0,"uiRenderResolutionKey":"1080p"}""")
        }.isFailure)
        assertEquals("panel", preferences.uiRenderResolutionKey)
    }

    @Test fun backupConstructorsStayWithinTheJvmParameterLimit() {
        SettingsBackup::class.java.declaredConstructors.forEach { constructor ->
            val slots = 1 + constructor.parameterTypes.sumOf { type ->
                if (type == java.lang.Long.TYPE || type == java.lang.Double.TYPE) 2 else 1
            }
            assertTrue("SettingsBackup constructor uses $slots of 255 parameter slots", slots <= 255)
        }
    }
}
