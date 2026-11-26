package com.ambient.tv.companion

import com.ambient.tv.data.local.*
import com.ambient.tv.data.weather.WeatherLocation
import com.ambient.tv.data.weather.WeatherSettings
import com.ambient.tv.domain.model.ServerProfile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/** Exercise the filtered wire document, receiver merge and real preference importer together. */
class CompanionSettingsCompletenessTest {
    private val schema = SettingsBackupManager.CURRENT_SCHEMA_VERSION
    private val json = CompanionBackupPolicy.json
    private fun preferences() = AppPreferences(memoryPreferences())
    private fun manager(preferences: AppPreferences) = SettingsBackupManager(preferences, TokenStore(memoryPreferences(), preferences))
    private fun snapshot(preferences: AppPreferences) = manager(preferences).let { it.previewJson(it.exportJson(includeCredentials = true)) }
    private fun source() = SettingsBackup(schemaVersion = schema, exportedAtEpochMs = 2)

    private fun receive(
        raw: String,
        target: AppPreferences,
        mode: String = "preferences",
        device: Boolean = false,
        overwrite: Boolean = true,
    ): SettingsBackup {
        val backups = manager(target)
        // The receiving service sanitizes a transfer again before showing its confirmation.
        val safe = CompanionBackupPolicy.prepare(backups.previewJson(raw), false, mode,
            presentFields = CompanionBackupPolicy.presentFields(raw))
        val merged = CompanionBackupPolicy.merge(backups.previewJson(safe), snapshot(target), mode, overwrite, device,
            presentFields = CompanionBackupPolicy.presentFields(safe))
        return backups.importJson(merged)
    }

    private fun send(
        source: SettingsBackup,
        target: AppPreferences,
        mode: String = "preferences",
        device: Boolean = false,
        overwrite: Boolean = true,
    ) = receive(CompanionBackupPolicy.prepare(source, false, mode), target, mode, device, overwrite)

    @Test fun playbackSpotifyAndHomeChoicesTravelAsPortablePreferences() {
        val row = HomeRowDefinition("youtube-home", "youtube", "subscriptions", "My videos", "list", false)
        val incoming = source().copy(watchedThresholdPercent = 75, resumeIgnoreEndPercent = 15,
            spotifyBitrateKbps = 160, spotifyVisualizerModeKey = "projectm", homeRows = listOf(row),
            systemHomeHiddenRows = setOf("plex.recent", "jellyfin.continue"))
        for (mode in listOf("preferences", "settings")) {
            val target = preferences()
            send(incoming, target, mode)
            assertEquals(75, target.watchedThresholdPercent)
            assertEquals(15, target.resumeIgnoreEndPercent)
            assertEquals(160, target.spotifyBitrateKbps)
            assertEquals("projectm", target.spotifyVisualizerModeKey)
            assertEquals(listOf(row), target.homeRows)
            assertEquals(incoming.systemHomeHiddenRows, target.systemHomeHiddenRows)
        }
        val accountsOnly = preferences()
        val before = snapshot(accountsOnly)
        send(incoming, accountsOnly, "accounts", device = true)
        assertEquals(before.homeRows, accountsOnly.homeRows)
        assertEquals(before.spotifyBitrateKbps, accountsOnly.spotifyBitrateKbps)
        assertEquals(before.watchedThresholdPercent, accountsOnly.watchedThresholdPercent)
    }

    @Test fun minimalNextUpLayoutTravelsAsAPortablePreferenceAndOmittedValuesStayOmitted() {
        val target = preferences()
        fun incoming(minimal: Boolean) = source().copy(extras = SettingsBackupExtras(
            portable = PortableSettingsExtras(nextUpMinimalEnabled = minimal),
        ))
        for (mode in listOf("preferences", "settings")) {
            send(incoming(true), target, mode)
            assertTrue(target.nextUpMinimalEnabled)
            receive("""{"schemaVersion":79,"exportedAtEpochMs":0}""", target, mode)
            assertTrue(target.nextUpMinimalEnabled)
            receive("""{"schemaVersion":$schema,"exportedAtEpochMs":0,
                "extras":{"portable":{"nextUpMinimalEnabled":null}}}""", target, mode)
            assertTrue(target.nextUpMinimalEnabled)
            send(incoming(false), target, mode)
            assertFalse(target.nextUpMinimalEnabled)
        }
        send(incoming(true), target, "accounts", device = true)
        assertFalse(target.nextUpMinimalEnabled)
    }

