package com.ambient.tv.ui.home

import com.ambient.tv.data.local.HomeRowDefinition
import com.ambient.tv.data.local.homeSystemReady
import com.ambient.tv.ui.common.MediaTileStyle
import org.junit.Assert.*
import org.junit.Test

class HomeRowOrderTest {
    private fun row(system: String, key: String) = HomeRowDefinition(id = "$system:$key", system = system, contentKey = key)

    @Test fun mixedRowsRespectTheHomeEditorOrder() {
        val shortcut = row("youtube", "home")
        val definitions = listOf(row("plex", "continue"), row("tvheadend", "recent_channels"), shortcut, row("tvheadend", "live"), row("plex", "recent"))
        assertEquals(listOf("plex.continue", "recent-tv", "shortcut:youtube:home", "live-tv", "plex.recentlyAdded", "plex.recentlyReleased"),
            orderedHomeRowKeys(definitions, listOf("plex.continue", "plex.recentlyAdded", "plex.recentlyReleased"), listOf(shortcut), true, true))
    }

    @Test fun emptyRecentRowDoesNotReserveAFocusDestination() {
        assertEquals(listOf("plex.continue"), orderedHomeRowKeys(listOf(row("tvheadend", "recent_channels")), listOf("plex.continue"), emptyList(), false, false))
    }

    @Test fun asynchronouslyArrivingTvRowsKeepExistingKeysStable() {
        val definitions = listOf(row("tvheadend", "live"), row("plex", "continue"), row("jellyfin", "libraries"))
        val ids = listOf("plex.continue", "jellyfin.library.2")
        val before = orderedHomeRowKeys(definitions, ids, emptyList(), false, false)
        val after = orderedHomeRowKeys(definitions, ids, emptyList(), true, false)
        assertEquals(before, after.filter { it != "live-tv" })
        assertEquals("live-tv", after.first())
    }
    @Test fun everyContentRowMapsBackToItsWidget() {
        assertEquals("plex.continue", homeRowSource("plex.continue"))
        assertEquals("plex.recent", homeRowSource("plex.recentlyAddedEpisodes"))
        assertEquals("emby.collections", homeRowSource("emby.collection.12"))
        assertEquals("jellyfin.libraries", homeRowSource("jellyfin.library.jellyfin:abc"))
        assertEquals("plex.watched", homeRowSource("plex.watched"))
        assertEquals("plex.top_genre:Sci-Fi & Fantasy", homeRowSource("plex.top_genre:Sci-Fi & Fantasy"))
        assertEquals("plex.hubs", homeRowSource("plex.hub.1.movie.genre.12"))
        assertEquals("photos.recent", homeRowSource("photos.recent"))
    }

    @Test fun rankedRowsFollowTheHomeEditorOrder() {
        val definitions = listOf(row("plex", "top_genre:Drama"), row("plex", "watched"), row("plex", "continue"), row("plex", "hubs"))
        assertEquals(listOf("plex.top_genre:Drama", "plex.watched", "plex.continue", "plex.hub.1.movie.rediscover"),
            orderedHomeRowKeys(definitions, listOf("plex.continue", "plex.hub.1.movie.rediscover", "plex.watched", "plex.top_genre:Drama"), emptyList(), false, false))
    }

    @Test fun aSystemPageKeepsTheOrderItsRowsWereBuiltIn() {
        val ids = listOf("plex.continue", "plex.top_unwatched", "plex.watched", "plex.library.1")
        assertEquals(ids, orderedHomeRowKeys(emptyList(), ids, emptyList(), false, false))
    }

    @Test fun homeOnlyServesRowsFromReadySystems() {
        assertTrue(homeSystemReady("plex", true, false, false, false, false))
        assertFalse(homeSystemReady("plex", false, true, true, true, true))
        assertFalse(homeSystemReady("jellyfin", true, false, true, true, true))
        assertTrue(homeSystemReady("emby", false, false, false, false, false, embyReady = true))
        assertFalse(homeSystemReady("emby", true, true, true, true, true, embyReady = false))
        assertTrue(homeSystemReady("youtube", false, false, false, true, false))
        assertFalse(homeSystemReady("files", true, true, true, true, true))
    }

    @Test fun continueWatchingPosterModeUsesPosterTiles() {
        assertEquals(MediaTileStyle.POSTER, homeRowTileStyle(HomeRowDefinition.DISPLAY_POSTER, MediaTileStyle.CONTINUE))
        assertEquals(MediaTileStyle.POSTER, homeRowTileStyle(HomeRowDefinition.DISPLAY_POSTER, MediaTileStyle.CONTINUE, overviewForcedPoster = true))
    }

    @Test fun continueWatchingLandscapeKeepsContinueTiles() {
        assertEquals(MediaTileStyle.CONTINUE, homeRowTileStyle(HomeRowDefinition.DISPLAY_LANDSCAPE, MediaTileStyle.CONTINUE))
        assertEquals(MediaTileStyle.CONTINUE, homeRowTileStyle(HomeRowDefinition.DISPLAY_LANDSCAPE, MediaTileStyle.CONTINUE, overviewForcedPoster = true))
    }

    @Test fun legacyDuplicatesUseTheFirstEnabledDefinitionLikeTheDataLoader() {
        val definitions = listOf(row("tvheadend", "live").copy(enabled = false), row("plex", "continue"),
            row("tvheadend", "live"), row("plex", "continue"))
        assertEquals(listOf("plex.continue", "live-tv"), orderedHomeRowKeys(definitions,
            listOf("plex.continue"), emptyList(), true, false))
    }
}
