package com.ambient.tv.ui.section

import com.ambient.tv.domain.model.MediaItem
import org.junit.Assert.*
import org.junit.Test

class SectionContinueWatchingTest {
    @Test fun includesResumeAndUnstartedNextEpisodesForEveryProvider() {
        for (prefix in listOf("", "jellyfin:", "emby:")) {
            val show = item("${prefix}show").copy(type = "show")
            val resumed = item("${prefix}episode1").copy(type = "episode", grandparentRatingKey = show.ratingKey,
                durationMs = 100_000, viewOffsetMs = 40_000)
            val next = resumed.copy(ratingKey = "${prefix}episode2", viewOffsetMs = 0)
            val foreign = next.copy(ratingKey = "${prefix}foreign", grandparentRatingKey = "${prefix}other-show")
            assertEquals(listOf(next, resumed), sectionContinueWatching(listOf(foreign, next, resumed), listOf(show)))
        }
    }

    @Test fun preservesServerOrderAndDoesNotInferMembershipFromTitles() {
        val movie = item("movie")
        val other = movie.copy(ratingKey = "other-library")
        val second = item("second")
        assertEquals(listOf(second, movie), sectionContinueWatching(listOf(other, second, movie, movie), listOf(movie, second)))
    }

    @Test fun matchesLinkedMovieAndSeriesIds() {
        val movie = item("movie").copy(linkedRatingKeys = listOf("movie-version"))
        val show = item("show").copy(type = "show", linkedRatingKeys = listOf("show-version"))
        val resume = item("movie-version")
        val next = item("episode").copy(type = "episode", grandparentRatingKey = "show-version")
        assertEquals(listOf(resume, next), sectionContinueWatching(listOf(resume, next), listOf(movie, show)))
    }

    @Test fun emptyFeedDoesNotReintroduceLocalProgressAndEmptyLibraryCannotLeakTitles() {
        val started = item("movie").copy(durationMs = 100_000, viewOffsetMs = 40_000)
        assertTrue(sectionContinueWatching(emptyList(), listOf(started)).isEmpty())
        assertTrue(sectionContinueWatching(listOf(started), emptyList()).isEmpty())
    }

    private fun item(key: String) = MediaItem(ratingKey = key, title = key, type = "movie",
        index = null, thumbUrl = null, artUrl = "art-$key", year = null, summary = null,
        tagline = null, durationMs = null, viewOffsetMs = null, parentTitle = null,
        parentRatingKey = null, grandparentTitle = null, grandparentRatingKey = null,
        contentRating = null, rating = null, studio = null)
}
