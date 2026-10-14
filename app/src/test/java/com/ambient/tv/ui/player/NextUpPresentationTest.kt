package com.ambient.tv.ui.player

import com.ambient.tv.domain.model.MediaItem
import org.junit.Assert.*
import org.junit.Test

class NextUpPresentationTest {
    private fun episode() = MediaItem(ratingKey = "episode", title = "Episode title", type = "episode",
        index = 4, thumbUrl = "landscape", artUrl = "series-background", year = 2024, summary = null,
        tagline = null, durationMs = 2_880_000, viewOffsetMs = null, parentTitle = "Season 2", parentIndex = 2,
        parentRatingKey = "season", grandparentTitle = "Series", grandparentRatingKey = "series",
        contentRating = "DE/12", rating = null, studio = null, parentThumbUrl = "season-poster", grandparentThumbUrl = "series-poster")
    @Test fun episodeMetadataAndAuthenticationAreCarriedToNextUp() {
        val next = episode().toNextUpCandidate("jellyfin", mapOf("Authorization" to "fixture"))
        assertEquals("Episode title", next.title)
        assertEquals("Series", next.subtitle)
        assertEquals(2, next.season); assertEquals(4, next.episode)
        assertEquals(2_880_000L, next.durationMs)
        assertEquals("DE/12", next.contentRating)
        assertEquals("fixture", next.artworkHeaders["Authorization"])
    }
    @Test fun episodeFallsBackFromLandscapeToTypedSeasonAndSeriesPosters() {
        assertEquals(listOf(NextUpArtwork("landscape", false), NextUpArtwork("season-poster", true),
            NextUpArtwork("series-poster", true)), episode().toNextUpCandidate("plex", emptyMap()).artworkChoices())
        assertTrue(episode().copy(thumbUrl = null).toNextUpCandidate("plex", emptyMap()).artworkChoices().first().poster)
    }
    @Test fun moviePrefersPortraitEvenWhenABackdropExists() {
        val next = episode().copy(type = "movie", thumbUrl = "movie-poster").toNextUpCandidate("emby", emptyMap())
        assertEquals(NextUpArtwork("movie-poster", true), next.artworkChoices().first())
        assertEquals(2024, next.year)
        assertNull(next.episode); assertNull(next.season)
        assertTrue(next.subtitle.isEmpty())
    }
    @Test fun emptyArtworkDoesNotReserveAnEmptyImage() {
        assertTrue(NextUpCandidate("x", "x", source = "youtube", artworkUrl = " ").artworkChoices().isEmpty())
    }
    @Test fun disabledAutoplayNeverClaimsAnAutomaticStartIncludingAtEof() {
        assertEquals(NextUpCountdown.DISABLED, nextUpCountdown(false, false, 24))
        assertEquals(NextUpCountdown.DISABLED, nextUpCountdown(false, false, 0))
    }
    @Test fun confirmationTakesPrecedenceOverCountdownAndReady() {
        assertEquals(NextUpCountdown.CONFIRMATION, nextUpCountdown(true, true, 24))
        assertEquals(NextUpCountdown.CONFIRMATION, nextUpCountdown(true, true, 0))
    }
    @Test fun onlyUnblockedAutoplayUsesTheCountdown() {
        assertEquals(NextUpCountdown.COUNTDOWN, nextUpCountdown(true, false, 24))
        assertEquals(NextUpCountdown.READY, nextUpCountdown(true, false, 0))
    }
}
