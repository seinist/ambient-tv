package com.ambient.tv.ui.home

import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSmartTubeRowsTest {
    @Test fun keepsTheRealSourceGroupAndDoesNotPlayChannelTiles() {
        val channels = YouTubeRow("Channels", listOf(YouTubeItem("channel:UCtest", "Subscribed channel")), id = "channels-group")
        val videos = YouTubeRow("Recommended", listOf(
            YouTubeItem("v1", "First", author = "Creator"),
            YouTubeItem("v2", "Second"),
            YouTubeItem("v1", "First duplicate"),
            YouTubeItem("v3", "Third"),
        ), id = "real-feed-group")
        val selected = homeYouTubeSourceRow(listOf(channels, videos), limit = 2)
        assertEquals("real-feed-group", selected?.id)
        assertEquals(listOf("v1", "v2"), selected?.items?.map { it.videoId })
    }

    @Test fun noVideoItemsProducesNoFakeHomeRow() {
        assertNull(homeYouTubeSourceRow(emptyList(), 24))
        assertNull(homeYouTubeSourceRow(listOf(
            YouTubeRow("Channels", listOf(YouTubeItem("channel:UCtest", "Channel"))),
        ), 24))
        assertNull(homeYouTubeSourceRow(listOf(
            YouTubeRow("Videos", listOf(YouTubeItem("abc", "Item"))),
        ), 0))
    }

    @Test fun playbackKeysNeverLeakIntoPlexAndKeepYoutubeArtwork() {
        val item = YouTubeItem(
            videoId = "aBcDeFg1234", title = "Example", author = "Creator",
            imageUrl = "https://i.ytimg.com/vi/aBcDeFg1234/hqdefault.jpg",
            durationMs = 100_000L, percentWatched = 25,
        )
        val media = item.asHomeMediaItem()
        assertEquals("clip", media.type)
        assertEquals("home-youtube:aBcDeFg1234", media.ratingKey)
        assertEquals("aBcDeFg1234", homeYouTubeVideoId(media.ratingKey))
        assertEquals(item.imageUrl, media.landscapeArtUrl)
        assertEquals(25_000L, media.viewOffsetMs)
        assertEquals("SmartTube", media.studio)
        assertFalse(media.isWatched)
        assertNull(homeYouTubeVideoId("plex:episode:42"))
    }

    @Test fun liveItemsDoNotReceiveFakeProgress() {
        val media = YouTubeItem(
            videoId = "live1", title = "Live", durationMs = 900_000L, percentWatched = 90, isLive = true,
        ).asHomeMediaItem()
        assertNull(media.viewOffsetMs)
        assertTrue(media.ratingKey.startsWith(HOME_YOUTUBE_ITEM_PREFIX))
    }
}