    @Test fun wakeOnOpenTransfersOnlyWhenExplicitlyProvidedByANewCompanionSender() {
        val target = preferences().apply { wakeOnOpenSystems = setOf("plex") }
        receive("""{"schemaVersion":81,"exportedAtEpochMs":0}""", target)
        assertEquals(setOf("plex"), target.wakeOnOpenSystems)
        receive("""{"schemaVersion":82,"exportedAtEpochMs":0}""", target)
        assertEquals(setOf("plex"), target.wakeOnOpenSystems)

        for (mode in listOf("preferences", "settings")) {
            send(source().copy(wakeOnOpenSystems = setOf("emby")), target, mode)
            assertEquals(setOf("emby"), target.wakeOnOpenSystems)
        }
        send(source().copy(wakeOnOpenSystems = emptySet()), target)
        assertTrue(target.wakeOnOpenSystems.isEmpty())

        send(source().copy(wakeOnOpenSystems = setOf("tvheadend")), target, "accounts")
        assertTrue(target.wakeOnOpenSystems.isEmpty())
    }

    @Test fun mousePointerTimeoutTravelsAsAPortablePreferenceWithoutResettingOlderSenders() {
        fun incoming(seconds: Int) = source().copy(extras = SettingsBackupExtras(
            portable = PortableSettingsExtras(mousePointerHideSeconds = seconds),
        ))
        for (mode in listOf("preferences", "settings")) {
            val target = preferences()
            send(incoming(5), target, mode)
            assertEquals(5, target.mousePointerHideSeconds)
            receive("""{"schemaVersion":79,"exportedAtEpochMs":0}""", target, mode)
            assertEquals(5, target.mousePointerHideSeconds)
            receive("""{"schemaVersion":$schema,"exportedAtEpochMs":0,
                "extras":{"portable":{"mousePointerHideSeconds":null}}}""", target, mode)
            assertEquals(5, target.mousePointerHideSeconds)
            send(incoming(0), target, mode)
            assertEquals(0, target.mousePointerHideSeconds)
            send(incoming(9), target, mode)
            assertEquals(2, target.mousePointerHideSeconds)
        }
        val accountsOnly = preferences().apply { mousePointerHideSeconds = 3 }
        send(incoming(0), accountsOnly, "accounts", device = true)
        assertEquals(3, accountsOnly.mousePointerHideSeconds)
    }

    @Test fun olderCompanionDefaultsAndNullPortableFieldsPreserveTheReceiver() {
        val row = HomeRowDefinition("saved", "youtube", "subscriptions")
        val target = preferences().apply {
            watchedThresholdPercent = 80; resumeIgnoreEndPercent = 20
            spotifyBitrateKbps = 96; spotifyVisualizerModeKey = "projectm"
            homeRows = listOf(row); systemHomeHiddenRows = setOf("emby.recent")
        }
        receive("""{"schemaVersion":79,"exportedAtEpochMs":0}""", target)
        assertEquals(96, target.spotifyBitrateKbps)
        assertEquals("projectm", target.spotifyVisualizerModeKey)
        assertEquals(setOf("emby.recent"), target.systemHomeHiddenRows)
        assertEquals(80, target.watchedThresholdPercent)
        assertEquals(20, target.resumeIgnoreEndPercent)
        assertEquals(listOf(row), target.homeRows)

        send(source().copy(homeRows = null, watchedThresholdPercent = null, resumeIgnoreEndPercent = null), target)
        assertEquals(80, target.watchedThresholdPercent)
        assertEquals(20, target.resumeIgnoreEndPercent)
        assertEquals(listOf(row), target.homeRows)
        send(source().copy(homeRows = emptyList()), target)
        assertTrue(target.homeRows.isEmpty())
    }

