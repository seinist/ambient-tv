package com.ambient.tv.ui.common

import com.ambient.tv.domain.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTileStyleTest {
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
    }
}
