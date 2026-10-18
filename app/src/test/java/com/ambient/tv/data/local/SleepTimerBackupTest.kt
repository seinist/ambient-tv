package com.ambient.tv.data.local

import com.ambient.tv.companion.CompanionBackupPolicy
import org.junit.Assert.*
import org.junit.Test

class SleepTimerBackupTest {
    @Test fun sleepDefaultsSurviveBackupAndOldBackupsRestoreDefaults() {
        val source = AppPreferences(memoryPreferences())
        source.sleepAutoStartKey = "90"
        source.sleepWarningMinutes = 5
        source.sleepDailyStopMinute = 60
        val exported = SettingsBackupManager(source, TokenStore(memoryPreferences(), source)).exportJson()
        val target = AppPreferences(memoryPreferences())
        val manager = SettingsBackupManager(target, TokenStore(memoryPreferences(), target))
        manager.importJson(exported)
        assertEquals("90", target.sleepAutoStartKey)
        assertEquals(5, target.sleepWarningMinutes)
        assertEquals(60, target.sleepDailyStopMinute)
        manager.importJson("""{"schemaVersion":62,"exportedAtEpochMs":0,"sleepAutoStartKey":"30","sleepDailyStopMinute":90}""")
        assertEquals("30", target.sleepAutoStartKey)
        assertEquals(AppPreferences.SLEEP_DAILY_STOP_OFF, target.sleepDailyStopMinute)
        manager.importJson("""{"schemaVersion":61,"exportedAtEpochMs":0}""")
        assertEquals(AppPreferences.SLEEP_AUTO_START_OFF, target.sleepAutoStartKey)
        assertEquals(AppPreferences.SLEEP_WARNING_DEFAULT, target.sleepWarningMinutes)
    }

    @Test fun invalidStoredValuesFallBack() {
        val prefs = AppPreferences(memoryPreferences())
        prefs.sleepAutoStartKey = "45"
        prefs.sleepWarningMinutes = 3
        assertEquals(AppPreferences.SLEEP_AUTO_START_OFF, prefs.sleepAutoStartKey)
        assertEquals(AppPreferences.SLEEP_WARNING_DEFAULT, prefs.sleepWarningMinutes)
        prefs.sleepDailyStopMinute = 1440
        assertEquals(AppPreferences.SLEEP_DAILY_STOP_OFF, prefs.sleepDailyStopMinute)
        prefs.sleepDailyStopMinute = 0
        assertEquals(0, prefs.sleepDailyStopMinute)
    }

    @Test fun companionCopiesSleepDefaultsOnlyFromSchema62() {
        val json = CompanionBackupPolicy.json
        val target = SettingsBackup(exportedAtEpochMs = 1)
        val source = SettingsBackup(exportedAtEpochMs = 2, sleepAutoStartKey = "60", sleepWarningMinutes = 2)
        fun merged(source: SettingsBackup) = json.decodeFromString<SettingsBackup>(
            CompanionBackupPolicy.merge(source, target, "preferences", overwrite = true, deviceSettings = false))
        assertEquals("60", merged(source).sleepAutoStartKey)
        assertEquals(2, merged(source).sleepWarningMinutes)
        assertEquals(AppPreferences.SLEEP_AUTO_START_OFF, merged(source.copy(schemaVersion = 61)).sleepAutoStartKey)
    }

    @Test fun companionCopiesDailyStopOnlyFromSchema63() {
        val json = CompanionBackupPolicy.json
        val target = SettingsBackup(exportedAtEpochMs = 1)
        val source = SettingsBackup(exportedAtEpochMs = 2, sleepDailyStopMinute = 60)
        fun merged(source: SettingsBackup) = json.decodeFromString<SettingsBackup>(
            CompanionBackupPolicy.merge(source, target, "preferences", overwrite = true, deviceSettings = false)).sleepDailyStopMinute
        assertEquals(60, merged(source))
        assertEquals(AppPreferences.SLEEP_DAILY_STOP_OFF, merged(source.copy(schemaVersion = 62)))
    }
}
