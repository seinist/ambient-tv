package com.ambient.tv.data.local

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeConfigurationTest {
    @Test fun posterDefaultReplacesFormerLandscapeRows() {
        val plex = HomeRowDefinition(system = HomeRowDefinition.SYSTEM_PLEX, contentKey = "recent", displayMode = HomeRowDefinition.DISPLAY_LANDSCAPE)
        val live = HomeRowDefinition(system = HomeRowDefinition.SYSTEM_TVHEADEND, contentKey = "live", displayMode = HomeRowDefinition.DISPLAY_LANDSCAPE)
        val alreadyPoster = HomeRowDefinition(system = HomeRowDefinition.SYSTEM_JELLYFIN, contentKey = "libraries")
        val migrated = listOf(plex, live, alreadyPoster).withDefaultPosterCards()
        assertEquals(HomeRowDefinition.DISPLAY_POSTER, migrated[0].displayMode)
        assertEquals(HomeRowDefinition.DISPLAY_LANDSCAPE, migrated[1].displayMode)
        assertEquals(HomeRowDefinition.DISPLAY_POSTER, migrated[2].displayMode)
        val trailers = HomeRowDefinition(system = HomeRowDefinition.SYSTEM_TRAILERS, contentKey = "popular", displayMode = HomeRowDefinition.DISPLAY_LANDSCAPE)
        assertEquals(HomeRowDefinition.DISPLAY_POSTER, trailers.withDefaultPosterCards().displayMode)
    }

    @Test fun savedLandscapePageViewsBecomePosterExceptYouTube() {
        val raw = memoryPreferences().apply {
            edit().putString("section_view_plex:1:recommendations", "LANDSCAPE")
                .putString("section_view_trailers:POPULAR", "LANDSCAPE")
                .putString("section_view_plex:1:library", "LIST")
                .putString("section_view_youtube:home", "LANDSCAPE").commit()
        }
        val prefs = AppPreferences(raw)
        assertEquals("POSTER", prefs.sectionViewMode("plex:1:recommendations"))
        assertEquals("POSTER", prefs.sectionViewMode("trailers:POPULAR"))
        assertEquals("LIST", prefs.sectionViewMode("plex:1:library"))
        assertEquals("LANDSCAPE", prefs.sectionViewMode("youtube:home"))
        prefs.setSectionViewMode("plex:1:recommendations", "LANDSCAPE")
        assertEquals("LANDSCAPE", AppPreferences(raw).sectionViewMode("plex:1:recommendations"))
    }

    @Test fun genreRowsCarryTheirGenreInTheContentKey() {
        val row = HomeRowDefinition(system = HomeRowDefinition.SYSTEM_EMBY, contentKey = HomeRowDefinition.topGenre("Sci-Fi & Fantasy"))
        assertEquals("Sci-Fi & Fantasy", row.genre)
        assertEquals(HomeRowDefinition.CONTENT_TOP_GENRE, homeRowType(row.contentKey))
        assertEquals(null, HomeRowDefinition(system = HomeRowDefinition.SYSTEM_EMBY, contentKey = "recent").genre)
        assertEquals(null, HomeRowDefinition(system = HomeRowDefinition.SYSTEM_EMBY, contentKey = "top_genre:").genre)
    }

    @Test fun onlyPlexOffersServerHubsAndKeepsThemOffItsPageByDefault() {
        assertEquals(true, HomeRowDefinition.CONTENT_HUBS in systemHomeRowTypes(HomeRowDefinition.SYSTEM_PLEX))
        assertEquals(false, HomeRowDefinition.CONTENT_HUBS in systemHomeRowTypes(HomeRowDefinition.SYSTEM_JELLYFIN))
        val prefs = AppPreferences(memoryPreferences())
        assertEquals(setOf("plex.hubs"), prefs.systemHomeHiddenRows)
        prefs.systemHomeHiddenRows = emptySet()
        assertEquals(emptySet<String>(), prefs.systemHomeHiddenRows)
        prefs.systemHomeHiddenRows = setOf("jellyfin.watched", "plex.top_genre")
        assertEquals(setOf("jellyfin.watched", "plex.top_genre"), prefs.systemHomeHiddenRows)
    }

    @Test fun recentPhotosRowIsKeptWhenHomeIsSaved() {
        val prefs = AppPreferences(memoryPreferences())
        val photos = HomeRowDefinition(
            system = HomeRowDefinition.SYSTEM_PHOTOS,
            contentKey = "recent",
            displayMode = HomeRowDefinition.DISPLAY_LANDSCAPE,
        )
        prefs.homeRows = listOf(photos)
        assertEquals(listOf(photos), prefs.homeRows)
    }

    @Test fun storedLandscapeHomeRowsMigrateOnce() {
        val store = memoryPreferences()
        val rows = listOf(
            HomeRowDefinition(
                id = "legacy-recent",
                system = HomeRowDefinition.SYSTEM_PLEX,
                contentKey = "recent",
                displayMode = HomeRowDefinition.DISPLAY_LANDSCAPE,
            ),
        )
        store.edit().putString(AppPreferences.KEY_HOME_ROWS, Json.encodeToString(rows)).apply()
        val prefs = AppPreferences(store)
        assertEquals(HomeRowDefinition.DISPLAY_POSTER, prefs.homeRows.single().displayMode)
        prefs.homeRows = listOf(prefs.homeRows.single().copy(displayMode = HomeRowDefinition.DISPLAY_LANDSCAPE))
        assertEquals(HomeRowDefinition.DISPLAY_LANDSCAPE, prefs.homeRows.single().displayMode)
    }
}
