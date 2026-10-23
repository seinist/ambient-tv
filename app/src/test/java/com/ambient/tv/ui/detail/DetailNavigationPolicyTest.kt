package com.ambient.tv.ui.detail

import com.ambient.tv.ui.FULLSCREEN_ROUTES
import com.ambient.tv.ui.navigation.Destinations
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailNavigationPolicyTest {
    @Test fun seriesAndSeasonsExposeWatchedActionsWithoutPretendingToPlay() {
        listOf("show", "season").forEach { type ->
            val actions = detailActionCapabilities(type, playbackAvailable = true, hasMediaVersions = false, inProgress = false)
            assertTrue(type, actions.canSetWatched)
            assertFalse(type, actions.canRestart)
            assertTrue(type, actions.hasMenuActions)
        }
    }

    @Test fun collectionsAndMusicContainersDoNotExposeDeadMenuActions() {
        listOf("collection", "artist", "album").forEach { type ->
            assertFalse(type, detailActionCapabilities(type, true, false, false).hasMenuActions)
        }
    }

    @Test fun offlineDetailsDoNotOfferServerMutations() {
        val actions = detailActionCapabilities("show", playbackAvailable = false, hasMediaVersions = false, inProgress = true)
        assertFalse(actions.canSetWatched)
        assertFalse(actions.canRemoveFromContinue)
        assertFalse(actions.hasMenuActions)
    }

    @Test fun childrenStatusRemainsVisibleWhileAContainerIsEmpty() {
        listOf("show", "season", "collection").forEach { assertTrue(it, detailShowsChildrenSection(it)) }
        assertFalse(detailShowsChildrenSection("movie"))
    }

    @Test fun detailDrillDownIsFullscreenWhileLibraryFiltersKeepTheirSidebar() {
        assertTrue(FULLSCREEN_ROUTES.contains(Destinations.DETAIL_FILTERED_BROWSE))
        assertFalse(FULLSCREEN_ROUTES.contains(Destinations.FILTERED_BROWSE))
    }

}
