package com.ambient.tv.data.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioCatalogCacheTest {
    @Test fun ttlHonorsExactBoundaryAndClockRollback() {
        assertTrue(radioCatalogFresh(1_000L, 100L, 1_099L))
        assertFalse(radioCatalogFresh(1_000L, 100L, 1_100L))
        assertFalse(radioCatalogFresh(1_000L, 100L, 900L))
    }

    @Test fun pageKeysIncludePagingSortingAndNormalizedFilters() {
        fun key(offset: Int = 0, limit: Int = 48, order: String = "name", reverse: Boolean = false,
                country: String? = "DE", tag: String? = null) =
            radioSearchCacheKey("  Jazz  ", country, null, tag, offset, limit, order, reverse)
        assertEquals(key(), radioSearchCacheKey("jazz", "de", null, null, 0, 48, "name", false))
        assertNotEquals(key(), key(offset = 48))
        assertNotEquals(key(), key(limit = 24))
        assertNotEquals(key(), key(reverse = true))
        assertNotEquals(key(), key(order = "clickcount"))
        assertNotEquals(key(), key(tag = "jazz"))
        assertNotEquals(key(), key(country = "FR"))
    }

    @Test fun persistedCatalogRedactsPlaybackUrlsButRetainsDisplayData() {
        val station = RadioBrowserStationDto(
            stationUuid = "a-uuid", name = "Test Radio",
            resolvedUrl = "https://stream.example/live?token=secret",
            homepage = "https://example.test/private",
            favicon = "https://example.test/logo.png?token=secret&width=100",
        ).toRadioStation()!!
        val display = station.radioCatalogDisplayOnly()
        assertEquals("Test Radio", display.name)
        assertEquals("a-uuid", display.id)
        assertEquals("", display.streamUrl)
        assertNull(display.homepage)
        assertEquals("https://example.test/logo.png?width=100", display.artworkUrl)
        assertEquals("https://stream.example/live?token=secret", station.streamUrl)
    }
}