    @Test fun extraPreferencesAreFilteredByTransferModeAndDeviceOptIn() {
        val portable = PortableSettingsExtras(
            allContinueSystems = setOf("jellyfin"), filterBarShown = false, launcherIntegrationEnabled = true,
            settingsLevelKey = "simple", autoPictureInPicture = false, projectmShowPresetName = true,
            projectmPresetShuffle = false, gaplessMusicEnabled = false, teletextSubtitleChoice = "150",
            lyricsVisible = false, lyricsOnlineEnabled = false, frameDropOverlayHistory = true,
            liveTvReminderAutoSwitch = true, autoUpdateCheck = false, projectmFavorites = setOf("chosen.milk"),
            keyBindings = mapOf("refresh" to "183:0"), gamepadSettings = mapOf("confirm" to "b"), retroInputSettings = mapOf("dpad_stick" to "1"), gameSettings = mapOf("shader" to "crt"),
            viewTransitionStyleKey = "slide", nextUpMinimalEnabled = true, mousePointerHideSeconds = 5,
            gameMetadataOptions = com.ambient.tv.games.metadata.GameMetadataOptions(
                provider = "thegamesdb", automatic = false, region = "jp", language = "de"),
        )
        val incoming = source().copy(tvheadendHost = "tvh.example", extras = SettingsBackupExtras(
            portable = portable, device = DeviceSettingsExtras(128, true), connections = ConnectionSettingsExtras(9984)))
        val preferencesPayload = json.parseToJsonElement(CompanionBackupPolicy.prepare(incoming, true, "preferences")).jsonObject
        assertEquals(setOf("portable", "device"), preferencesPayload.getValue("extras").jsonObject.keys)
        val accountPayload = json.parseToJsonElement(CompanionBackupPolicy.prepare(incoming, false, "accounts")).jsonObject
        assertEquals(setOf("connections"), accountPayload.getValue("extras").jsonObject.keys)

        for (device in listOf(false, true)) {
            val target = preferences().apply { imageCacheMb = 256; tvheadendHtspPort = 10002 }
            val restored = send(incoming, target, device = device)
            assertEquals(portable.copy(gameLibraryOptions = target.gameLibraryOptions, gameLibraryOverrides = emptyList(), gameLibraryFolders = emptyList(), moonlightStreamProfile = target.moonlightStreamProfile,
                moonlightLibraryView = target.moonlightLibraryView,
                moonlightGridColumns = target.moonlightGridColumns, moonlightListRows = target.moonlightListRows,
                moonlightSortKey = target.moonlightSortKey, moonlightSortDescending = target.moonlightSortDescending), restored.extras?.portable)
            assertEquals(setOf("jellyfin"), target.allContinueSystems)
            assertFalse(target.filterBarShown)
            assertTrue(target.launcherIntegrationEnabled)
            assertEquals("simple", target.settingsLevelKey)
            assertFalse(target.autoPictureInPicture)
            assertTrue(target.projectmShowPresetName)
            assertFalse(target.projectmPresetShuffle)
            assertFalse(target.gaplessMusicEnabled)
            assertEquals("150", target.teletextSubtitleChoice)
            assertFalse(target.lyricsVisible)
            assertFalse(target.lyricsOnlineEnabled)
            assertTrue(target.frameDropOverlayHistory)
            assertTrue(target.nextUpMinimalEnabled)
            assertEquals("slide", target.viewTransitionStyleKey)
            assertEquals(5, target.mousePointerHideSeconds)
            assertEquals(portable.gameMetadataOptions, target.gameMetadataOptions)
            assertEquals(mapOf("refresh" to "183:0"), target.keyBindings)
            assertEquals(mapOf("confirm" to "b"), target.gamepadSettings)
            assertEquals(mapOf("dpad_stick" to "1"), target.retroInputSettings)
            assertEquals(mapOf("shader" to "crt"), target.gameSettings)
            assertEquals(if (device) 128 else 256, target.imageCacheMb)
            assertEquals(device, target.cardImages1080)
            assertEquals(10002, target.tvheadendHtspPort)
        }

        val accountsTarget = preferences().apply {
            gaplessMusicEnabled = true; imageCacheMb = 256; viewTransitionStyleKey = "cinematic"
        }
        send(incoming, accountsTarget, "accounts", device = true)
        assertTrue(accountsTarget.gaplessMusicEnabled)
        assertFalse(accountsTarget.nextUpMinimalEnabled)
        assertEquals("cinematic", accountsTarget.viewTransitionStyleKey)
        assertEquals(256, accountsTarget.imageCacheMb)
        assertEquals(9984, accountsTarget.tvheadendHtspPort)
    }

