package com.ambient.tv.companion

import com.ambient.tv.data.local.SettingsBackup
import com.ambient.tv.data.local.MonogramApiBackup
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
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
        assertEquals(67, SettingsBackup(exportedAtEpochMs = 0).schemaVersion)
        assertEquals(300, merged(source, device = true))
        assertEquals(100, merged(source, device = false))
        assertEquals(100, merged(source.copy(schemaVersion = 64), device = true))
    }

    @Test fun gpuOutputChoicesTravelWithDeviceSettingsFromSchema66() {
        val json = CompanionBackupPolicy.json
        val target = SettingsBackup(exportedAtEpochMs = 1)
        val source = SettingsBackup(exportedAtEpochMs = 2, allowHighRefreshDoubling = true,
            gpuRenderResolutionKey = "ui", mpvSdrCurveKey = "bt.1886", svpExplicitChoice = true)
        fun merged(source: SettingsBackup, device: Boolean) = json.decodeFromString<SettingsBackup>(
            CompanionBackupPolicy.merge(source, target, "preferences", overwrite = true, deviceSettings = device))
        listOf("allowHighRefreshDoubling", "gpuRenderResolutionKey", "mpvSdrCurveKey", "svpExplicitChoice")
            .forEach { assertTrue(it in CompanionBackupPolicy.classifiedFields) }
        val device = merged(source, device = true)
        assertEquals("ui", device.gpuRenderResolutionKey)
        assertEquals("bt.1886", device.mpvSdrCurveKey)
        assertTrue(device.allowHighRefreshDoubling && device.svpExplicitChoice)
        assertEquals("auto", merged(source, device = false).gpuRenderResolutionKey)
        assertEquals("auto", merged(source.copy(schemaVersion = 65), device = true).mpvSdrCurveKey)
    }

    // B1
    @Test fun youtubeVideoPresetFromOlderBackupsDoesNotOverwriteTheReceiver() {
        val json = CompanionBackupPolicy.json
        val target = SettingsBackup(exportedAtEpochMs = 1, youtubeVideoPreset = "2160:60:vp9")
        val source = SettingsBackup(exportedAtEpochMs = 2, youtubeVideoPreset = "auto")
        fun merged(source: SettingsBackup) = json.decodeFromString<SettingsBackup>(
            CompanionBackupPolicy.merge(source, target, "preferences", overwrite = true, deviceSettings = true))
        assertEquals("2160:60:vp9", merged(source.copy(schemaVersion = 65)).youtubeVideoPreset)
        assertEquals("auto", merged(source).youtubeVideoPreset)
    }

    @Test fun syncPlaybackToDisplayTravelsOnlyWithDeviceSettingsFromSchema67() {
        val json = CompanionBackupPolicy.json
        val target = SettingsBackup(exportedAtEpochMs = 1, syncPlaybackToDisplay = false)
        val source = SettingsBackup(exportedAtEpochMs = 2, syncPlaybackToDisplay = true)
        fun merged(source: SettingsBackup, device: Boolean) = json.decodeFromString<SettingsBackup>(
            CompanionBackupPolicy.merge(source, target, "preferences", overwrite = true, deviceSettings = device)).syncPlaybackToDisplay
        assertTrue("syncPlaybackToDisplay" in CompanionBackupPolicy.classifiedFields)
        assertEquals(67, SettingsBackup(exportedAtEpochMs = 0).schemaVersion)
        assertTrue(merged(source, device = true))
        assertFalse(merged(source, device = false))
        assertFalse(merged(source.copy(schemaVersion = 66), device = true))
    }
    @Test fun monogramApiCredentialsAreAccountOnlyAndNeverSyncedWithoutConsent() {
        val id = MonogramApiBackup(123456, "0123456789abcdef0123456789abcdef")
        val sender = SettingsBackup(schemaVersion = 76, exportedAtEpochMs = 12,
            monogramApiCredentials = id)
        fun credentials(json: String) = Json.parseToJsonElement(json).jsonObject["monogramApiCredentials"]

        assertTrue("monogramApiCredentials" in CompanionBackupPolicy.classifiedFields)
        assertEquals(JsonNull, credentials(CompanionBackupPolicy.prepare(sender, false, "accounts")))
        assertEquals(null, credentials(CompanionBackupPolicy.prepare(sender, false, "preferences")))
        assertEquals(null, credentials(CompanionBackupPolicy.prepare(sender, true, "preferences")))
        assertTrue(credentials(CompanionBackupPolicy.prepare(sender, true, "accounts")) != JsonNull)

        val fresh = SettingsBackup(schemaVersion = 76, exportedAtEpochMs = 11)
        val accepted = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(
            CompanionBackupPolicy.merge(sender, fresh, "accounts", overwrite = true, deviceSettings = false))
        assertEquals(id, accepted.monogramApiCredentials)

        val existing = fresh.copy(monogramApiCredentials = MonogramApiBackup(777, "a".repeat(32)))
        val protected = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(
            CompanionBackupPolicy.merge(sender, existing, "accounts", overwrite = true, deviceSettings = false))
        assertEquals(existing.monogramApiCredentials, protected.monogramApiCredentials)
    }

}
