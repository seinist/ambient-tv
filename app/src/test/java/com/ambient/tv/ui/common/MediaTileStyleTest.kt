package com.ambient.tv.ui.common

import com.ambient.tv.domain.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaTileStyleTest {
    private fun game() = MediaItem(
        ratingKey = "game-home:source%3Agame|snes", title = "Game", type = "game",
        index = null, thumbUrl = "file:///cover.png", artUrl = "file:///screenshot.png",
        year = null, summary = null, tagline = null, durationMs = null, viewOffsetMs = null,
        parentTitle = null, parentRatingKey = null, grandparentTitle = null, grandparentRatingKey = null,
        contentRating = null, rating = null, studio = null, gameCoverAspectRatio = 1.47f,
    )

    @Test fun gameAlwaysShowsItsBoxCoverInEveryHomeRowStyle() {
        MediaTileStyle.entries.forEach { style ->
            assertEquals("file:///cover.png", mediaTileImageUrl(game(), style))
            assertNull(mediaTileImageUrl(game().copy(thumbUrl = null), style))
        }
        assertNull(mediaTileImageUrl(game().copy(thumbUrl = "android.resource://com.ambient.tv/123"), MediaTileStyle.POSTER))
    }

    @Test fun gameGeometryUsesPersistedArtworkAndFallsBackForOldCachedRowsOnly() {
        assertEquals(1.47f, mediaTileGameCoverRatio(game())!!, 0.0001f)
        assertEquals(1.4f, mediaTileGameCoverRatio(game().copy(gameCoverAspectRatio = null))!!, 0.0001f)
        assertEquals(1.4f, mediaTileGameCoverRatio(game().copy(gameCoverAspectRatio = Float.NaN))!!, 0.0001f)
        assertNull(mediaTileGameCoverRatio(game().copy(ratingKey = "movie-1", type = "movie")))
    }

    @Test
    fun handheldAutoUsesPosterForEpisodes() {
        assertEquals(MediaTileStyle.POSTER, resolveMediaTileStyle(MediaTileStyle.AUTO, "episode", handheld = true))
        assertEquals(MediaTileStyle.POSTER, resolveMediaTileStyle(MediaTileStyle.AUTO, "movie", handheld = true))
    }

    @Test
    fun televisionAutoKeepsEpisodeTiles() {
        assertEquals(MediaTileStyle.EPISODE, resolveMediaTileStyle(MediaTileStyle.AUTO, "episode", handheld = false))
        assertEquals(MediaTileStyle.POSTER, resolveMediaTileStyle(MediaTileStyle.AUTO, "movie", handheld = false))
    }

    @Test
    fun posterStylePrefersSeriesCover() {
        val episode = MediaItem(
            ratingKey = "e1",
            title = "Pilot",
            type = "episode",
            index = 1,
            thumbUrl = "still",
            artUrl = "art",
            year = 2024,
            summary = null,
            tagline = null,
            durationMs = 1_000L,
            viewOffsetMs = 100L,
            parentTitle = "Season 1",
            parentRatingKey = "s1",
            grandparentTitle = "Series",
            grandparentRatingKey = "show",
            contentRating = null,
            rating = null,
            studio = null,
            grandparentThumbUrl = "series-poster",
        )
        assertEquals("series-poster", mediaTileImageUrl(episode, MediaTileStyle.POSTER))
        assertEquals("still", mediaTileImageUrl(episode, MediaTileStyle.CONTINUE))
        assertEquals("still", mediaTileImageUrl(episode, MediaTileStyle.EPISODE))
        assertEquals("still", mediaTileImageUrl(episode, MediaTileStyle.LANDSCAPE))
        val movie = episode.copy(type = "movie", thumbUrl = "poster")
        assertEquals("art", mediaTileImageUrl(movie, MediaTileStyle.CONTINUE))
        assertEquals("art", mediaTileImageUrl(movie, MediaTileStyle.LANDSCAPE))
        val season = episode.copy(type = "season", thumbUrl = "poster", artUrl = null, parentBackdropUrl = "show-art")
        assertEquals("show-art", mediaTileImageUrl(season, MediaTileStyle.LANDSCAPE))
    }
}
