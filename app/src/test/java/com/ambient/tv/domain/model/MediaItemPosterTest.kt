package com.ambient.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaItemPosterTest {
    @Test
    fun episodePosterUsesSeriesCover() {
        val episode = item(
            type = "episode",
            title = "Pilot",
            thumbUrl = "episode-still",
            parentThumbUrl = "season-poster",
            grandparentThumbUrl = "series-poster",
            grandparentTitle = "Series",
        )
        assertEquals("series-poster", episode.posterArtUrl)
        assertEquals("Series", episode.posterTitle)
    }

    @Test
    fun episodePosterFallsBackToSeasonThenStill() {
        val seasonOnly = item(type = "episode", thumbUrl = "still", parentThumbUrl = "season-poster")
        val stillOnly = item(type = "episode", thumbUrl = "still")
        assertEquals("season-poster", seasonOnly.posterArtUrl)
        assertEquals("still", stillOnly.posterArtUrl)
    }

    @Test
    fun moviePosterKeepsItsOwnThumb() {
        val movie = item(type = "movie", title = "Film", thumbUrl = "movie-poster", grandparentThumbUrl = "ignored")
        assertEquals("movie-poster", movie.posterArtUrl)
        assertEquals("Film", movie.posterTitle)
    }

    private fun item(
        type: String,
        title: String = "Title",
        thumbUrl: String? = null,
        parentThumbUrl: String? = null,
        grandparentThumbUrl: String? = null,
        grandparentTitle: String? = null,
    ) = MediaItem(
        ratingKey = "1",
        title = title,
        type = type,
        index = 1,
        thumbUrl = thumbUrl,
        artUrl = "art",
        year = 2024,
        summary = null,
        tagline = null,
        durationMs = 1_000L,
        viewOffsetMs = null,
        parentTitle = "Season 1",
        parentRatingKey = "s1",
        grandparentTitle = grandparentTitle,
        grandparentRatingKey = "show",
        contentRating = null,
        rating = null,
        studio = null,
        parentThumbUrl = parentThumbUrl,
        grandparentThumbUrl = grandparentThumbUrl,
    )
}
