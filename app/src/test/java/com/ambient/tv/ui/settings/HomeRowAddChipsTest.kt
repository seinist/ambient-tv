package com.ambient.tv.ui.settings

import com.ambient.tv.data.local.HomeRowDefinition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowAddChipsTest {
    @Test
    fun alreadyPresentIncludesDisabledRows() {
        val state = SettingsUiState(
            homeRows = listOf(
                HomeRowDefinition(
                    system = HomeRowDefinition.SYSTEM_PLEX,
                    contentKey = "continue",
                    enabled = false,
                ),
            ),
        )
        assertTrue(homeRowAlreadyPresent(HomeRowDefinition.SYSTEM_PLEX, "continue", state))
        assertFalse(homeRowAlreadyPresent(HomeRowDefinition.SYSTEM_PLEX, "recent", state))
    }

    @Test
    fun genrePresenceUsesTopGenreContentKey() {
        val drama = HomeRowDefinition.topGenre("Drama")
        val state = SettingsUiState(
            homeRows = listOf(
                HomeRowDefinition(system = HomeRowDefinition.SYSTEM_JELLYFIN, contentKey = drama),
            ),
        )
        assertTrue(homeRowAlreadyPresent(HomeRowDefinition.SYSTEM_JELLYFIN, drama, state))
        assertFalse(homeRowAlreadyPresent(HomeRowDefinition.SYSTEM_JELLYFIN, HomeRowDefinition.topGenre("Comedy"), state))
        assertFalse(homeRowAlreadyPresent(HomeRowDefinition.SYSTEM_JELLYFIN, HomeRowDefinition.CONTENT_TOP_GENRE, state))
    }

    @Test
    fun addChipVisibleHidesPresentTypesButKeepsGenreOpener() {
        val ready = SettingsUiState(
            plexEnabled = true,
            plexConfigured = true,
            homeRows = listOf(
                HomeRowDefinition(system = HomeRowDefinition.SYSTEM_PLEX, contentKey = "continue", enabled = false),
            ),
        )
        // homeRowAddChipVisible is private in SettingsScreen; mirror the rule here via helpers.
        assertTrue(homeRowSourceAddable(HomeRowDefinition.SYSTEM_PLEX, "continue", ready))
        assertTrue(homeRowAlreadyPresent(HomeRowDefinition.SYSTEM_PLEX, "continue", ready))
        assertFalse(homeRowAlreadyPresent(HomeRowDefinition.SYSTEM_PLEX, HomeRowDefinition.CONTENT_TOP_GENRE, ready))
        assertTrue(homeRowSourceAddable(HomeRowDefinition.SYSTEM_PLEX, HomeRowDefinition.CONTENT_WATCHED, ready))
    }

    @Test
    fun distinctBySystemAndContentKeyCollapsesDuplicates() {
        val rows = listOf(
            HomeRowDefinition(id = "a", system = HomeRowDefinition.SYSTEM_PLEX, contentKey = "recent"),
            HomeRowDefinition(id = "b", system = HomeRowDefinition.SYSTEM_PLEX, contentKey = "recent", enabled = false),
            HomeRowDefinition(id = "c", system = HomeRowDefinition.SYSTEM_PLEX, contentKey = "continue"),
        )
        val collapsed = rows.distinctBy { it.system to it.contentKey }
        assertTrue(collapsed.size == 2)
        assertTrue(collapsed.first().id == "a")
        assertTrue(collapsed.any { it.contentKey == "continue" })
    }

    /**
     * SmartTube enabled but signed out: the feeds that need an account stay out of the picker,
     * while the ones that work without one can be put on Home. Before this, no SmartTube row
     * could be added at all while signed out.
     */
    @Test
    fun signedOutSmartTubeStillOffersTheFeedsThatWorkWithoutAnAccount() {
        val signedOut = SettingsUiState(
            youtubeEnabled = true,
            youtubeAvailable = true,
            youtubeAuthKnown = true,
            youtubeSignedIn = false,
            // What SmartTubeEngine.supportedFeedKeys() reports for a signed-out user.
            youtubeSupportedFeedKeys = setOf("shorts", "music", "news", "gaming", "live", "kids"),
        )
        for (key in listOf("shorts", "music", "news", "gaming", "live", "kids")) {
            assertTrue(key, homeRowSourceAddable(HomeRowDefinition.SYSTEM_YOUTUBE, key, signedOut))
        }
        for (key in listOf("home", "subscriptions", "history", "playlists")) {
            assertFalse(key, homeRowSourceAddable(HomeRowDefinition.SYSTEM_YOUTUBE, key, signedOut))
        }
    }

    @Test
    fun signedInSmartTubeOffersTheAccountFeedsToo() {
        val signedIn = SettingsUiState(
            youtubeEnabled = true,
            youtubeAvailable = true,
            youtubeAuthKnown = true,
            youtubeSignedIn = true,
            youtubeSupportedFeedKeys = setOf("home", "subscriptions", "history", "playlists", "shorts"),
        )
        for (key in listOf("home", "subscriptions", "history", "playlists", "shorts")) {
            assertTrue(key, homeRowSourceAddable(HomeRowDefinition.SYSTEM_YOUTUBE, key, signedIn))
        }
        assertFalse(homeRowSourceAddable(HomeRowDefinition.SYSTEM_YOUTUBE, "music", signedIn))
    }

    /** No engine (no SmartTube module): nothing to offer, whatever the preference says. */
    @Test
    fun smartTubeWithoutEngineOffersNothing() {
        val unavailable = SettingsUiState(
            youtubeEnabled = true,
            youtubeAvailable = false,
            youtubeAuthKnown = true,
            youtubeSupportedFeedKeys = setOf("shorts"),
        )
        assertFalse(homeRowSourceAddable(HomeRowDefinition.SYSTEM_YOUTUBE, "shorts", unavailable))
    }

    /** Disabled in settings: not offered even when the engine reports feeds. */
    @Test
    fun disabledSmartTubeOffersNothing() {
        val off = SettingsUiState(
            youtubeEnabled = false,
            youtubeAvailable = true,
            youtubeAuthKnown = true,
            youtubeSupportedFeedKeys = setOf("shorts"),
        )
        assertFalse(homeRowSourceAddable(HomeRowDefinition.SYSTEM_YOUTUBE, "shorts", off))
    }
}
