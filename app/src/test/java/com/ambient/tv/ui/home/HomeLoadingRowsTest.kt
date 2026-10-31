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
        assertEquals(
            listOf(MediaTileStyle.LANDSCAPE),
            homeLoadingRowStyles(rows, overviewSystem = "plex"),
        )
    }

    @Test fun unknownRowsUseUnscaledPosterDefaults() {
        assertEquals(List(3) { MediaTileStyle.POSTER }, homeLoadingRowStyles(emptyList()))
        assertEquals(List(3) { MediaTileStyle.POSTER }, homeLoadingRowStyles(emptyList(), overviewSystem = "emby"))
    }

    @Test fun atMostThreeVisibleSkeletonRowsArePrepared() {
        val rows = (0 until 6).map { index ->
            HomeRowDefinition(id = "row$index", system = "plex", contentKey = "row$index")
        }
        assertEquals(3, homeLoadingRowStyles(rows).size)
    }
}
