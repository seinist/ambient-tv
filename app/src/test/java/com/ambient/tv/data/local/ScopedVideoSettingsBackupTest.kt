package com.ambient.tv.data.local

import com.ambient.tv.companion.CompanionBackupPolicy
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class ScopedVideoSettingsBackupTest {
    private val title = mapOf("plex:3:srv:42" to TitleVideoSettings(rendererKey = "mpv", savedAt = 2))
    private val scoped = mapOf("season:plex:3:srv:7" to TitleVideoSettings(audioDelayMs = 50, savedAt = 3))

    @Test fun titleAndScopedProfilesSurviveABackupAndOldBackupsKeepThem() {
        val source = AppPreferences(memoryPreferences())
        source.titleVideoSettings = title
        source.scopedVideoSettings = scoped
        val exported = SettingsBackupManager(source, TokenStore(memoryPreferences(), source)).exportJson()
        val target = AppPreferences(memoryPreferences())
        val manager = SettingsBackupManager(target, TokenStore(memoryPreferences(), target))
        manager.importJson(exported)
        assertEquals(title, target.titleVideoSettings)
        assertEquals(scoped, target.scopedVideoSettings)
        manager.importJson("""{"schemaVersion":68,"exportedAtEpochMs":0}""")
        assertEquals(title, target.titleVideoSettings)
        assertEquals(scoped, target.scopedVideoSettings)
    }

    @Test fun restoreDropsMalformedKeysAndTitleOnlyFieldsInScopes() {
        val restoredTitles = restorableTitleVideoSettings(title + mapOf("" to TitleVideoSettings(rendererKey = "vlc"),
            "season:plex:0::1" to TitleVideoSettings(rendererKey = "vlc"), "plex:1" to TitleVideoSettings()))
        assertEquals(title, restoredTitles)
        val restoredScopes = restorableScopedVideoSettings(mapOf("plex:1" to TitleVideoSettings(rendererKey = "vlc"),
            "series:plex:0::9" to TitleVideoSettings(rendererKey = "mpv", mpvOverloadContinueSignature = "x", snapshot = true)))
        assertEquals(mapOf("series:plex:0::9" to TitleVideoSettings(rendererKey = "mpv")), restoredScopes)
        val many = (1..TitleVideoSettings.MAX_TITLES + 5).associate { "plex:$it" to TitleVideoSettings(rendererKey = "mpv", savedAt = it.toLong()) }
        assertEquals(TitleVideoSettings.MAX_TITLES, restorableTitleVideoSettings(many).size)
        assertFalse("plex:1" in restorableTitleVideoSettings(many))
    }

    @Test fun companionCarriesProfilesOnlyFromSchema69AsDeviceSettings() {
        val incoming = SettingsBackup(schemaVersion = 69, exportedAtEpochMs = 2, titleVideoSettings = title, scopedVideoSettings = scoped)
        val target = SettingsBackup(schemaVersion = 69, exportedAtEpochMs = 1)
        val merged = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(incoming, target, "settings", true, true))
        assertEquals(title, merged.titleVideoSettings)
        assertEquals(scoped, merged.scopedVideoSettings)
        val old = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(
            incoming.copy(schemaVersion = 68), target.copy(titleVideoSettings = mapOf("plex:1" to TitleVideoSettings(rendererKey = "vlc"))), "settings", true, true))
        assertEquals(mapOf("plex:1" to TitleVideoSettings(rendererKey = "vlc")), old.titleVideoSettings)
        val withoutDevice = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(incoming, target, "settings", true, false))
        assertTrue(withoutDevice.titleVideoSettings.isEmpty())
    }
}