    @Test fun moonlightBrowseAndQualityPreferencesTravelWithoutHostInventory() {
        val target = preferences().apply { moonlightLibraryView = "list" }
        val quality = com.ambient.tv.moonlight.MoonlightStreamProfile(width = 1280, height = 720)
        val incoming = source().copy(extras = SettingsBackupExtras(
            portable = PortableSettingsExtras(moonlightLibraryView = "grid", moonlightStreamProfile = quality),
            connections = ConnectionSettingsExtras(moonlightEnabled = true)))
        send(incoming, target, "preferences")
        assertEquals("grid", target.moonlightLibraryView)
        assertEquals(quality.validated(), target.moonlightStreamProfile)
        assertFalse(target.moonlightEnabled)
        receive("""{"schemaVersion":85,"exportedAtEpochMs":0}""", target)
        assertEquals("grid", target.moonlightLibraryView)
    }

    @Test fun missingAndPartialExtrasPreserveOtherReceiverChoices() {
        val target = preferences().apply {
            gaplessMusicEnabled = false; lyricsVisible = false; autoPictureInPicture = false
            imageCacheMb = 256; cardImages1080 = true; tvheadendHtspPort = 10002
        }
        val partial = source().copy(extras = SettingsBackupExtras(
            portable = PortableSettingsExtras(lyricsVisible = true), device = DeviceSettingsExtras(cardImages1080 = false)))
        send(partial, target, device = true)
        assertTrue(target.lyricsVisible)
        assertFalse(target.gaplessMusicEnabled)
        assertFalse(target.autoPictureInPicture)
        assertEquals(256, target.imageCacheMb)
        assertFalse(target.cardImages1080)
        assertEquals(10002, target.tvheadendHtspPort)

        send(source().copy(extras = null), target, device = true)
        assertFalse(target.gaplessMusicEnabled)
        assertEquals(256, target.imageCacheMb)
        send(partial.copy(schemaVersion = 79, extras = SettingsBackupExtras(
            portable = PortableSettingsExtras(gaplessMusicEnabled = true))), target)
        assertFalse(target.gaplessMusicEnabled)
    }

    @Test fun htspPortOnlyFollowsAnAcceptedTvheadendAccount() {
        val incoming = source().copy(tvheadendHost = "new.example",
            extras = SettingsBackupExtras(connections = ConnectionSettingsExtras(9984)))
        val target = preferences().apply { tvheadendHost = "existing.example"; tvheadendHtspPort = 10002 }
        send(incoming, target, "accounts", overwrite = false)
        assertEquals("existing.example", target.tvheadendHost)
        assertEquals(10002, target.tvheadendHtspPort)
        send(selectSyncAccounts(incoming, emptySet()), target, "settings")
        assertEquals("existing.example", target.tvheadendHost)
        assertEquals(10002, target.tvheadendHtspPort)
        send(incoming, target, "settings")
        assertEquals("new.example", target.tvheadendHost)
        assertEquals(9984, target.tvheadendHtspPort)
    }

