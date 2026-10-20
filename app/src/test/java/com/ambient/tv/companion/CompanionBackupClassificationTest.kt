package com.ambient.tv.companion

import com.ambient.tv.data.local.SettingsBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionBackupClassificationTest {
    @Test fun everyBackupFieldIsClassified() {
        val d = SettingsBackup.serializer().descriptor
        val fields = (0 until d.elementsCount).map(d::getElementName).toSet()
        assertEquals(emptySet<String>(), fields - CompanionBackupPolicy.classifiedFields)
    }

    @Test fun sidebarDividersTravelWithTheMenuOrder() {
        assertTrue("sidebarDividersAfter" in CompanionBackupPolicy.classifiedFields)
    }

    @Test fun displaySwitchPauseTravelsOnlyWithDeviceSettingsFromNewBackups() {
        val json = CompanionBackupPolicy.json
        val target = SettingsBackup(exportedAtEpochMs = 1, displaySwitchPauseMs = 1_500)
        val source = SettingsBackup(exportedAtEpochMs = 2, displaySwitchPauseMs = 3_000)
        fun merged(source: SettingsBackup, device: Boolean) = json.decodeFromString<SettingsBackup>(
            CompanionBackupPolicy.merge(source, target, "preferences", overwrite = true, deviceSettings = device)).displaySwitchPauseMs
        assertTrue("displaySwitchPauseMs" in CompanionBackupPolicy.classifiedFields)
        assertEquals(3_000, merged(source, device = true))
        assertEquals(1_500, merged(source, device = false))
        assertEquals(1_500, merged(source.copy(schemaVersion = 60), device = true))
    }

    @Test fun sdrReferenceWhiteTravelsOnlyWithDeviceSettingsFromSchema65() {
        val json = CompanionBackupPolicy.json
        val target = SettingsBackup(exportedAtEpochMs = 1, sdrToHdrReferenceWhiteNits = 100)
        val source = SettingsBackup(exportedAtEpochMs = 2, sdrToHdrReferenceWhiteNits = 300)
        fun merged(source: SettingsBackup, device: Boolean) = json.decodeFromString<SettingsBackup>(
            CompanionBackupPolicy.merge(source, target, "preferences", overwrite = true, deviceSettings = device)).sdrToHdrReferenceWhiteNits
        assertTrue("sdrToHdrReferenceWhiteNits" in CompanionBackupPolicy.classifiedFields)
        assertEquals(65, SettingsBackup(exportedAtEpochMs = 0).schemaVersion)
        assertEquals(300, merged(source, device = true))
        assertEquals(100, merged(source, device = false))
        assertEquals(100, merged(source.copy(schemaVersion = 64), device = true))
    }
}
