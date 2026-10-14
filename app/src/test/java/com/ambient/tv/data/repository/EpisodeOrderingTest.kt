package com.ambient.tv.data.repository

import com.ambient.tv.domain.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeOrderingTest {
    @Test fun specialsComeAfterRegularSeasons() {
        val episodes = listOf(ep("s0e1", 0, 1), ep("s2e1", 2, 1), ep("s1e2", 1, 2), ep("s1e1", 1, 1))
        assertEquals("s1e1", EpisodeOrdering.nextUnwatched(episodes)?.ratingKey)
    }

    @Test fun specialsAreTheFallbackOnceRegularEpisodesAreWatched() {
        val episodes = listOf(ep("s0e1", 0, 1), ep("s1e1", 1, 1, viewCount = 1))
        assertEquals("s0e1", EpisodeOrdering.nextUnwatched(episodes)?.ratingKey)
    }

    @Test fun partlyWatchedEpisodeWins() {
        val episodes = listOf(ep("s1e1", 1, 1), ep("s1e2", 1, 2, offset = 300))
        assertEquals("s1e2", EpisodeOrdering.nextUnwatched(episodes)?.ratingKey)
    }

    @Test fun serverFilteredListIsTakenAsUnwatched() {
        val episodes = listOf(ep("s0e1", 0, 1, viewCount = 1), ep("s3e4", 3, 4, viewCount = 1))
        assertEquals("s3e4", EpisodeOrdering.nextUnwatched(episodes, assumeUnwatched = true)?.ratingKey)
        assertNull(EpisodeOrdering.nextUnwatched(episodes))
    }

    private fun ep(key: String, season: Int, index: Int, viewCount: Int? = null, offset: Long? = null) = MediaItem(
        ratingKey = key, title = key, type = "episode", index = index, parentIndex = season,
        thumbUrl = null, artUrl = null, year = null, summary = null, tagline = null,
        durationMs = 1_000, viewOffsetMs = offset, parentTitle = null, parentRatingKey = null,
        grandparentTitle = "show", grandparentRatingKey = "show", contentRating = null, rating = null,
        studio = null, viewCount = viewCount,
    )
}