    @Test fun weatherClockMotionAndPlayerChoicesSurviveTheActualImporter() {
        val weather = WeatherSettings(enabled = true, showInSidebar = false,
            locations = listOf(WeatherLocation("berlin", "Berlin", "DE", latitude = 52.52, longitude = 13.41, timezone = "Europe/Berlin")),
            selectedLocationId = "berlin", showInHeader = false, showInScreensaver = true,
            temperatureUnit = "fahrenheit", windUnit = "mph", radarProvider = "dwd", satelliteProvider = "nasa_gibs")
        val clock = ClockSettings(display = "everywhere", format = "12", seconds = true, font = "mono")
        val target = preferences().apply { uiRenderResolutionKey = "panel" }
        send(source().copy(weather = weather, clock = clock, accentMotionKey = "ambient",
            playerOsdModeIconVisible = false, playerBackMinimizes = true, uiRenderResolutionKey = "1080p"), target)
        assertEquals(weather, target.weatherSettings)
        assertEquals(clock, target.clockSettings)
        assertEquals("ambient", target.accentMotionKey)
        assertFalse(target.playerOsdModeIconVisible)
        assertTrue(target.playerBackMinimizes)
        assertEquals("panel", target.uiRenderResolutionKey)

        receive("""{"schemaVersion":72,"exportedAtEpochMs":0}""", target, device = true)
        assertEquals(weather, target.weatherSettings)
        assertEquals(clock, target.clockSettings)
        assertEquals("ambient", target.accentMotionKey)
        assertFalse(target.playerOsdModeIconVisible)
        assertTrue(target.playerBackMinimizes)
        assertEquals("panel", target.uiRenderResolutionKey)
    }

    @Test fun preIntroBackupsDoNotReplaceTheReceiversAnimation() {
        val target = preferences().apply { introAnimationKey = "prism" }
        receive("""{"schemaVersion":71,"exportedAtEpochMs":0}""", target)
        assertEquals("prism", target.introAnimationKey)
        send(source().copy(schemaVersion = 72, introAnimationKey = "off"), target)
        assertEquals("off", target.introAnimationKey)
    }

    @Test fun oldAudioForceChannelsMigrationRunsBeforeReceiverSchemaReplacesSourceSchema() {
        val audio = AudioSettings(selectedProfile = "tv", profiles = mapOf("tv" to AudioOptions(forceChannels = 8)))
        for (sourceSchema in listOf(69, 70)) {
            val target = preferences().apply {
                audioSettings = audio.copy(profiles = mapOf("tv" to AudioOptions(forceChannels = 6)))
            }
            send(source().copy(schemaVersion = sourceSchema, audioSettings = audio), target, device = false)
            assertEquals(6, target.audioSettings.profiles.getValue("tv").forceChannels)
            send(source().copy(schemaVersion = sourceSchema, audioSettings = audio), target, device = true)
            assertEquals(if (sourceSchema < 70) 0 else 8, target.audioSettings.profiles.getValue("tv").forceChannels)
        }
    }

    @Test fun legacyPassthroughMigrationSurvivesBothFilteringPasses() {
        val raw = """{"schemaVersion":43,"exportedAtEpochMs":0,"tvheadendAudioPassthroughEnabled":true}"""
        val source = manager(preferences()).previewJson(raw)
        val wire = CompanionBackupPolicy.prepare(source, false, "preferences")
        assertFalse(json.parseToJsonElement(wire).jsonObject.containsKey("tvheadendAudioPassthroughEnabled"))
        val target = preferences().apply { mediaAudioPassthroughEnabled = false }
        receive(wire, target, device = false)
        assertFalse(target.mediaAudioPassthroughEnabled)
        receive(wire, target, device = true)
        assertTrue(target.mediaAudioPassthroughEnabled)
    }

