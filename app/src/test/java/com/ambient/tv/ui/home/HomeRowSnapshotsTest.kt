package com.ambient.tv.ui.home

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaVersion
import com.ambient.tv.data.browse.displayOnly
import com.ambient.tv.ui.common.MediaTileStyle
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowSnapshotsTest {
    private fun item() = MediaItem(
        ratingKey = "radio:42", title = "Last listened", type = "track",
        index = null, thumbUrl = "https://art.example/cover?tag=1&ApiKey=private",
        artUrl = null, year = null, summary = null, tagline = null,
        durationMs = null, viewOffsetMs = null, parentTitle = null,
        parentRatingKey = null, grandparentTitle = null, grandparentRatingKey = null,
        contentRating = null, rating = null, studio = "Radio Browser",
        mediaVersions = listOf(MediaVersion(streamUrl = "https://stream.example/live",
            resolution = null, videoCodec = null, audioCodec = null, container = null)),
    )

    @Test fun restoredRowsKeepIdsAndPosterButNotPlaybackSecrets() {
        val safe = item().displayOnly()
        val snapshot = HomeRowDisplaySnapshot(
            id = "radio.favorites", title = "Favorites",
            style = MediaTileStyle.LANDSCAPE.name, showDescription = false,
            items = listOf(safe),
        )
        val restored = Json.decodeFromString<HomeRowDisplaySnapshot>(Json.encodeToString(snapshot)).asRow()
        requireNotNull(restored)
        assertEquals("radio.favorites", restored.id)
        assertEquals(MediaTileStyle.LANDSCAPE, restored.style)
        assertEquals("radio:42", restored.items.single().ratingKey)
        assertTrue(restored.items.single().mediaVersions.isEmpty())
        assertFalse(restored.items.single().thumbUrl.orEmpty().contains("private"))
    }

    @Test fun successfulEmptyFeedTombstoneDoesNotRecreateOldRow() {
        assertNull(HomeRowDisplaySnapshot(
            id = "trailers.Popular", title = "", style = "LANDSCAPE",
            showDescription = false, items = emptyList(),
        ).asRow())
    }

    @Test fun unknownStyleFallsBackWithoutDroppingCachedPosters() {
        val restored = HomeRowDisplaySnapshot(
            id = "radio.popular", title = "Popular", style = "future-mode",
            showDescription = false, items = listOf(item()),
        ).asRow()
        assertEquals(MediaTileStyle.LANDSCAPE, restored?.style)
    }
}
