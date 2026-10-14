package com.ambient.tv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarNavigationPolicyTest {
    @Test fun restoresRememberedChildEvenAfterListReordering() {
        assertEquals("series", chooseSidebarChild(listOf("music", "series", "home"), "series", "home"))
    }
    @Test fun removedChildFallsBackToActiveThenFirstAvailable() {
        assertEquals("home", chooseSidebarChild(listOf("music", "home"), "deleted", "home"))
        assertEquals("music", chooseSidebarChild(listOf("music", "home"), "deleted", "hidden"))
        assertNull(chooseSidebarChild(emptyList(), "deleted", "hidden"))
    }
    @Test fun reorderMovesPastDisabledSystemsWithoutDroppingThem() {
        assertEquals(listOf("spotify", "plex", "jellyfin", "youtube", "tvheadend"),
            reorderedSidebarSystems(listOf("plex", "jellyfin", "youtube", "spotify", "tvheadend"),
                "spotify", -1, listOf("plex", "spotify")))
    }
    @Test fun orderBoundariesAndUnknownSystemsAreSafe() {
        val order = listOf("plex", "youtube")
        assertEquals(order, reorderedSidebarSystems(order, "plex", -1, order))
        assertEquals(order, reorderedSidebarSystems(order, "youtube", 1, order))
        assertEquals(order, reorderedSidebarSystems(order, "missing", 1, order))
    }

    @Test fun contentHandoffWaitsForTheSelectedRoute() {
        assertFalse(sidebarContentHandoffReady(true, true, "plex", "home"))
        assertTrue(sidebarContentHandoffReady(true, true, "home", "home"))
        assertTrue(sidebarContentHandoffReady(true, true, "plex", null))
        assertFalse(sidebarContentHandoffReady(true, false, "home", "home"))
        assertFalse(sidebarContentHandoffReady(false, true, "home", "home"))
    }

    @Test fun contentFocusCanLeaveTheRailBeforeTheDestinationIsReady() {
        assertTrue(sidebarContentFocusAllowed(true, "home", "home"))
        assertTrue(sidebarContentFocusAllowed(true, "plex", null))
        assertFalse(sidebarContentFocusAllowed(true, "plex", "home"))
        assertFalse(sidebarContentFocusAllowed(false, "home", "home"))
        assertFalse(sidebarContentHandoffReady(true, false, "home", "home"))
    }

    @Test fun globalSearchHiddenWhenOnlyFilesWouldBeAvailable() {
        assertFalse(
            sidebarShowsGlobalSearch(
                plexConfigured = false,
                jellyfinConfigured = false,
                embyConfigured = false,
                tvheadendConfigured = false,
                youtubeEnabled = false,
                spotifyCanSearch = false,
            ),
        )
    }


    @Test fun globalSearchShownForConfiguredMediaBackends() {
        assertTrue(
            sidebarShowsGlobalSearch(
                plexConfigured = true,
                jellyfinConfigured = false,
                embyConfigured = false,
                tvheadendConfigured = false,
                youtubeEnabled = false,
                spotifyCanSearch = false,
            ),
        )
        assertTrue(
            sidebarShowsGlobalSearch(
                plexConfigured = false,
                jellyfinConfigured = false,
                embyConfigured = false,
                tvheadendConfigured = true,
                youtubeEnabled = false,
                spotifyCanSearch = false,
            ),
        )
        assertTrue(
            sidebarShowsGlobalSearch(
                plexConfigured = false,
                jellyfinConfigured = false,
                embyConfigured = false,
                tvheadendConfigured = false,
                youtubeEnabled = true,
                spotifyCanSearch = false,
            ),
        )
        // Spotify toggle alone is not enough — SearchViewModel needs auth + native.
        assertFalse(
            sidebarShowsGlobalSearch(
                plexConfigured = false,
                jellyfinConfigured = false,
                embyConfigured = false,
                tvheadendConfigured = false,
                youtubeEnabled = false,
                spotifyCanSearch = false,
            ),
        )
        assertTrue(
            sidebarShowsGlobalSearch(
                plexConfigured = false,
                jellyfinConfigured = false,
                embyConfigured = false,
                tvheadendConfigured = false,
                youtubeEnabled = false,
                spotifyCanSearch = true,
            ),
        )
    }

    @Test fun homeHiddenForFilesOnlySetup() {
        assertFalse(
            sidebarShowsHome(
                plexConfigured = false,
                jellyfinConfigured = false,
                embyConfigured = false,
                tvheadendConfigured = false,
                youtubeEnabled = false,
                spotifyEnabled = false,
            ),
        )
        assertTrue(
            sidebarShowsHome(
                plexConfigured = false,
                jellyfinConfigured = false,
                embyConfigured = false,
                tvheadendConfigured = false,
                youtubeEnabled = false,
                spotifyEnabled = true,
            ),
        )
    }
}
