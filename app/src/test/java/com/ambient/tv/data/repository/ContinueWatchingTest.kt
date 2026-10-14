package com.ambient.tv.data.repository

import com.ambient.tv.domain.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingTest {
    @Test fun nextEpisodeTakesThePlayDateOfItsSeries() {
        val movie = item("movie", lastViewedAt = 200)
        val oldMovie = item("old-movie", lastViewedAt = 50)
        val next = item("s1e4", series = "show")
        val played = listOf(item("s1e2", series = "show", lastViewedAt = 90), item("s1e3", series = "show", lastViewedAt = 100))
        assertEquals(listOf("movie", "s1e4", "old-movie"), continueWatching(listOf(movie, oldMovie), listOf(next), played).map { it.ratingKey })
    }

    @Test fun seriesWithAResumableEpisodeIsListedOnce() {
        val resumable = item("s1e3", series = "show", lastViewedAt = 100)
        val next = item("s1e4", series = "show")
        assertEquals(listOf("s1e3"), continueWatching(listOf(resumable), listOf(next, resumable), emptyList()).map { it.ratingKey })
    }

    @Test fun nextEpisodeOfASeriesWithoutKnownPlayDateComesLast() {
        val movie = item("movie", lastViewedAt = 10)
        val next = item("s2e1", series = "other")
        assertEquals(listOf("movie", "s2e1"), continueWatching(listOf(movie), listOf(next), emptyList()).map { it.ratingKey })
    }

    private fun item(ratingKey: String, series: String? = null, lastViewedAt: Long? = null) = MediaItem(
        ratingKey = ratingKey,
        title = ratingKey,
        type = if (series == null) "movie" else "episode",
        index = null,
        thumbUrl = null,
        artUrl = null,
        year = null,
        summary = null,
        tagline = null,
        durationMs = null,
        viewOffsetMs = null,
        parentTitle = null,
        parentRatingKey = null,
        grandparentTitle = series,
        grandparentRatingKey = series,
        contentRating = null,
        rating = null,
        studio = null,
        lastViewedAt = lastViewedAt,
    )
}
