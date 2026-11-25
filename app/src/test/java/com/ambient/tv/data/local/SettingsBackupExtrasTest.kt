package com.ambient.tv.data.local

import com.ambient.tv.ui.common.SettingsLevel
import com.ambient.tv.ui.common.SettingsLevels
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SettingsBackupExtrasTest {
    @After fun clearLiveSettingsLevel() {
        SettingsLevels.refresh(AppPreferences(memoryPreferences()))
    }

    private fun configured() = AppPreferences(memoryPreferences()).apply {
        allContinueSystems = setOf("jellyfin")
        filterBarShown = false
        launcherIntegrationEnabled = false
        settingsLevelKey = "advanced"
        autoPictureInPicture = false
        projectmShowPresetName = false
        projectmPresetShuffle = false
        gaplessMusicEnabled = false
        teletextSubtitleChoice = "150"
        lyricsVisible = false
        lyricsOnlineEnabled = true
        frameDropOverlayHistory = true
        viewTransitionStyleKey = "cinematic"
        imageCacheMb = 256
        cardImages1080 = true
        tvheadendHtspPort = 10982
    }

    private fun manager(preferences: AppPreferences) =
        SettingsBackupManager(preferences, TokenStore(memoryPreferences(), preferences))

    private fun assertConfigured(target: AppPreferences) {
        assertEquals(setOf("jellyfin"), target.allContinueSystems)
        assertFalse(target.filterBarShown)
        assertFalse(target.launcherIntegrationEnabled)
        assertEquals("advanced", target.settingsLevelKey)
        assertFalse(target.autoPictureInPicture)
        assertFalse(target.projectmShowPresetName)
        assertFalse(target.projectmPresetShuffle)
        assertFalse(target.gaplessMusicEnabled)
        assertEquals("150", target.teletextSubtitleChoice)
        assertFalse(target.lyricsVisible)
        assertTrue(target.lyricsOnlineEnabled)
        assertTrue(target.frameDropOverlayHistory)
        assertEquals("cinematic", target.viewTransitionStyleKey)
        assertEquals(256, target.imageCacheMb)
        assertTrue(target.cardImages1080)
        assertEquals(10982, target.tvheadendHtspPort)
    }

    @Test fun completeBackupRestoresActualPreferencesIncludingNonDefaultFalseValues() {
        val source = configured()
        val target = AppPreferences(memoryPreferences())
        SettingsLevels.set(target, SettingsLevel.SIMPLE)

        manager(target).importJson(manager(source).exportJson())

        // Comparing exported JSON alone cannot catch settings missing from both exports.
        assertConfigured(target)
        assertEquals(SettingsLevel.ADVANCED, SettingsLevels.current(target))
    }

    @Test fun olderMissingNullAndEmptyExtrasKeepTheReceivingChoices() {
        val documents = listOf(
            """{"schemaVersion":79,"exportedAtEpochMs":0}""",
            """{"schemaVersion":80,"exportedAtEpochMs":0}""",
            """{"schemaVersion":80,"exportedAtEpochMs":0,"extras":null}""",
            """{"schemaVersion":80,"exportedAtEpochMs":0,"extras":{}}""",
            """{"schemaVersion":80,"exportedAtEpochMs":0,"extras":{"portable":{},"device":{},"connections":{}}}""",
            """{"schemaVersion":80,"exportedAtEpochMs":0,"extras":{"portable":{"filterBarShown":null},"device":{"imageCacheMb":null},"connections":{"tvheadendHtspPort":null}}}""",
            """{"schemaVersion":79,"exportedAtEpochMs":0,"extras":{"portable":{"allContinueSystems":[],"lyricsOnlineEnabled":false},"device":{"imageCacheMb":128},"connections":{"tvheadendHtspPort":9982}}}""",
        )
        for (document in documents) {
            val target = configured()
            manager(target).importJson(document)
            assertConfigured(target)
        }
    }

    @Test fun explicitEmptyZeroAndFalseValuesReplaceTheReceiverWithoutResettingMissingFields() {
        val target = configured()
        manager(target).importJson("""{
            "schemaVersion":80,"exportedAtEpochMs":0,
            "extras":{
                "portable":{"allContinueSystems":[],"lyricsOnlineEnabled":false,"frameDropOverlayHistory":false},
                "device":{"imageCacheMb":0,"cardImages1080":false}
            }
        }""")
        assertTrue(target.allContinueSystems.isEmpty())
        assertFalse(target.lyricsOnlineEnabled)
        assertFalse(target.frameDropOverlayHistory)
        assertEquals(0, target.imageCacheMb)
        assertFalse(target.cardImages1080)
        assertEquals("advanced", target.settingsLevelKey)
        assertEquals("150", target.teletextSubtitleChoice)
        assertFalse(target.projectmPresetShuffle)
        assertEquals(10982, target.tvheadendHtspPort)
    }

    @Test fun restoredValuesUseTheSettingsNormalizers() {
        val target = configured()
        manager(target).importJson("""{
            "schemaVersion":80,"exportedAtEpochMs":0,
            "extras":{
                "portable":{"settingsLevelKey":"unknown","teletextSubtitleChoice":"9999","viewTransitionStyleKey":"unknown"},
                "device":{"imageCacheMb":9999},
                "connections":{"tvheadendHtspPort":99999}
            }
        }""")
        assertEquals("expert", target.settingsLevelKey)
        assertEquals(SettingsLevel.EXPERT, SettingsLevels.current(target))
        assertEquals("off", target.teletextSubtitleChoice)
        assertEquals("off", target.viewTransitionStyleKey)
        assertEquals(0, target.imageCacheMb)
        assertEquals(65535, target.tvheadendHtspPort)
    }

    @Test fun bundledPresetIdentifiersSurviveTheBackupWireFormat() {
        val favorites = setOf("classic/First.milk", "cream/Second.milk")
        val backup = SettingsBackup(
            schemaVersion = SettingsBackupManager.CURRENT_SCHEMA_VERSION,
            exportedAtEpochMs = 0,
            extras = SettingsBackupExtras(portable = PortableSettingsExtras(projectmFavorites = favorites)),
        )
        assertEquals(favorites, manager(configured()).previewJson(Json.encodeToString(backup)).extras?.portable?.projectmFavorites)
    }

    @Test fun moonlightBackupRestoresOnlyHostProfilesNotDeviceIdentity() {
        val source = configured().apply {
            moonlightEnabled = true
            moonlightHosts = listOf(com.ambient.tv.moonlight.MoonlightHost(
                id = "sunshine-a", host = "gaming-pc.local", port = 47989,
                name = "Gaming PC", macAddress = "AA:BB:CC:DD:EE:FF",
            ))
        }
        val target = configured()
        val receiverId = target.moonlightDeviceId
        manager(target).importJson(manager(source).exportJson(includeCredentials = true, includeSessions = true))
        assertTrue(target.moonlightEnabled)
        assertEquals(source.moonlightHosts, target.moonlightHosts)
        assertEquals(receiverId, target.moonlightDeviceId)
        assertNotEquals(source.moonlightDeviceId, target.moonlightDeviceId)
    }

    @Test fun invalidMoonlightHostsRejectBeforeChangingSettings() {
        val target = configured().apply { moonlightEnabled = false }
        val invalid = """{"schemaVersion":85,"exportedAtEpochMs":0,
            "extras":{"connections":{"moonlightEnabled":true,
             "moonlightHosts":[{"id":"bad","host":"http://attacker.invalid","port":47989}]}}}"""
        assertThrows(IllegalArgumentException::class.java) { manager(target).importJson(invalid) }
        assertFalse(target.moonlightEnabled)
        assertTrue(target.moonlightHosts.isEmpty())
    }

    @Test fun receiverActivationPairingTrustAndConsentStayOnTheReceivingDevice() {
        val source = configured()
        val target = AppPreferences(memoryPreferences()).apply {
            youtubeTvLinkEnabled = true
            dlnaRendererEnabled = true
            dlnaRendererAsk = false
            dlnaRendererName = "Living room"
            dlnaRendererTrusted = setOf("local-sender")
            hbbtvConsent = true
        }
        manager(target).importJson(manager(source).exportJson())
        assertTrue(target.youtubeTvLinkEnabled)
        assertTrue(target.dlnaRendererEnabled)
        assertFalse(target.dlnaRendererAsk)
        assertEquals("Living room", target.dlnaRendererName)
        assertEquals(setOf("local-sender"), target.dlnaRendererTrusted)
        assertTrue(target.hbbtvConsent)
    }
}
