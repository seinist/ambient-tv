package com.ambient.tv.search

import com.ambient.tv.domain.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalSearchTest {
    @Test fun queriesAreTrimmedSingleLineAndBounded() {
        assertEquals("star wars", normalizeExternalQuery("  star \n  wars "))
        assertNull(normalizeExternalQuery(" x "))
        assertNull(normalizeExternalQuery(null))
        assertEquals(200, normalizeExternalQuery("a".repeat(500))!!.length)
    }

    private fun item(key: String, type: String, guid: String) =
        MediaItem(ratingKey = key, title = key, type = type,
            index = null, thumbUrl = null, artUrl = null, year = 2020, summary = null,
            tagline = null, durationMs = null, viewOffsetMs = null, parentTitle = null,
            parentRatingKey = null, grandparentTitle = null, grandparentRatingKey = null,
            contentRating = null, rating = null, studio = null, guid = guid)

    @Test fun suggestionsPutMoviesAndShowsFirstAndDropDuplicatesAcrossServers() {
        val merged = mergeSuggestions(listOf(
            item("ep", "episode", "tmdb://3"),
            item("plex-movie", "movie", "tmdb://1"),
            item("jf-movie", "movie", "tmdb://1"),
            item("show", "show", "tmdb://2"),
        ), limit = 10)
        assertEquals(listOf("plex-movie", "show", "ep"), merged.map { it.ratingKey })
        assertEquals(1, mergeSuggestions(merged, limit = 1).size)
    }
}
