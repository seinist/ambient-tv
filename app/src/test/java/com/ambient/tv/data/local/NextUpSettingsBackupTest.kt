package com.ambient.tv.data.local

import org.junit.Assert.*
import org.junit.Test

class NextUpSettingsBackupTest {
    private fun manager(preferences: AppPreferences) =
        SettingsBackupManager(preferences, TokenStore(memoryPreferences(), preferences))

    @Test fun minimalLayoutIsOptInAndRoundTripsWithoutChangingAutoplay() {
        val storage = memoryPreferences()
        val source = AppPreferences(storage).apply {
            nextUpAutoplayEpisodes = true
            nextUpLeadSeconds = 60
        }
        assertFalse(source.nextUpMinimalEnabled)
        val target = AppPreferences(memoryPreferences())
        for (minimal in listOf(true, false)) {
            source.nextUpMinimalEnabled = minimal
            assertEquals(minimal, AppPreferences(storage).nextUpMinimalEnabled)
            manager(target).importJson(manager(source).exportJson())
            assertEquals(minimal, target.nextUpMinimalEnabled)
            assertTrue(target.nextUpAutoplayEpisodes)
            assertEquals(60, target.nextUpLeadSeconds)
        }
    }

    @Test fun olderAndPartialBackupsKeepTheExistingLayoutChoice() {
        val preferences = AppPreferences(memoryPreferences())
        val backups = manager(preferences)
        val legacy = """{"schemaVersion":79,"exportedAtEpochMs":0}"""
        backups.importJson(legacy)
        assertFalse(preferences.nextUpMinimalEnabled)
        preferences.nextUpMinimalEnabled = true
        backups.importJson(legacy)
        assertTrue(preferences.nextUpMinimalEnabled)
        backups.importJson("""{"schemaVersion":84,"exportedAtEpochMs":0,
            "extras":{"portable":{"lyricsVisible":false}}}""")
        assertTrue(preferences.nextUpMinimalEnabled)
        assertFalse(preferences.lyricsVisible)
    }
}
