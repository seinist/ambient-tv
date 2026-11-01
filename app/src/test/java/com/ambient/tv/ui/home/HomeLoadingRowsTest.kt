package com.ambient.tv.ui.home

import com.ambient.tv.data.local.HomeRowDefinition
import com.ambient.tv.ui.common.MediaTileStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLoadingRowsTest {
    @Test fun loadingTilesFollowEnabledRowFormats() {
        val rows = listOf(
            HomeRowDefinition(id = "live", system = "tvheadend", contentKey = "live", displayMode = "poster"),
            HomeRowDefinition(id = "recent", system = "plex", contentKey = "recent", displayMode = "landscape"),
            HomeRowDefinition(id = "disabled", system = "plex", contentKey = "continue", enabled = false),
            HomeRowDefinition(id = "poster", system = "radio", contentKey = "popular"),
        )
        assertEquals(
            listOf(MediaTileStyle.LANDSCAPE, MediaTileStyle.LANDSCAPE, MediaTileStyle.POSTER),
            homeLoadingRowStyles(rows),
        )
        // An overview owns its own default rows, even when they were not added to Home.
        assertEquals(
            listOf(MediaTileStyle.POSTER, MediaTileStyle.LANDSCAPE, MediaTileStyle.POSTER),
            homeLoadingRowStyles(rows, overviewSystem = "plex"),
        )
    }

    @Test fun smartTubeSkeletonIsLandscapeLikeItsRows() {
        val rows = listOf(HomeRowDefinition(id = "subs", system = HomeRowDefinition.SYSTEM_YOUTUBE,
            contentKey = "subscriptions", displayMode = "poster"))
        assertEquals(MediaTileStyle.LANDSCAPE, homeLoadingRowStyles(rows).first())
    }

    @Test fun unknownRowsUseUnscaledPosterDefaults() {
        assertEquals(List(3) { MediaTileStyle.POSTER }, homeLoadingRowStyles(emptyList()))
        assertEquals(List(3) { MediaTileStyle.POSTER }, homeLoadingRowStyles(emptyList(), overviewSystem = "emby"))
    }

    @Test fun systemOverviewSkeletonUsesItsOwnLandscapeSetting() {
        assertEquals(
            listOf(MediaTileStyle.CONTINUE, MediaTileStyle.LANDSCAPE, MediaTileStyle.LANDSCAPE),
            homeLoadingRowStyles(emptyList(), overviewSystem = "plex", overviewPageMode = HomeRowDefinition.DISPLAY_LANDSCAPE),
        )
    }

    @Test fun hiddenSystemRowsDoNotGetSkeletons() {
        val rows = listOf(HomeRowDefinition(id = "recent", system = "plex", contentKey = "recent", displayMode = "landscape"))
        assertEquals(
            listOf(MediaTileStyle.LANDSCAPE, MediaTileStyle.POSTER, MediaTileStyle.POSTER),
            homeLoadingRowStyles(rows, overviewSystem = "plex", systemHomeHiddenRows = setOf("plex.continue")),
        )
    }

    @Test fun loadingRowsTrackOverviewViewModeSwitchWithoutRecreatingTheScreen() {
        val rows = emptyList<HomeRowDefinition>()
        assertEquals(List(3) { MediaTileStyle.POSTER },
            homeLoadingRowStyles(rows, overviewSystem = "plex",
                overviewPageMode = HomeRowDefinition.DISPLAY_POSTER))
        assertEquals(listOf(MediaTileStyle.CONTINUE, MediaTileStyle.LANDSCAPE, MediaTileStyle.LANDSCAPE),
            homeLoadingRowStyles(rows, overviewSystem = "plex",
                overviewPageMode = HomeRowDefinition.DISPLAY_LANDSCAPE))
    }

    @Test fun loadingRowsTrackHomeEditorCardFormatChanges() {
        val poster = HomeRowDefinition(
            id = "recent", system = "plex", contentKey = "recent", displayMode = "poster",
        )
        assertEquals(listOf(MediaTileStyle.POSTER), homeLoadingRowStyles(listOf(poster)))
        assertEquals(listOf(MediaTileStyle.LANDSCAPE),
            homeLoadingRowStyles(listOf(poster.copy(displayMode = "landscape"))))
    }

    @Test fun atMostThreeVisibleSkeletonRowsArePrepared() {
        val rows = (0 until 6).map { index ->
            HomeRowDefinition(id = "row$index", system = "plex", contentKey = "row$index")
        }
        assertEquals(3, homeLoadingRowStyles(rows).size)
    }
}
