package com.ambient.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaItemWatchStateTest {
    @Test
    fun partlyWatchedShowIsNotWatchedDespiteItsPlexViewCount() {
        val show = item(type = "show", viewCount = 3, leafCount = 10, viewedLeafCount = 3)
        assertFalse(show.isWatched)
        assertEquals(7, show.unwatchedLeafCount)
    }

    @Test
    fun showIsWatchedOnceEveryEpisodeIs() {
        val show = item(type = "show", viewCount = 0, leafCount = 10, viewedLeafCount = 10)
        assertTrue(show.isWatched)
        assertEquals(0, show.unwatchedLeafCount)
    }

    @Test
    fun showWithoutWatchedEpisodeCountFallsBackToViewCount() {
        assertTrue(item(type = "show", viewCount = 1, leafCount = 10, viewedLeafCount = null).isWatched)
        assertFalse(item(type = "show", viewCount = 0, leafCount = 10, viewedLeafCount = null).isWatched)
    }

    @Test
    fun movieUsesViewCountAndProgress() {
        assertTrue(item(type = "movie", viewCount = 1).isWatched)
        assertTrue(item(type = "movie", viewCount = 0, viewOffsetMs = 950L).isWatched)
        assertFalse(item(type = "movie", viewCount = 0, viewOffsetMs = 400L).isWatched)
    }

    private fun item(
        type: String,
        viewCount: Int? = null,
        leafCount: Int? = null,
        viewedLeafCount: Int? = null,
        viewOffsetMs: Long? = null,
    ) = MediaItem(
        ratingKey = "1",
        title = "Title",
        type = type,
        index = null,
        thumbUrl = null,
        artUrl = null,
        year = 2024,
        summary = null,
        tagline = null,
        durationMs = 1_000L,
        viewOffsetMs = viewOffsetMs,
        parentTitle = null,
        parentRatingKey = null,
        grandparentTitle = null,
        grandparentRatingKey = null,
        contentRating = null,
        rating = null,
        studio = null,
        viewCount = viewCount,
        leafCount = leafCount,
        viewedLeafCount = viewedLeafCount,
    )
}
