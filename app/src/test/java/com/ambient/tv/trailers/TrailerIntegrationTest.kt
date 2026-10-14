package com.ambient.tv.trailers

import com.ambient.tv.data.local.HomeRowDefinition
import com.ambient.tv.data.local.homeSystemReady
import com.ambient.tv.ui.SIDEBAR_ROUTES
import com.ambient.tv.ui.FULLSCREEN_ROUTES
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.home.homeRowTileStyle
import com.ambient.tv.ui.home.orderedHomeRowKeys
import com.ambient.tv.ui.common.MediaTileStyle
import org.junit.Assert.*
import org.junit.Test

class TrailerIntegrationTest {
    @Test fun dateSortUsesMonthAndDayAndTitlesFallBackToOriginal() {
        val video = TrailerVideo("abcdefghijk", type = "Trailer")
        val january = TrailerEntry(TrailerTitle(1, title = "Zulu", releaseDate = "2026-01-10"), "movie", listOf(video))
        val december = TrailerEntry(TrailerTitle(2, originalName = "Series", firstAirDate = "2026-12-01"), "tv", listOf(video))
        assertEquals("Series", december.title.displayTitle)
        assertEquals(listOf(december, january), applyTrailerViewOptions(listOf(january, december), TrailerSort.DATE, TrailerFilter.ALL))
    }

    @Test fun catalogIsAMenuDestinationAndItemPickerIsFullscreen() {
        assertTrue(Destinations.TRAILERS in SIDEBAR_ROUTES)
        assertTrue(Destinations.TRAILER_FEED in SIDEBAR_ROUTES)
        assertEquals("trailers/feed/{feedKey}", Destinations.TRAILER_FEED)
        assertFalse(Destinations.TRAILERS in FULLSCREEN_ROUTES)
        assertTrue(Destinations.ITEM_TRAILERS in FULLSCREEN_ROUTES)
        assertTrue(Destinations.TMDB_TRAILERS in FULLSCREEN_ROUTES)
        assertTrue(Destinations.YOUTUBE_PLAYER in FULLSCREEN_ROUTES)
    }

    @Test fun trailerWidgetsFollowEditorOrderAndDoNotRequireOtherSystems() {
        val definitions = listOf("SERIES", "CINEMA").map { HomeRowDefinition(system = "trailers", contentKey = it) }
        assertEquals(listOf("trailers.SERIES", "trailers.CINEMA"), orderedHomeRowKeys(
            definitions, listOf("trailers.CINEMA", "trailers.SERIES"), emptyList(), false, false))
        assertTrue(homeSystemReady("trailers", false, false, false, false, false, trailersReady = true))
        assertFalse(homeSystemReady("trailers", true, true, true, true, true))
        assertEquals(MediaTileStyle.POSTER, homeRowTileStyle("poster", MediaTileStyle.LANDSCAPE))
        assertEquals(MediaTileStyle.LANDSCAPE, homeRowTileStyle("landscape", MediaTileStyle.LANDSCAPE))
    }

    @Test fun trailerViewOptionsFilterAndSortWithoutLosingEntries() {
        val trailer = TrailerVideo("abcdefghijk", site = "YouTube", type = "Trailer", official = true)
        val teaser = TrailerVideo("12345678901", site = "YouTube", type = "Teaser")
        val later = TrailerEntry(TrailerTitle(2, title = "Zulu", releaseDate = "2026-01-01"), "movie", listOf(teaser))
        val earlier = TrailerEntry(TrailerTitle(1, title = "Alpha", releaseDate = "2020-01-01"), "movie", listOf(trailer, teaser))
        val officialOnly = applyTrailerViewOptions(listOf(later, earlier), TrailerSort.DEFAULT, TrailerFilter.OFFICIAL)
        assertEquals(listOf(1), officialOnly.map { it.title.id })
        assertEquals(listOf(trailer), officialOnly.single().videos)
        assertEquals(listOf(earlier, later), applyTrailerViewOptions(listOf(later, earlier), TrailerSort.TITLE, TrailerFilter.ALL))
        assertEquals(listOf(later, earlier), applyTrailerViewOptions(listOf(earlier, later), TrailerSort.DATE, TrailerFilter.ALL))
    }
}
