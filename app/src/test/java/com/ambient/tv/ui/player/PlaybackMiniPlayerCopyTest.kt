package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackMiniPlayerCopyTest {
    @Test fun fileUsesTitleOnly() {
        assertEquals(MiniPlayerCopy("Movie.mkv", ""), miniPlayerCopy("video", "Movie.mkv", null))
        assertEquals(MiniPlayerCopy("Dune", ""), miniPlayerCopy("movie", "Dune", null))
    }

    @Test fun episodePutsSeriesAboveEpisodeTitle() {
        assertEquals(
            MiniPlayerCopy("Andor", "One Way Out"),
            miniPlayerCopy("episode", "One Way Out", "Andor"),
        )
    }

    @Test fun episodeWithoutSeriesKeepsTheEpisodeTitle() {
        assertEquals(MiniPlayerCopy("Pilot", ""), miniPlayerCopy("episode", "Pilot", "  "))
    }

    @Test fun matchingSeriesAndEpisodeTitlesAreNotDuplicated() {
        assertEquals(MiniPlayerCopy("Special", ""), miniPlayerCopy("Episode", "Special", "Special"))
    }

    @Test fun episodeMiniPlayerPrefersSeriesCover() {
        val art = miniPlayerArtwork(
            mediaType = "episode",
            posterArtUrl = "series-poster",
            thumbUrl = "episode-still",
            panelArtworkUrl = "backdrop",
            panelFallbacks = listOf("wide"),
        )
        assertEquals("series-poster", art.url)
        assertEquals(listOf("episode-still", "backdrop", "wide"), art.fallbacks)
        assertEquals(2f / 3f, art.aspectRatio, 0.001f)
    }

    @Test fun episodeWithoutCoverKeepsTheStill() {
        val art = miniPlayerArtwork(
            mediaType = "episode",
            posterArtUrl = "episode-still",
            thumbUrl = "episode-still",
            panelArtworkUrl = null,
        )
        assertEquals("episode-still", art.url)
        assertEquals(16f / 9f, art.aspectRatio, 0.001f)
    }

    @Test fun movieMiniPlayerKeepsPosterThumb() {
        val art = miniPlayerArtwork(
            mediaType = "movie",
            posterArtUrl = "movie-poster",
            thumbUrl = "movie-poster",
            panelArtworkUrl = "backdrop",
        )
        assertEquals("movie-poster", art.url)
        assertEquals(2f / 3f, art.aspectRatio, 0.001f)
    }
}