    @Test fun explicitEmptyProfilesClearButOmittedProfilesRemainOnTheReceiver() {
        fun target() = preferences().apply {
            titleVideoSettings = mapOf("plex:1" to TitleVideoSettings(rendererKey = "mpv"))
            scopedVideoSettings = mapOf("season:plex:0::2" to TitleVideoSettings(rendererKey = "vlc"))
        }
        val cleared = target()
        send(source(), cleared, "settings", device = true)
        assertTrue(cleared.titleVideoSettings.isEmpty())
        assertTrue(cleared.scopedVideoSettings.isEmpty())

        val preserved = target()
        receive("""{"schemaVersion":$schema,"exportedAtEpochMs":0}""", preserved, "settings", device = true)
        assertFalse(preserved.titleVideoSettings.isEmpty())
        assertFalse(preserved.scopedVideoSettings.isEmpty())

        val onlyTitles = json.parseToJsonElement(CompanionBackupPolicy.prepare(source(), false, "settings")).jsonObject - "scopedVideoSettings"
        receive(JsonObject(onlyTitles).toString(), preserved, "settings", device = true)
        assertTrue(preserved.titleVideoSettings.isEmpty())
        assertFalse(preserved.scopedVideoSettings.isEmpty())
    }

    @Test fun settingsOnlyKeepsServerBoundProfilesEvenWithHardwareOptIn() {
        val incoming = source().copy(uiScale = .8f,
            titleVideoSettings = mapOf("plex:5:phone:42" to TitleVideoSettings(rendererKey = "mpv")),
            scopedVideoSettings = mapOf("series:plex:5:phone:7" to TitleVideoSettings(rendererKey = "mpv")))
        val target = preferences().apply {
            titleVideoSettings = mapOf("plex:2:tv:42" to TitleVideoSettings(rendererKey = "vlc"))
            scopedVideoSettings = mapOf("series:plex:2:tv:7" to TitleVideoSettings(rendererKey = "vlc"))
        }
        val before = snapshot(target)
        val wire = CompanionBackupPolicy.prepare(incoming, false, "preferences")
        assertFalse("titleVideoSettings" in CompanionBackupPolicy.presentFields(wire))
        assertFalse("scopedVideoSettings" in CompanionBackupPolicy.presentFields(wire))
        receive(wire, target, device = true)
        assertEquals(before.titleVideoSettings, target.titleVideoSettings)
        assertEquals(before.scopedVideoSettings, target.scopedVideoSettings)
        assertEquals(.8f, target.uiScale)

        // The receiver enforces the boundary even for an older or unfiltered sender.
        val raw = CompanionBackupPolicy.merge(incoming, snapshot(target), "preferences", true, true,
            setOf("titleVideoSettings", "scopedVideoSettings"))
        manager(target).importJson(raw)
        assertEquals(before.titleVideoSettings, target.titleVideoSettings)
        assertEquals(before.scopedVideoSettings, target.scopedVideoSettings)
    }

    @Test fun repeatedAccountImportAfterAnEndpointEditAllocatesDistinctIdsAndRemapsProfiles() {
        val sourceId = "same-id"
        val incoming = source().copy(servers = listOf(ServerBackup(ServerProfile(sourceId, "Sender", "source.example"))),
            titleVideoSettings = mapOf("plex:${sourceId.length}:$sourceId:42" to TitleVideoSettings(rendererKey = "mpv")))
        val target = preferences().apply { upsertServerProfile(ServerProfile(sourceId, "Existing", "existing.example")) }
        send(incoming, target, "settings", device = true)
        val first = target.serverProfiles.single { it.host == "source.example" }
        assertTrue(first.id.startsWith("remote-"))
        target.upsertServerProfile(first.copy(host = "edited.example"))

        send(incoming, target, "settings", device = true)
        val accounts = target.serverProfiles
        assertEquals(3, accounts.size)
        assertEquals(accounts.size, accounts.map { it.id }.toSet().size)
        assertEquals(first.id, accounts.single { it.host == "edited.example" }.id)
        val added = accounts.single { it.host == "source.example" }
        assertNotEquals(first.id, added.id)
        assertEquals(setOf("plex:${first.id.length}:${first.id}:42", "plex:${added.id.length}:${added.id}:42"), target.titleVideoSettings.keys)

        send(incoming, target, "settings", device = true)
        assertEquals(3, target.serverProfiles.size)
        assertEquals(added.id, target.serverProfiles.single { it.host == "source.example" }.id)
    }

