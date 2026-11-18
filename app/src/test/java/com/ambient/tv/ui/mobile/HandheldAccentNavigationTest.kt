package com.ambient.tv.ui.mobile

import com.ambient.tv.ui.navigation.Destinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandheldAccentNavigationTest {
    @Test fun selectedTabsAreExclusiveEvenWhenCompanionOpensOverBrowse() {
        for (route in listOf(Destinations.HOME, Destinations.PLEX, Destinations.SEARCH, Destinations.SETTINGS)) {
            val selected = HandheldNavTab.entries.filter { handheldNavTabSelected(route, true, it) }
            assertEquals(listOf(HandheldNavTab.REMOTE), selected)
        }
    }

    @Test fun portraitAndLandscapeUseOneTabSelectionPolicy() {
        val routes = mapOf(
            Destinations.HOME to HandheldNavTab.HOME,
            Destinations.PLEX to HandheldNavTab.SOURCES,
            Destinations.SEARCH to HandheldNavTab.SEARCH,
            Destinations.SETTINGS to HandheldNavTab.SETTINGS,
            Destinations.RADIO to HandheldNavTab.SOURCES,
        )
        routes.forEach { (route, tab) ->
            assertEquals(listOf(tab), HandheldNavTab.entries.filter { handheldNavTabSelected(route, false, it) })
        }
        assertFalse(handheldNavTabSelected(Destinations.PLEX, false, HandheldNavTab.REMOTE))
    }

    @Test fun sourceHighlightsOnlyTheCurrentConcreteRouteNotEveryLibrarySection() {
        val movies = Destinations.section("plex:movies")
        val series = Destinations.section("plex:series")
        assertTrue(handheldSourceSelected(movies, movies))
        assertFalse(handheldSourceSelected(movies, series))
        assertFalse(handheldSourceSelected(Destinations.SECTION, movies))
        assertTrue(handheldSourceSelected(Destinations.WEATHER, Destinations.WEATHER))
        // Every per-system Settings shortcut shares the same route, but none owns global Settings.
        assertFalse(handheldSourceSelected(Destinations.SETTINGS, Destinations.SETTINGS, "plex"))
        assertFalse(handheldSourceSelected(null, movies))
    }

    @Test fun miniPlayerTimelineKeepsLiveAndUnknownDurationEmpty() {
        assertEquals(0f, handheldMiniPlayerProgress(10_000L, 0L, live = false), .001f)
        assertEquals(0f, handheldMiniPlayerProgress(10_000L, 20_000L, live = true), .001f)
        assertEquals(0.5f, handheldMiniPlayerProgress(10_000L, 20_000L, live = false), .001f)
        assertEquals(1f, handheldMiniPlayerProgress(99_000L, 20_000L, live = false), .001f)
        assertEquals(0f, handheldMiniPlayerProgress(-500L, 20_000L, live = false), .001f)
    }
}
