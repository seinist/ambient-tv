package com.ambient.tv.data.local

import com.ambient.tv.domain.model.ServerProfile
import com.ambient.tv.domain.model.JellyfinServerProfile
import com.ambient.tv.domain.model.EmbyServerProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SettingsBackupRegressionTest {
    private fun store(): Triple<AppPreferences, TokenStore, SettingsBackupManager> {
        val prefs = AppPreferences(memoryPreferences())
        val tokens = TokenStore(memoryPreferences(), prefs)
        return Triple(prefs, tokens, SettingsBackupManager(prefs, tokens))
    }

    @Test fun oneFocusSnapshotPersistsItemAndRowAndClearsOldRow() {
        val prefs = AppPreferences(memoryPreferences())
        prefs.setSectionFocusSnapshot("library", "item-a", "row-b")
        assertEquals("item-a", prefs.sectionFocus("library"))
        assertEquals("row-b", prefs.sectionFocusRow("library"))
        prefs.setSectionFocusSnapshot("library", "item-c", null)
        assertEquals("item-c", prefs.sectionFocus("library"))
        assertNull(prefs.sectionFocusRow("library"))
    }

    @Test fun focusIsRememberedOnlyForTheSession() {
        val raw = memoryPreferences()
        val prefs = AppPreferences(raw)
        prefs.setSectionFocus("library", "item-a")
        prefs.setSectionFocus("library", "item-b")
        assertEquals("item-b", prefs.sectionFocus("library"))
        assertNull(raw.getString("section_focus_library", null))
        assertNull(prefs.portableLibraryPreferences()["section_focus_library"])
        assertNull(AppPreferences(raw).sectionFocus("library"))
    }

    @Test fun autoDisplayPoliciesNormalizeOffForDirectWritesAndLegacyPreferences() {
        for (mode in listOf(AppPreferences.DISPLAY_MODE_AUTO_CONTENT, AppPreferences.DISPLAY_MODE_AUTO_DISPLAY)) {
            val raw = memoryPreferences()
            val prefs = AppPreferences(raw)
            prefs.displayRefreshAdjustmentKey = AppPreferences.DISPLAY_REFRESH_OFF
            prefs.preferredDisplayModeKey = mode
            assertEquals(AppPreferences.DISPLAY_REFRESH_START_STOP, prefs.displayRefreshAdjustmentKey)
            assertEquals(mode, prefs.preferredDisplayModeKey)
            prefs.displayRefreshAdjustmentKey = AppPreferences.DISPLAY_REFRESH_OFF
            assertEquals("system", prefs.preferredDisplayModeKey)
            raw.edit().putString(AppPreferences.KEY_PREFERRED_DISPLAY_MODE, mode)
                .putString(AppPreferences.KEY_DISPLAY_REFRESH_ADJUSTMENT, AppPreferences.DISPLAY_REFRESH_OFF).apply()
            assertEquals(AppPreferences.DISPLAY_REFRESH_START_STOP, prefs.displayRefreshAdjustmentKey)
        }
    }

    @Test fun legacyAutoOffBackupImportsAsWorkingStartStopPolicy() {
        val (target, _, manager) = store()
        // Import applies adjustment before preferred mode, as in existing backups.
        manager.importJson("""{"schemaVersion":49,"exportedAtEpochMs":0,"displayRefreshAdjustmentKey":"off","preferredDisplayModeKey":"auto_content"}""")
        assertEquals("auto_content", target.preferredDisplayModeKey)
        assertEquals(AppPreferences.DISPLAY_REFRESH_START_STOP, target.displayRefreshAdjustmentKey)
    }

    @Test fun youtubeMediaSelectionIsIndependentAndDefaultsToPlay() {
        val prefs = AppPreferences(memoryPreferences())
        assertEquals(MediaSelectionPreferences.DETAILS, prefs.mediaSelectionBehaviorKey)
        assertEquals(MediaSelectionPreferences.PLAY, prefs.youtubeMediaSelectionBehaviorKey)
        prefs.mediaSelectionBehaviorKey = MediaSelectionPreferences.ASK
        assertEquals(MediaSelectionPreferences.PLAY, prefs.youtubeMediaSelectionBehaviorKey)
        prefs.youtubeMediaSelectionBehaviorKey = MediaSelectionPreferences.DETAILS
        assertEquals(MediaSelectionPreferences.ASK, prefs.mediaSelectionBehaviorKey)
        assertEquals(MediaSelectionPreferences.DETAILS, prefs.youtubeMediaSelectionBehaviorKey)
    }

    @Test fun rewindDefaultsStayOffAndOlderBackupsKeepTheDevicesCurrentChoice() {
        val (preferences, _, importer) = store()
        assertEquals(0, preferences.pauseRewindSeconds)
        assertEquals(10, preferences.pauseRewindMinPauseSeconds)
        assertEquals(0, preferences.resumeRewindSeconds)
        preferences.pauseRewindSeconds = 5
        preferences.pauseRewindMinPauseSeconds = 30
        preferences.resumeRewindSeconds = 15
        importer.importJson("""{"schemaVersion":73,"exportedAtEpochMs":0}""")
        assertEquals(5, preferences.pauseRewindSeconds)
        assertEquals(30, preferences.pauseRewindMinPauseSeconds)
        assertEquals(15, preferences.resumeRewindSeconds)
    }

    @Test fun currentBackupRoundTripsNewSettingsAndEmptyHomeLayout() {
        val (source, _, exporter) = store()
        source.homeRows = emptyList()
        source.visibleBackgroundVideoPlayback = true
        source.backgroundVideoDimPercent = 90
        source.sidebarSystemOrder = listOf("tvheadend", "spotify", "youtube", "jellyfin", "plex")
        source.systemNames = mapOf("plex" to "Movies", "tvheadend" to "Live TV")
        source.plexHiddenSectionKeys = setOf("2", "9")
        source.sidebarSettingsShortcutHidden = setOf("emby", "files")
        source.systemHomeHiddenRows = setOf("emby.watched")
        source.mpvScalingFilterKey = "nnedi3_nns32"
        source.mpvDebandEnabled = false
        source.vlcScalingFilterKey = "nearest"
        source.playerTechBadgesVisible = false
        source.performanceModeKey = "low"
        source.uiPerformanceModeKey = "maximum"
        source.playbackPerformanceModeKey = "balanced"
        source.homeBackgroundEverywhere = true
        source.homeHeroStyleKey = HomeHeroStyle.SWEEP
        source.homeHeroIntensityPercent = 80
        source.homeHeroSpreadKey = HomeHeroStyle.WIDE
        source.notificationDisplayDurationKey = "long"
        source.youtubeAutoplayNextEnabled = true
        source.youtubeMediaSelectionBehaviorKey = MediaSelectionPreferences.DETAILS
        source.introSkipMode = "auto"
        source.creditsSkipMode = "off"
        source.nextUpCreditsDelaySeconds = 15
        source.nextUpEnabled = false
        source.nextUpAutoplayEpisodes = true
        source.nextUpAutoplayCollections = true
        source.spotifySubmenuKeys = listOf("liked")
        source.tvheadendSubmenuKeys = listOf("guide", "timers")
        source.wireGuardTvheadendEnabled = true
        source.plexThemeMusicEnabled = true
        source.embyThemeMusicEnabled = true
        source.navSoundsEnabled = true
        source.navSoundsVolume = 0.45f
        source.tvheadendUseHttps = true
        source.tvheadendDeinterlaceFilterKey = "phosphor"
        source.tvheadendMpvDeinterlaceFilterKey = "mcdeint"
        source.displayChangeNotificationsEnabled = true
        source.svpAvailable = true
        source.displayRefreshAdjustmentKey = "always"
        source.displayModeWhitelist = setOf("1920x1080@23.976", "3840x2160@59.940")
        source.preferredDisplayModeKey = "3840x2160@59.940"
        source.allowDoubleRefreshRate = false
        source.allowThreeTwoPulldown = true
        source.pauseRewindSeconds = 5
        source.pauseRewindMinPauseSeconds = 10
        source.resumeRewindSeconds = 30
        val raw = exporter.exportJson()
        val (target, _, importer) = store()
        importer.importJson(raw)
        assertTrue(target.homeRows.isEmpty())
        assertEquals(setOf("emby.watched"), target.systemHomeHiddenRows)
        assertEquals(setOf("emby", "files"), target.sidebarSettingsShortcutHidden)
        assertTrue(target.plexThemeMusicEnabled)
        assertFalse(target.jellyfinThemeMusicEnabled)
        assertTrue(target.embyThemeMusicEnabled)
        assertTrue(target.navSoundsEnabled)
        assertEquals(0.45f, target.navSoundsVolume, 0.0001f)
        assertTrue(target.tvheadendUseHttps)
        assertEquals("phosphor", target.tvheadendDeinterlaceFilterKey)
        assertEquals("mcdeint", target.tvheadendMpvDeinterlaceFilterKey)
        assertTrue(target.displayChangeNotificationsEnabled)
        // Device-specific values are exported but stay with the receiving device.
        assertFalse(target.svpAvailable)
        assertEquals("always", target.displayRefreshAdjustmentKey)
        assertTrue(target.displayModeWhitelist.isEmpty())
        assertEquals("system", target.preferredDisplayModeKey)
        assertFalse(target.allowDoubleRefreshRate)
        assertTrue(target.allowThreeTwoPulldown)
        assertEquals(5, target.pauseRewindSeconds)
        assertEquals(10, target.pauseRewindMinPauseSeconds)
        assertEquals(30, target.resumeRewindSeconds)
        assertEquals("auto", target.introSkipMode)
        assertEquals("off", target.creditsSkipMode)
        assertEquals(15, target.nextUpCreditsDelaySeconds)
        assertFalse(target.playerTechBadgesVisible)
        assertEquals("low", target.performanceModeKey)
        assertEquals("maximum", target.uiPerformanceModeKey)
        assertEquals("balanced", target.playbackPerformanceModeKey)
        assertEquals("maximum", target.effectiveUiPerformanceModeKey())
        assertEquals("balanced", target.effectivePlaybackPerformanceModeKey())
        assertTrue(target.homeBackgroundEverywhere)
        assertEquals(HomeHeroStyle.SWEEP, target.homeHeroStyleKey)
        assertEquals(80, target.homeHeroIntensityPercent)
        assertEquals(HomeHeroStyle.WIDE, target.homeHeroSpreadKey)
        assertEquals("long", target.notificationDisplayDurationKey)
        assertEquals(mapOf("plex" to "Movies", "tvheadend" to "Live TV"), target.systemNames)
        // Compare all exported settings, not just the first few fields of the schema.
        // Device-specific values stay with the receiving device and are not compared.
        val deviceSpecific = setOf("exportedAtEpochMs", "svpAvailable", "displayModeWhitelist", "preferredDisplayModeKey")
        val before = Json.parseToJsonElement(raw).jsonObject - deviceSpecific
        val after = Json.parseToJsonElement(importer.exportJson()).jsonObject - deviceSpecific
        assertEquals(before, after)
    }

    @Test fun recentTvRowAndDimSettingRoundTripWithoutWatchHistory() {
        val (source, _, exporter) = store()
        source.homeRows = listOf(HomeRowDefinition(system = "tvheadend", contentKey = "recent_channels"))
        source.tvheadendEnabled = true
        source.tvheadendHost = "nas.local"
        source.recordWatchedTvChannel(source.tvheadendHistoryScope, "private-channel-id")
        source.backgroundVideoDimPercent = 60
        val raw = exporter.exportJson()
        assertFalse(raw.contains("private-channel-id"))
        val (target, _, importer) = store()
        importer.importJson(raw)
        assertEquals("recent_channels", target.homeRows.single().contentKey)
        assertEquals(60, target.backgroundVideoDimPercent)
        assertTrue(target.recentTvChannelIds.isEmpty())
    }

    @Test fun legacyPerformanceModeRemainsTheDefaultUntilEachSideIsSaved() {
        val prefs = AppPreferences(memoryPreferences())
        prefs.performanceModeKey = "maximum"
        assertEquals("maximum", prefs.effectiveUiPerformanceModeKey())
        assertEquals("maximum", prefs.effectivePlaybackPerformanceModeKey())
        prefs.uiPerformanceModeKey = "low"
        assertEquals("low", prefs.effectiveUiPerformanceModeKey())
        assertEquals("maximum", prefs.effectivePlaybackPerformanceModeKey())
        prefs.playbackPerformanceModeKey = "balanced"
        assertEquals("balanced", prefs.effectivePlaybackPerformanceModeKey())
        assertEquals("maximum", prefs.performanceModeKey)
    }

    @Test fun gpuOutputChoicesRoundTripAndOlderBackupsUseDefaults() {
        val (source, _, exporter) = store()
        source.allowHighRefreshDoubling = true
        source.gpuRenderResolutionKey = "panel"
        source.mpvSdrCurveKey = "gamma2.2"
        source.svpExplicitChoice = true
        val json = exporter.exportJson()
        val (target, _, importer) = store()
        importer.importJson(json)
        assertEquals(true, target.allowHighRefreshDoubling)
        assertEquals("panel", target.gpuRenderResolutionKey)
        assertEquals("gamma2.2", target.mpvSdrCurveKey)
        assertEquals(true, target.svpExplicitChoice)
        importer.importJson("""{"schemaVersion":65,"exportedAtEpochMs":0,"gpuRenderResolutionKey":"ui","allowHighRefreshDoubling":true}""")
        assertEquals(false, target.allowHighRefreshDoubling)
        assertEquals("auto", target.gpuRenderResolutionKey)
        assertEquals("auto", target.mpvSdrCurveKey)
        assertEquals(false, target.svpExplicitChoice)
    }

    // P1
    @Test fun mpvImageExplicitChoiceRoundTripsAndOlderBackupsRestoreNotChosen() {
        val (source, _, exporter) = store()
        source.mpvImageExplicitChoice = true
        val (target, _, importer) = store()
        importer.importJson(exporter.exportJson())
        assertTrue(target.mpvImageExplicitChoice)
        importer.importJson("""{"schemaVersion":67,"exportedAtEpochMs":0,"mpvImageExplicitChoice":true}""")
        assertFalse(target.mpvImageExplicitChoice)
    }

    // B1
    @Test fun youtubeVideoPresetRoundTripsAndOlderBackupsKeepTheDevicePreset() {
        val (source, _, exporter) = store()
        source.youtubeVideoPreset = "1080:60:vp9"
        val (target, _, importer) = store()
        importer.importJson(exporter.exportJson())
        assertEquals("1080:60:vp9", target.youtubeVideoPreset)
        // A schema-65 backup has no preset: its default must not reset the chosen one.
        importer.importJson("""{"schemaVersion":65,"exportedAtEpochMs":0}""")
        assertEquals("1080:60:vp9", target.youtubeVideoPreset)
    }

    @Test fun doubleRefreshRateIsPreferredByDefaultAndOlderBackupsKeepIt() {
        val (target, _, importer) = store()
        assertTrue(target.preferDoubleRefreshRate)
        importer.importJson("""{"schemaVersion":70,"exportedAtEpochMs":0}""")
        assertTrue(target.preferDoubleRefreshRate)
        importer.importJson("""{"schemaVersion":72,"exportedAtEpochMs":0,"preferDoubleRefreshRate":false}""")
        assertFalse(target.preferDoubleRefreshRate)
    }

    @Test fun backBehaviourAndNetworkBufferRoundTripFromSchema73() {
        val (source, _, exporter) = store()
        source.playerBackMinimizes = true
        source.networkBufferKey = "high"
        val (target, _, importer) = store()
        importer.importJson(exporter.exportJson())
        assertTrue(target.playerBackMinimizes)
        assertEquals("high", target.networkBufferKey)
        // An older backup has neither field: the chosen values stay.
        importer.importJson("""{"schemaVersion":72,"exportedAtEpochMs":0}""")
        assertTrue(target.playerBackMinimizes)
        assertEquals("high", target.networkBufferKey)
    }

    @Test fun sdrReferenceWhiteRoundTripsAndOlderBackupsUseAuto() {
        val (source, _, exporter) = store()
        source.sdrToHdrReferenceWhiteNits = 300
        val json = exporter.exportJson()
        assertEquals("75", Json.parseToJsonElement(json).jsonObject["schemaVersion"].toString())
        val (target, _, importer) = store()
        importer.importJson(json)
        assertEquals(300, target.sdrToHdrReferenceWhiteNits)
        importer.importJson("""{"schemaVersion":64,"exportedAtEpochMs":0,"sdrToHdrReferenceWhiteNits":400}""")
        assertEquals(0, target.sdrToHdrReferenceWhiteNits)
        target.sdrToHdrReferenceWhiteNits = 5000
        assertEquals(600, target.sdrToHdrReferenceWhiteNits)
        target.sdrToHdrReferenceWhiteNits = -3
        assertEquals(0, target.sdrToHdrReferenceWhiteNits)
    }

    @Test fun syncPlaybackToDisplayRoundTripsAndOlderBackupsRestoreOff() {
        val (source, _, exporter) = store()
        assertFalse(source.syncPlaybackToDisplay)
        source.syncPlaybackToDisplay = true
        val json = exporter.exportJson()
        val (target, _, importer) = store()
        importer.importJson(json)
        assertTrue(target.syncPlaybackToDisplay)
        importer.importJson("""{"schemaVersion":66,"exportedAtEpochMs":0,"syncPlaybackToDisplay":true}""")
        assertFalse(target.syncPlaybackToDisplay)
    }

    @Test fun oldBackupDoesNotClearSplitPerformanceModes() {
        val (prefs, _, importer) = store()
        prefs.uiPerformanceModeKey = "low"
        prefs.playbackPerformanceModeKey = "maximum"
        importer.importJson("""{"schemaVersion":48,"exportedAtEpochMs":0}""")
        assertEquals("low", prefs.uiPerformanceModeKey)
        assertEquals("maximum", prefs.playbackPerformanceModeKey)
    }

    @Test fun oldBackupKeepsPlayerTechBadgesVisible() {
        val (prefs, _, importer) = store()
        prefs.playerTechBadgesVisible = false
        importer.importJson("""{"schemaVersion":39,"exportedAtEpochMs":0}""")
        assertTrue(prefs.playerTechBadgesVisible)
    }

    @Test fun oldSharedDeinterlaceSettingMigratesOnImport() {
        val (prefs, _, importer) = store()
        prefs.tvheadendMpvDeinterlaceFilterKey = "nnedi"
        importer.importJson("""{"exportedAtEpochMs":0,"tvheadendDeinterlaceFilterKey":"bob"}""")
        assertEquals("bob", prefs.tvheadendDeinterlaceFilterKey)
        assertEquals("yadif2x", prefs.tvheadendMpvDeinterlaceFilterKey)
    }

    @Test fun oldBackupKeepsPerformanceModeAuto() {
        val (prefs, _, importer) = store()
        prefs.performanceModeKey = "low"
        importer.importJson("""{"schemaVersion":40,"exportedAtEpochMs":0}""")
        assertEquals("auto", prefs.performanceModeKey)
    }

    @Test fun homeHeroStyleNormalizesAndOldBackupsUseTheNewDefault() {
        val (prefs, _, importer) = store()
        prefs.homeHeroStyleKey = "invalid"
        assertEquals(HomeHeroStyle.DEFAULT, prefs.homeHeroStyleKey)
        prefs.homeHeroStyleKey = HomeHeroStyle.SWEEP
        importer.importJson("""{"schemaVersion":47,"exportedAtEpochMs":0}""")
        assertEquals(HomeHeroStyle.DEFAULT, prefs.homeHeroStyleKey)
    }

    @Test fun olderBackupDefaultsHeroLightAndPreferencesNormalizeInvalidValues() {
        val (prefs, _, importer) = store()
        prefs.homeHeroIntensityPercent = 200
        prefs.homeHeroSpreadKey = "invalid"
        assertEquals(100, prefs.homeHeroIntensityPercent)
        assertEquals(HomeHeroStyle.SOFT, prefs.homeHeroSpreadKey)
        importer.importJson("""{"schemaVersion":49,"exportedAtEpochMs":0}""")
        assertEquals(HomeHeroStyle.DEFAULT_INTENSITY, prefs.homeHeroIntensityPercent)
        assertEquals(HomeHeroStyle.DEFAULT_SPREAD, prefs.homeHeroSpreadKey)
    }

    @Test fun sidebarDividersPersistMigrateAndRoundTrip() {
        val (source, _, exporter) = store()
        // Migration: nothing stored yet (old install) → no dividers, order still loads.
        assertTrue(source.sidebarDividersAfter.isEmpty())
        source.sidebarSystemOrder = listOf("tvheadend", "plex")
        source.sidebarDividersAfter = setOf("plex", "home")
        assertEquals(setOf("plex", "home"), source.sidebarDividersAfter)
        assertEquals(listOf("tvheadend", "plex"), source.sidebarSystemOrder.take(2))
        val (target, _, importer) = store()
        importer.importJson(exporter.exportJson())
        assertEquals(setOf("plex", "home"), target.sidebarDividersAfter)
        assertEquals(listOf("tvheadend", "plex"), target.sidebarSystemOrder.take(2))
        // An old backup without the field imports with no dividers.
        importer.importJson("""{"schemaVersion":41,"exportedAtEpochMs":0}""")
        assertTrue(target.sidebarDividersAfter.isEmpty())
    }

    @Test fun sidebarEntryLevelsPersistMigrateAndRoundTrip() {
        val (source, _, exporter) = store()
        assertTrue(source.sidebarEntryOrder.isEmpty())
        assertTrue(source.sidebarEntryDividers.isEmpty())
        source.sidebarEntryOrder = mapOf("tvheadend" to listOf("tvheadend:guide", "tvheadend:live"), "gone" to listOf("x"))
        source.sidebarEntryDividers = mapOf("tvheadend" to listOf("tvheadend:guide"), "plex" to emptyList())
        // Unknown systems and empty levels are dropped on load.
        assertEquals(mapOf("tvheadend" to listOf("tvheadend:guide", "tvheadend:live")), source.sidebarEntryOrder)
        assertEquals(mapOf("tvheadend" to listOf("tvheadend:guide")), source.sidebarEntryDividers)
        val (target, _, importer) = store()
        importer.importJson(exporter.exportJson())
        assertEquals(source.sidebarEntryOrder, target.sidebarEntryOrder)
        assertEquals(source.sidebarEntryDividers, target.sidebarEntryDividers)
        importer.importJson("""{"schemaVersion":41,"exportedAtEpochMs":0}""")
        assertTrue(target.sidebarEntryOrder.isEmpty())
        assertTrue(target.sidebarEntryDividers.isEmpty())
    }

    @Test fun oldBackupKeepsNavSoundsOff() {
        val (prefs, _, importer) = store()
        prefs.navSoundsEnabled = true
        prefs.navSoundsVolume = 0.8f
        importer.importJson("""{"schemaVersion":41,"exportedAtEpochMs":0}""")
        assertFalse(prefs.navSoundsEnabled)
        assertEquals(0.3f, prefs.navSoundsVolume, 0.0001f)
    }

    @Test fun oldBackupKeepsTvheadendHttpsOff() {
        val (prefs, _, importer) = store()
        prefs.tvheadendUseHttps = true
        importer.importJson("""{"schemaVersion":42,"exportedAtEpochMs":0}""")
        assertFalse(prefs.tvheadendUseHttps)
    }

    @Test fun oldBackupORsLegacyPassthroughIntoGlobal() {
        val (prefs, _, importer) = store()
        importer.importJson("""{"schemaVersion":43,"exportedAtEpochMs":0,"plexAudioPassthroughEnabled":false,"tvheadendAudioPassthroughEnabled":true}""")
        assertTrue(prefs.mediaAudioPassthroughEnabled)
        val exported = SettingsBackupManager(prefs, TokenStore(memoryPreferences(), prefs)).exportJson()
        assertTrue(exported.contains("\"mediaAudioPassthroughEnabled\": true") || exported.contains("\"mediaAudioPassthroughEnabled\":true"))
        assertFalse(exported.contains("plexAudioPassthroughEnabled"))
        assertFalse(exported.contains("tvheadendAudioPassthroughEnabled"))
    }

    @Test fun schema44DefaultsDisplayModeSettingsForUpgrade() {
        val (prefs, _, importer) = store()
        importer.importJson("""{"schemaVersion":44,"exportedAtEpochMs":0}""")
        assertEquals(AppPreferences.DISPLAY_REFRESH_START_STOP, prefs.displayRefreshAdjustmentKey)
        assertFalse(prefs.displayChangeNotificationsEnabled)
        assertFalse(prefs.svpAvailable)
        assertTrue(prefs.displayModeWhitelist.isEmpty())
        assertEquals("system", prefs.preferredDisplayModeKey)
        assertTrue(prefs.allowDoubleRefreshRate)
        assertTrue(prefs.allowThreeTwoPulldown)
    }

    @Test fun automaticDisplayModesSurviveSettingsBackup() {
        for (mode in listOf(AppPreferences.DISPLAY_MODE_AUTO_CONTENT, AppPreferences.DISPLAY_MODE_AUTO_DISPLAY)) {
            val (source, _, exporter) = store()
            source.preferredDisplayModeKey = mode
            source.displayRefreshAdjustmentKey = AppPreferences.DISPLAY_REFRESH_START_STOP
            val (target, _, importer) = store()
            importer.importJson(exporter.exportJson())
            assertEquals(mode, target.preferredDisplayModeKey)
            assertEquals(AppPreferences.DISPLAY_REFRESH_START_STOP, target.displayRefreshAdjustmentKey)
        }
    }

    @Test fun schema44PassthroughIgnoresLegacyWhenPresent() {
        val (prefs, _, importer) = store()
        importer.importJson("""{"schemaVersion":44,"exportedAtEpochMs":0,"mediaAudioPassthroughEnabled":false,"plexAudioPassthroughEnabled":true}""")
        assertFalse(prefs.mediaAudioPassthroughEnabled)
    }

    @Test fun oldBackupKeepsYouTubePlayImmediatelyIndependentOfLibrarySelection() {
        val (prefs, _, importer) = store()
        prefs.mediaSelectionBehaviorKey = MediaSelectionPreferences.DETAILS
        importer.importJson("""{"schemaVersion":38,"exportedAtEpochMs":0,"mediaSelectionBehaviorKey":"ask"}""")
        assertEquals(MediaSelectionPreferences.ASK, prefs.mediaSelectionBehaviorKey)
        assertEquals(MediaSelectionPreferences.PLAY, prefs.youtubeMediaSelectionBehaviorKey)
    }

    @Test fun oldBackupsUseRecommendedDimmingAndImportedExtremesAreClamped() {
        val (prefs, _, importer) = store()
        importer.importJson("""{"schemaVersion":32,"exportedAtEpochMs":0}""")
        assertEquals(75, prefs.backgroundVideoDimPercent)
        importer.importJson("""{"schemaVersion":33,"exportedAtEpochMs":0,"backgroundVideoDimPercent":-50}""")
        assertEquals(60, prefs.backgroundVideoDimPercent)
        prefs.backgroundVideoDimPercent = 100
        assertEquals(90, prefs.backgroundVideoDimPercent)
    }

    @Test fun oldBackupReplacesExistingCustomHomeLayoutUsingLegacyFlags() {
        val (prefs, _, importer) = store()
        prefs.homeRows = listOf(HomeRowDefinition(system = "youtube", contentKey = "home"))
        importer.importJson("""{"schemaVersion":28,"exportedAtEpochMs":0,"showContinueWatchingHub":false,"showRecentHomeHubs":false,"showCollectionHomeHubs":false,"tvheadendShowOnHome":true}""")
        assertEquals("tvheadend", prefs.homeRows.single().system)
        assertEquals(prefs.homeRows, prefs.homeRows)
    }

    @Test fun importingWithoutCredentialsDoesNotReuseOldCredentialsAtNewEndpoint() {
        val (prefs, tokens, importer) = store()
        prefs.upsertServerProfile(ServerProfile(id = "same-id", name = "Old", host = "192.168.1.2", port = 32400))
        tokens.token = "old-secret"
        tokens.tvheadendPassword = "old-tv-password"
        importer.importJson("""{"schemaVersion":29,"exportedAtEpochMs":0,"servers":[{"profile":{"id":"same-id","name":"New","host":"192.168.1.3","port":32400}}]}""")
        assertNull(tokens.token)
        assertNull(tokens.tvheadendPassword)
    }

    @Test fun editingJellyfinEndpointDoesNotSendThePreviousServersToken() {
        val (prefs, tokens, _) = store()
        prefs.upsertJellyfinServerProfile(JellyfinServerProfile(
            id = "jf", name = "Jellyfin", host = "192.168.1.4", port = 8096,
            userId = "user", authenticated = true,
        ))
        prefs.jellyfinEnabled = true
        tokens.jellyfinToken = "old-jellyfin-secret"
        assertTrue(prefs.isJellyfinConfigured)
        prefs.jellyfinHost = "192.168.1.5"
        assertFalse(prefs.isJellyfinConfigured)
        assertNull(tokens.jellyfinToken)
        assertEquals("old-jellyfin-secret", tokens.jellyfinTokenForServer("jf"))
        prefs.activateJellyfinServer("jf")
        assertEquals("old-jellyfin-secret", tokens.jellyfinToken)
    }

    @Test fun editingEmbyEndpointDoesNotSendThePreviousServersToken() {
        val (prefs, tokens, _) = store()
        prefs.upsertEmbyServerProfile(EmbyServerProfile(
            id = "em", name = "Emby", host = "192.168.1.6", port = 8096,
            userId = "user", authenticated = true,
        ))
        prefs.embyEnabled = true
        tokens.embyToken = "old-emby-secret"
        assertTrue(prefs.isEmbyConfigured)
        prefs.embyHost = "192.168.1.7"
        assertFalse(prefs.isEmbyConfigured)
        assertNull(tokens.embyToken)
        assertEquals("old-emby-secret", tokens.embyTokenForServer("em"))
        prefs.activateEmbyServer("em")
        assertEquals("old-emby-secret", tokens.embyToken)
    }

    @Test fun disablingPlexRetainsItsProfileAndCanBeReversed() {
        val (prefs, tokens, _) = store()
        prefs.upsertServerProfile(ServerProfile(id = "plex", name = "Plex", host = "192.168.1.2", port = 32400))
        tokens.token = "secret"
        prefs.plexLoginVerified = true
        assertTrue(prefs.isConfigured)
        prefs.plexEnabled = false
        assertFalse(prefs.isConfigured)
        assertEquals("secret", tokens.token)
        assertEquals(1, prefs.serverProfiles.size)
        prefs.plexEnabled = true
        assertTrue(prefs.isConfigured)
    }
}
