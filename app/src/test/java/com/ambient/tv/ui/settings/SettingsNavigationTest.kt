package com.ambient.tv.ui.settings

import com.ambient.tv.data.local.AudioOptions
import com.ambient.tv.data.local.HomeRowDefinition
import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.ui.common.MediaSystem
import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationTest {
    @Test fun settingsAreasFollowTheRailOrderAndKeepEveryPage() {
        assertEquals(
            listOf(
                SettingsArea.START, SettingsArea.HOME, SettingsArea.SYSTEMS, SettingsArea.TELEVISION,
                SettingsArea.STREAMING, SettingsArea.PICTURE, SettingsArea.PLAYBACK, SettingsArea.NETWORK,
                SettingsArea.APP,
            ),
            SettingsArea.entries,
        )
        assertEquals(SettingsArea.START, SettingsCategory.LANGUAGE.area)
        assertEquals(SettingsArea.START, SettingsCategory.APPEARANCE.area)
        assertEquals(SettingsArea.START, SettingsCategory.BACKGROUND.area)
        assertEquals(SettingsArea.START, SettingsCategory.SCREENSAVER.area)
        assertEquals(SettingsArea.HOME, SettingsCategory.HOME.area)
        assertEquals(SettingsArea.HOME, SettingsCategory.LIBRARY.area)
        assertEquals(SettingsArea.HOME, SettingsCategory.MEDIA_SELECTION.area)
        assertEquals(SettingsArea.HOME, SettingsCategory.NAV_SOUNDS.area)
        assertEquals(SettingsArea.SYSTEMS, SettingsCategory.PLEX.area)
        assertEquals(SettingsArea.SYSTEMS, SettingsCategory.DLNA.area)
        assertEquals(SettingsArea.SYSTEMS, SettingsCategory.FILES.area)
        assertEquals(SettingsArea.TELEVISION, SettingsCategory.TVHEADEND.area)
        assertEquals(SettingsArea.TELEVISION, SettingsCategory.IPTV.area)
        assertEquals(SettingsArea.TELEVISION, SettingsCategory.LIVE_TV.area)
        assertEquals(SettingsArea.STREAMING, SettingsCategory.YOUTUBE.area)
        assertEquals(SettingsArea.STREAMING, SettingsCategory.SPOTIFY.area)
        assertEquals(SettingsArea.STREAMING, SettingsCategory.RADIO.area)
        assertEquals(SettingsArea.STREAMING, SettingsCategory.TRAILERS.area)
        assertEquals(SettingsArea.PICTURE, SettingsCategory.IMAGE.area)
        assertEquals(SettingsArea.PICTURE, SettingsCategory.AUDIO.area)
        assertEquals(SettingsArea.PICTURE, SettingsCategory.SUBTITLES.area)
        assertEquals(SettingsArea.PICTURE, SettingsCategory.VISUALIZER.area)
        assertEquals(SettingsArea.PLAYBACK, SettingsCategory.PLAYBACK.area)
        assertEquals(SettingsArea.PLAYBACK, SettingsCategory.SEEK.area)
        assertEquals(SettingsArea.PLAYBACK, SettingsCategory.NEXT_UP.area)
        assertEquals(SettingsArea.PLAYBACK, SettingsCategory.PHOTOS.area)
        assertEquals(SettingsArea.APP, SettingsCategory.NOTIFICATIONS.area)
        assertEquals(SettingsArea.NETWORK, SettingsCategory.COMPANION.area)
    }

    @Test fun sourceAreasHideSystemsThatAreNotSetUpAndKeepLiveTv() {
        val empty = SettingsUiState(plexEnabled = false, youtubeEnabled = false)
        assertFalse(showsInAreaIndex(SettingsCategory.PLEX, SettingsArea.SYSTEMS, systemListed(SettingsCategory.PLEX, empty)))
        val plex = empty.copy(plexEnabled = true, plexConfigured = true)
        assertTrue(showsInAreaIndex(SettingsCategory.PLEX, SettingsArea.SYSTEMS, systemListed(SettingsCategory.PLEX, plex)))
        assertTrue(showsInAreaIndex(SettingsCategory.LIVE_TV, SettingsArea.TELEVISION, listed = false))
        assertFalse(showsInAreaIndex(SettingsCategory.IPTV, SettingsArea.TELEVISION, listed = false))
        assertFalse(showsInAreaIndex(SettingsCategory.YOUTUBE, SettingsArea.STREAMING, listed = false))
        assertTrue(showsInAreaIndex(SettingsCategory.APPEARANCE, SettingsArea.START, listed = false))
        assertFalse(showsInAreaIndex(SettingsCategory.PLEX, SettingsArea.STREAMING, listed = true))
        assertEquals(
            listOf(MediaSystem.PLEX, MediaSystem.JELLYFIN, MediaSystem.EMBY, MediaSystem.DLNA, MediaSystem.TVHEADEND, MediaSystem.IPTV, MediaSystem.YOUTUBE, MediaSystem.SPOTIFY, MediaSystem.RADIO, MediaSystem.TRAILERS, MediaSystem.FILES),
            MediaSystem.addOrder,
        )
    }

    @Test fun backFromAnAddedSystemReturnsToTheLogoCatalogThenTheArea() {
        val fromPage = SettingsHubNav(page = SettingsCategory.PLEX, addingSystems = true, returnToAdd = true).pop()!!
        assertNull(fromPage.page)
        assertTrue(fromPage.addingSystems)
        assertFalse(fromPage.search)
        assertFalse(fromPage.areaMenu)
        val fromCatalog = fromPage.pop()!!
        assertFalse(fromCatalog.addingSystems)
        assertFalse(fromCatalog.areaMenu)
        assertTrue(fromCatalog.pop()!!.areaMenu)
    }

    @Test fun backFromEveryPageReturnsToTheAreaIndex() {
        SettingsCategory.entries.forEach { category ->
            val popped = SettingsHubNav(page = category).pop()
            assertNotNull(category.name, popped)
            assertNull(category.name, popped!!.page)
            assertFalse(category.name, popped.search)
            assertFalse(category.name, popped.areaMenu)
        }
    }

    @Test fun leftClosesOnlyTheOpenPage() {
        SettingsCategory.entries.forEach { category ->
            assertTrue(category.name, SettingsHubNav(page = category).leftClosesPage())
        }
        assertFalse(SettingsHubNav().leftClosesPage())
        assertFalse(SettingsHubNav(search = true).leftClosesPage())
        assertFalse(SettingsHubNav(areaMenu = true).leftClosesPage())
    }

    @Test fun backWalksPageThenIndexThenAreaMenuThenExit() {
        val fromPage = SettingsHubNav(page = SettingsCategory.TVHEADEND).pop()!!
        assertNull(fromPage.page)
        assertFalse(fromPage.areaMenu)
        val fromIndex = fromPage.pop()!!
        assertTrue(fromIndex.areaMenu)
        assertNull(fromIndex.pop())
    }

    @Test fun backWalksSearchThenIndexThenAreaMenu() {
        val fromPage = SettingsHubNav(page = SettingsCategory.IMAGE, search = true, returnToSearch = true).pop()!!
        assertNull(fromPage.page)
        assertTrue(fromPage.search)
        val fromSearch = fromPage.pop()!!
        assertFalse(fromSearch.search)
        assertFalse(fromSearch.areaMenu)
        val fromIndex = fromSearch.pop()!!
        assertTrue(fromIndex.areaMenu)
        assertNull(fromIndex.pop())
    }

    @Test fun directPageEntryBackWalksParentIndexThenAreaMenuThenExit() {
        SettingsCategory.entries.forEach { category ->
            val initialPage = SettingsCategory.fromKey(category.name)!!
            val parent = SettingsHubNav(page = initialPage).pop()!!
            assertNull(category.name, parent.page)
            assertFalse(category.name, parent.areaMenu)
            val areaMenu = parent.pop()!!
            assertTrue(category.name, areaMenu.areaMenu)
            assertNull(category.name, areaMenu.pop())
        }
    }

    @Test fun handheldPageBackReturnsToParentBeforeExiting() {
        val parent = SettingsHubNav(page = SettingsCategory.APPEARANCE, areaMenu = true).pop()!!
        assertNull(parent.page)
        assertFalse(parent.areaMenu)
        assertNull(parent.copy(areaMenu = true).pop())
    }

    @Test fun wireGuardPickerBackWalksFoldersThenRootsThenDismiss() {
        assertEquals(WireGuardBrowseBack.Up, wireGuardBrowseBack(3, true))
        assertEquals(WireGuardBrowseBack.Roots, wireGuardBrowseBack(1, true))
        assertEquals(WireGuardBrowseBack.Dismiss, wireGuardBrowseBack(0, false))
    }

    @Test fun existingSetupAndSidebarAliasesKeepTheirTargets() {
        mapOf(
            "plex" to SettingsCategory.PLEX, "network" to SettingsCategory.PLEX,
            "vpn" to SettingsCategory.WIREGUARD, "wireguard" to SettingsCategory.WIREGUARD,
            "jellyfin" to SettingsCategory.JELLYFIN, "emby" to SettingsCategory.EMBY, "tvh" to SettingsCategory.TVHEADEND,
            "tvheadend" to SettingsCategory.TVHEADEND, "youtube" to SettingsCategory.YOUTUBE,
            "spotify" to SettingsCategory.SPOTIFY, "radio" to SettingsCategory.RADIO, "files" to SettingsCategory.FILES,
            "locale" to SettingsCategory.LANGUAGE, "nav" to SettingsCategory.MEDIA_SELECTION,
            "navigation" to SettingsCategory.MEDIA_SELECTION, "media_selection" to SettingsCategory.MEDIA_SELECTION,
            "libraries" to SettingsCategory.LIBRARY, "library" to SettingsCategory.LIBRARY,
            "info" to SettingsCategory.ABOUT, "about" to SettingsCategory.ABOUT,
            "playback" to SettingsCategory.PLAYBACK, "seek" to SettingsCategory.SEEK,
            "live_tv" to SettingsCategory.LIVE_TV, "timeshift" to SettingsCategory.LIVE_TV,
            "reminders" to SettingsCategory.LIVE_TV,
            "advanced" to SettingsCategory.SPOTIFY,
            "nav_sounds" to SettingsCategory.NAV_SOUNDS,
            "navigation_sounds" to SettingsCategory.NAV_SOUNDS,
            "sound_editor" to SettingsCategory.NAV_SOUNDS,
            "screensaver" to SettingsCategory.SCREENSAVER, "appearance" to SettingsCategory.APPEARANCE,
        ).forEach { (key, target) -> assertEquals(key, target, SettingsCategory.fromKey(key)) }
        assertNull(SettingsCategory.fromKey(null))
        assertNull(SettingsCategory.fromKey("obsolete"))
    }

    @Test fun searchFindsReorderedCaseInsensitiveTermsWithoutMatchingPartialQueries() {
        assertTrue(settingsSearchMatches(" WOL  jellyfin ", "Jellyfin: Server, WOL und Anmeldung"))
        assertTrue(settingsSearchMatches("sCaLeR", "Bildprofile: Scaler und Filter"))
        assertFalse(settingsSearchMatches("WOL Spotify", "Spotify: Anmeldung"))
        assertTrue(settingsSearchMatches("", "Darstellung"))
    }

    @Test fun loadingAndAccountStatusDoNotMakeSettingsDirty() {
        val state = SettingsUiState()
        assertEquals(state.editableValues(), state.copy(
            statusMessage = "Connected", isTestingConnection = true, isDiscoveringServers = true,
            youtubeAuthBusy = true, youtubeSignedIn = true, youtubeAuthKnown = true,
            youtubeSignInCode = "ABCD", spotifyWebAuthorized = true, spotifyAccountName = "Account",
            isImportingSettings = true, uiLanguageReloadRequired = true, fileSourceCount = 2,
        ).editableValues())
    }

    @Test fun sourceTogglesAndTvDeinterlaceAreDraftValues() {
        val state = SettingsUiState()
        listOf(state.copy(filesEnabled = true), state.copy(youtubeEnabled = !state.youtubeEnabled),
            state.copy(plexEnabled = false), state.copy(tvheadendDeinterlaceModeKey = "auto"),
            state.copy(tvheadendDeinterlaceFilterKey = "yadif2x"), state.copy(tvheadendHost = "nas"),
            state.copy(tvheadendMpvDeinterlaceFilterKey = "w3fdif"),
        ).forEach { assertNotEquals(state.editableValues(), it.editableValues()) }
    }

    @Test fun pictureAndAudioDefaultsParticipateInDiscardAndDirtyComparison() {
        val state = SettingsUiState()
        val changed = state.copy(mpvImageOptions = MpvImageOptions(scaler = "fsrcnnx8"),
            audioSettings = state.audioSettings.update(AudioOptions(output = "pcm", delayMs = 125)))
        assertNotEquals(state.editableValues(), changed.editableValues())
        assertEquals(state.editableValues(), changed.copy(mpvImageOptions = state.mpvImageOptions,
            audioSettings = state.audioSettings).editableValues())
    }

    @Test fun HomeChangesAreNotConfusedWithDerivedVisibilityFlags() {
        val state = SettingsUiState()
        assertEquals(state.editableValues(), state.copy(tvheadendShowOnHome = false).editableValues())
        assertNotEquals(state.editableValues(), state.copy(homeRows = listOf(
            HomeRowDefinition(system = HomeRowDefinition.SYSTEM_TVHEADEND, contentKey = "recent_channels"),
        )).editableValues())
    }

    @Test fun onlyUsableEnabledSourcesQualifyForSingleSystemShortcut() {
        val empty = SettingsUiState(plexEnabled = false, youtubeEnabled = false)
        assertFalse(sourceConfigured(SettingsCategory.SPOTIFY, empty.copy(spotifyEnabled = true)))
        assertTrue(sourceConfigured(SettingsCategory.RADIO, empty.copy(radioEnabled = true)))
        assertFalse(sourceConfigured(SettingsCategory.FILES, empty.copy(filesEnabled = true)))
        assertFalse(sourceConfigured(SettingsCategory.DLNA, empty))
        assertFalse(systemListed(SettingsCategory.DLNA, empty))
        assertTrue(systemListed(SettingsCategory.DLNA, empty.copy(dlnaServerCount = 1)))
        val single = empty.copy(filesEnabled = true, fileSourceCount = 1)
        assertEquals(listOf(SettingsCategory.FILES), usableConfiguredSystems(single))
        assertEquals(SettingsCategory.FILES, singleUsableConfiguredSystem(single))
        val two = single.copy(youtubeEnabled = true)
        assertEquals(2, usableConfiguredSystems(two).size)
        assertNull(singleUsableConfiguredSystem(two))
        assertFalse(sourceConfigured(SettingsCategory.PLEX, empty.copy(plexConfigured = true)))
        assertNull(singleUsableConfiguredSystem(empty))
    }

    @Test fun photoSettingsParticipateInDraftDirtyAndDiscardComparison() {
        val state = SettingsUiState()
        listOf(
            state.copy(photoSlideshowIntervalSeconds = 12),
            state.copy(photoTransitionKey = "slide"),
            state.copy(photoKenBurns = true),
            state.copy(photoShuffle = true),
            state.copy(photoIncludeSubfolders = true),
            state.copy(photoSortKey = "name"),
            state.copy(photoShowInfo = true),
            state.copy(screensaverPhotoAlbumKey = "lib-1", screensaverPhotoAlbumTitle = "Vacation"),
        ).forEach { assertNotEquals(state.editableValues(), it.editableValues()) }
        val changed = state.copy(photoSlideshowIntervalSeconds = 15, photoKenBurns = true)
        assertEquals(state.editableValues(), changed.copy(
            photoSlideshowIntervalSeconds = state.photoSlideshowIntervalSeconds,
            photoKenBurns = state.photoKenBurns,
        ).editableValues())
    }

    @Test fun homeAddRowOnlyOffersActiveSystemsThatCanServeData() {
        val empty = SettingsUiState(plexEnabled = false, youtubeEnabled = false)
        assertFalse(homeRowSourceAddable(HomeRowDefinition.SYSTEM_PLEX, "continue", empty.copy(plexEnabled = true)))
        assertTrue(homeRowSourceAddable(HomeRowDefinition.SYSTEM_PLEX, "continue", empty.copy(plexEnabled = true, plexConfigured = true)))
        assertFalse(homeRowSourceAddable(HomeRowDefinition.SYSTEM_JELLYFIN, "recent", empty.copy(jellyfinEnabled = true)))
        assertTrue(homeRowSourceAddable(HomeRowDefinition.SYSTEM_JELLYFIN, "recent", empty.copy(jellyfinEnabled = true, jellyfinConfigured = true)))
        assertFalse(homeRowSourceAddable(HomeRowDefinition.SYSTEM_EMBY, "recent", empty.copy(embyEnabled = true)))
        assertTrue(homeRowSourceAddable(HomeRowDefinition.SYSTEM_EMBY, "recent", empty.copy(embyEnabled = true, embyConfigured = true)))
        assertFalse(homeRowSourceAddable(HomeRowDefinition.SYSTEM_TVHEADEND, "live", empty.copy(tvheadendEnabled = true)))
        assertTrue(homeRowSourceAddable(HomeRowDefinition.SYSTEM_TVHEADEND, "live", empty.copy(tvheadendEnabled = true, tvheadendHost = "tvh")))
        // #136: SmartTube serves the home feed without a sign-in.
        assertTrue(homeRowSourceAddable(HomeRowDefinition.SYSTEM_YOUTUBE, "home", empty.copy(youtubeEnabled = true, youtubeAvailable = true)))
        assertFalse(homeRowSourceAddable(HomeRowDefinition.SYSTEM_YOUTUBE, "home", empty.copy(
            youtubeEnabled = true, youtubeAvailable = false, youtubeSignedIn = true,
        )))
        assertTrue(homeRowSourceAddable(HomeRowDefinition.SYSTEM_YOUTUBE, "home", empty.copy(
            youtubeEnabled = true, youtubeAvailable = true, youtubeSignedIn = true,
        )))
        assertFalse(homeRowSourceAddable(HomeRowDefinition.SYSTEM_YOUTUBE, "history", empty.copy(
            youtubeEnabled = true, youtubeAvailable = true, youtubeSignedIn = true,
            youtubeSupportedFeedKeys = setOf("home"),
        )))
        assertFalse(homeRowSourceAddable(HomeRowDefinition.SYSTEM_SPOTIFY, "liked", empty.copy(spotifyEnabled = true)))
        assertTrue(homeRowSourceAddable(HomeRowDefinition.SYSTEM_SPOTIFY, "liked", empty.copy(spotifyEnabled = true, spotifyWebAuthorized = true)))
    }
    @Test fun refreshKeepsDraftAndAcceptsUneditedPlayerDefaults() {
        val baseline = SettingsUiState()
        val draft = baseline.copy(uiScale = 0.9f)
        val stored = baseline.copy(audioSettings = baseline.audioSettings.update(AudioOptions(output = "pcm")))
        val merged = mergeStoredSettings(draft, baseline, stored)
        assertEquals(0.9f, merged.uiScale)
        assertEquals("pcm", merged.audioSettings.current.output)
        assertNotEquals(stored.editableValues(), merged.editableValues())
    }

    @Test fun refreshDoesNotOverwriteEditedProfilesWithBackgroundValues() {
        val baseline = SettingsUiState()
        val draft = baseline.copy(mpvImageOptions = MpvImageOptions(scaler = "nnedi3_nns16"))
        val stored = baseline.copy(mpvImageOptions = MpvImageOptions(scaler = "fsr"), fileSourceCount = 3)
        val merged = mergeStoredSettings(draft, baseline, stored)
        assertEquals("nnedi3_nns16", merged.mpvImageOptions.scaler)
        assertEquals(3, merged.fileSourceCount)
    }

    @Test fun refreshOfAnUneditedDraftIsClean() {
        val baseline = SettingsUiState()
        val stored = baseline.copy(filesEnabled = true, fileSourceCount = 1, playbackBackendKey = "vlc")
        val merged = mergeStoredSettings(baseline, baseline, stored)
        assertEquals(stored.editableValues(), merged.editableValues())
    }

    @Test fun refreshPicksUpEmbySessionAndWireGuardFromStoredSettings() {
        val baseline = SettingsUiState()
        val stored = baseline.copy(
            embyConfigured = true,
            embyEnabled = true,
            savedEmbyServers = listOf(
                com.ambient.tv.domain.model.EmbyServerProfile(
                    id = "em", name = "Emby", host = "192.168.1.8", port = 8096,
                ),
            ),
            activeEmbyServerId = "em",
            wireGuardEmbyEnabled = true,
        )
        val merged = mergeStoredSettings(baseline, baseline, stored)
        assertTrue(merged.embyConfigured)
        assertEquals("em", merged.activeEmbyServerId)
        assertEquals(1, merged.savedEmbyServers.size)
        assertTrue(merged.wireGuardEmbyEnabled)
    }

    @Test
    fun searchTargetsKeepTheirIdentityWhenSectionsMove() {
        assertEquals("appearance:scale", settingsSearchTargetKey(SettingsCategory.APPEARANCE, "ui scale"))
        assertEquals("appearance:theme", settingsSearchTargetKey(SettingsCategory.APPEARANCE, "oled theme"))
        assertEquals("appearance:hdr", settingsSearchTargetKey(SettingsCategory.APPEARANCE, "HDR"))
        assertEquals("playback:test-duration", settingsSearchTargetKey(SettingsCategory.PLAYBACK, "Prüfdauer Bildaussetzer"))
        assertEquals("playback:renderer", settingsSearchTargetKey(SettingsCategory.PLAYBACK, "MPV backend"))
        assertEquals("background:video", settingsSearchTargetKey(SettingsCategory.BACKGROUND, "Hintergrundvideo"))
        val index = SettingsItemIndex()
        assertEquals(0, index.landingPosition(settingsSearchTargetKey(SettingsCategory.BACKGROUND, "Lichtstimmung"), requested = true))
        assertEquals("spotify:account", settingsSearchTargetKey(SettingsCategory.SPOTIFY, "client ID"))
        assertEquals("seek:controls", settingsSearchTargetKey(SettingsCategory.SEEK, "Fortsetzen"))
        assertTrue(settingsSearchPrefersStartPageTab("home widgets"))
        assertEquals(SettingsCategory.SPOTIFY, SettingsCategory.fromKey("advanced"))
    }

    @Test fun offlineShortcutOpensExistingWakePageAndTargetsSpecificMacGroup() {
        assertEquals(SettingsCategory.WAKE, settingsShortcutCategory("wake:plex"))
        assertEquals("plex", settingsShortcutWakeTarget("wake:plex"))
        assertEquals("jellyfin", settingsShortcutWakeTarget("wake:jellyfin"))
        assertEquals("emby", settingsShortcutWakeTarget("wake:emby"))
        assertEquals("tvheadend", settingsShortcutWakeTarget("wake:tvheadend"))
        assertEquals(SettingsCategory.PLEX, settingsShortcutCategory("plex"))
        assertNull(settingsShortcutWakeTarget("plex"))
        assertNull(settingsShortcutWakeTarget("wake:unknown"))
        assertNull(settingsShortcutWakeTarget(null))
    }
}