    @Test fun oversizedProfilesRemainOmittedWhenTheReceiverSanitizesTheWireAgain() {
        val title = TitleVideoSettings(rendererKey = "mpv", label = "x".repeat(4096))
        val prepared = CompanionBackupPolicy.prepareWithInfo(source().copy(
            titleVideoSettings = (1..200).associate { "plex:$it" to title }), false, "settings")
        assertTrue(prepared.profilesOmitted)
        assertTrue(prepared.json.toByteArray().size <= CompanionBackupPolicy.MAX_BYTES)
        val target = preferences().apply { titleVideoSettings = mapOf("plex:kept" to TitleVideoSettings(rendererKey = "vlc")) }
        receive(prepared.json, target, "settings", device = true)
        assertEquals(setOf("plex:kept"), target.titleVideoSettings.keys)
    }

    @Test fun anOlderFileWithoutAccentStyleKeepsTheReceiversStyleAcrossSanitization() {
        val target = preferences().apply { accentStyleKey = "glass" }
        val raw = """{"schemaVersion":76,"exportedAtEpochMs":0}"""
        val prepared = CompanionBackupPolicy.prepare(manager(target).previewJson(raw), false, "preferences",
            presentFields = CompanionBackupPolicy.presentFields(raw))
        assertFalse("accentStyleKey" in CompanionBackupPolicy.presentFields(prepared))
        receive(prepared, target)
        assertEquals("glass", target.accentStyleKey)

        send(source().copy(accentStyleKey = "satin"), target)
        assertEquals("satin", target.accentStyleKey)
    }

    @Test fun deselectingTvheadendAlsoExcludesItsExtraConnectionSettings() {
        val incoming = source().copy(tvheadendHost = "tvh.test",
            extras = SettingsBackupExtras(connections = ConnectionSettingsExtras(tvheadendHtspPort = 10982)))
        assertNull(selectSyncAccounts(incoming, emptySet()).extras?.connections)
        assertEquals(10982, selectSyncAccounts(incoming, setOf("tvheadend")).extras?.connections?.tvheadendHtspPort)
    }

    @Test fun explicitlyStoredOlderFileValuesTravelAfterBecomingShareable() {
        val target = preferences().apply {
            spotifyBitrateKbps = 96
            spotifyVisualizerModeKey = "cover"
            systemHomeHiddenRows = setOf("emby.recent")
        }
        receive("""{"schemaVersion":79,"exportedAtEpochMs":0,
            "spotifyBitrateKbps":160,"spotifyVisualizerModeKey":"projectm","systemHomeHiddenRows":[]}""", target)
        assertEquals(160, target.spotifyBitrateKbps)
        assertEquals("projectm", target.spotifyVisualizerModeKey)
        assertTrue(target.systemHomeHiddenRows.isEmpty())
    }

    @Test fun missingNewlyShareableFieldsStayMissingEvenInAPartialCurrentFile() {
        for (version in listOf(79, schema)) {
            val target = preferences().apply {
                spotifyBitrateKbps = 96
                spotifyVisualizerModeKey = "projectm"
                systemHomeHiddenRows = setOf("emby.recent")
            }
            receive("""{"schemaVersion":$version,"exportedAtEpochMs":0}""", target)
            assertEquals(96, target.spotifyBitrateKbps)
            assertEquals("projectm", target.spotifyVisualizerModeKey)
            assertEquals(setOf("emby.recent"), target.systemHomeHiddenRows)
        }
    }

    @Test fun oldDecodedModelsWithoutPresenceCannotManufactureExplicitFileValues() {
        val wire = CompanionBackupPolicy.prepare(source().copy(schemaVersion = 79,
            spotifyBitrateKbps = 160, spotifyVisualizerModeKey = "projectm", systemHomeHiddenRows = emptySet()),
            false, "preferences")
        val fields = CompanionBackupPolicy.presentFields(wire)
        assertFalse("spotifyBitrateKbps" in fields)
        assertFalse("spotifyVisualizerModeKey" in fields)
        assertFalse("systemHomeHiddenRows" in fields)
    }
}
