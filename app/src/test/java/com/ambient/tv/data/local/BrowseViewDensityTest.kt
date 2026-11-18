package com.ambient.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseViewDensityTest {
    @Test fun densityChoicesAreIndependentAndDefaultToLegacyValues() {
        val preferences = AppPreferences(memoryPreferences())
        preferences.libraryGridColumns = 10
        preferences.libraryListRows = 8
        assertEquals(10, preferences.layoutGridColumns("plex:library", "POSTER"))
        assertEquals(10, preferences.layoutGridColumns("plex:library", "LANDSCAPE"))
        preferences.setLayoutGridColumns("plex:library", "POSTER", 6)
        preferences.setLayoutGridColumns("plex:library", "LANDSCAPE", 12)
        preferences.setLayoutGridColumns("youtube:home", "LANDSCAPE", 4)
        preferences.setLayoutListRows("plex:library", "LIST", 4)
        preferences.setLayoutListRows("plex:library", "INFO_LIST", 9)
        assertEquals(6, preferences.layoutGridColumns("plex:library", "POSTER"))
        assertEquals(12, preferences.layoutGridColumns("plex:library", "LANDSCAPE"))
        assertEquals(4, preferences.layoutGridColumns("youtube:home", "LANDSCAPE"))
        assertEquals(10, preferences.layoutGridColumns("plex:library", "WALL"))
        assertEquals(4, preferences.layoutListRows("plex:library", "LIST"))
        assertEquals(9, preferences.layoutListRows("plex:library", "INFO_LIST"))
        assertEquals(8, preferences.layoutListRows("youtube:home", "LIST"))
    }

    @Test fun scopedDensitiesArePortableAndValidated() {
        val source = AppPreferences(memoryPreferences())
        source.setLayoutGridColumns("plex:library", "POSTER", 5)
        source.setLayoutListRows("youtube:home", "INFO_LIST", 9)
        val values = source.portableLibraryPreferences()
        assertTrue(values.keys.any { it.startsWith("layout_grid_columns_") })
        assertTrue(values.keys.any { it.startsWith("layout_list_rows_") })
        val target = AppPreferences(memoryPreferences())
        target.setLayoutGridColumns("old", "POSTER", 7)
        target.restorePortableLibraryPreferences(values, includeDensityOptions = true)
        assertEquals(5, target.layoutGridColumns("plex:library", "POSTER"))
        assertEquals(9, target.layoutListRows("youtube:home", "INFO_LIST"))
        assertEquals(8, target.layoutGridColumns("old", "POSTER"))
        assertFalse(target.portableLibraryPreferences().keys.any { it.contains("old|POSTER") })
        target.restorePortableLibraryPreferences(mapOf(
            "layout_grid_columns_plex|POSTER" to "999",
            "layout_list_rows_plex|LIST" to "-3",
            "layout_grid_columns_plex|WALL" to "not-an-int",
        ), includeDensityOptions = true)
        assertEquals(12, target.layoutGridColumns("plex", "POSTER"))
        assertEquals(4, target.layoutListRows("plex", "LIST"))
        assertEquals(8, target.layoutGridColumns("plex", "WALL"))
    }

    @Test fun legacyBackupDoesNotDeleteCurrentPerViewDensities() {
        val preferences = AppPreferences(memoryPreferences())
        preferences.setLayoutGridColumns("plex:library", "POSTER", 7)
        preferences.restorePortableLibraryPreferences(emptyMap(), includeDensityOptions = false)
        assertEquals(7, preferences.layoutGridColumns("plex:library", "POSTER"))
    }
}
