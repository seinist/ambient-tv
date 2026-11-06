package com.ambient.tv.ui.home

import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeRow
import com.ambient.tv.youtube.YouTubeLocalProgress
import com.ambient.tv.ui.common.ContentRowData
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

    @Test fun existingHomeCardReflectsLocalCompletionWithoutReloadingTheFeed() {
        val id = "home-completion-progress"
        val video = YouTubeItem(id, "Video", durationMs = 100_000L, percentWatched = 40)
        val row = ContentRowData("SmartTube", listOf(video.asHomeMediaItem()), id = "youtube.home")
        assertEquals(40_000L, row.items.single().viewOffsetMs)
        YouTubeLocalProgress.record(id, 80_000, 100_000, completed = true)
        val finished = row.withHomeYouTubeProgress().items.single()
        assertTrue(finished.isWatched)
        assertEquals(1, finished.viewCount)
        assertNull(finished.viewOffsetMs)
        assertTrue(video.asHomeMediaItem().isWatched) // The same cached card cannot reset it.
        assertEquals(0L, YouTubeLocalProgress.resumePositionMs(id, 100_000, 40))
    }

    @Test fun existingHomeCardUsesTheSameFreshServerValueAsResume() {
        val id = "home-remote-progress"
        val row = ContentRowData("SmartTube", listOf(
            YouTubeItem(id, "Video", durationMs = 100_000L, percentWatched = 80).asHomeMediaItem(),
        ), id = "youtube.history")
        YouTubeLocalProgress.observeRemote(id, 40)
        assertEquals(40_000L, row.withHomeYouTubeProgress().items.single().viewOffsetMs)
        assertEquals(40_000L, YouTubeLocalProgress.resumePositionMs(id, 100_000, 80))
        YouTubeLocalProgress.observeRemote(id, 0)
        val restarted = row.withHomeYouTubeProgress().items.single()
        assertNull(restarted.viewOffsetMs)
        assertFalse(restarted.isWatched)
    }

    @Test fun homeLiveCardsIgnoreHistoricalProgressWhenRowsRefresh() {
        val id = "home-live-no-progress"
        YouTubeLocalProgress.record(id, 800_000, 900_000)
        val row = ContentRowData("SmartTube", listOf(
            YouTubeItem(id, "Live", durationMs = 900_000, percentWatched = 90, isLive = true).asHomeMediaItem(),
        ), id = "youtube.home")
        val live = row.withHomeYouTubeProgress().items.single()
        assertNull(live.viewOffsetMs)
        assertFalse(live.isWatched)
    }

    @Test fun homeUsesYoutubeCompletionThresholdInsteadOfLibraryNinetyPercent() {
        val id = "home-youtube-threshold"
        val video = YouTubeItem(id, "Video", durationMs = 100_000, percentWatched = 94)
        val row = ContentRowData("SmartTube", listOf(video.asHomeMediaItem()), id = "youtube.home")
        assertFalse(row.items.single().isWatched)
        assertFalse(row.withHomeYouTubeProgress().items.single().isWatched)
        assertEquals(94_000L, YouTubeLocalProgress.resumePositionMs(id, 100_000))
        YouTubeLocalProgress.observeRemote(id, 95)
        assertTrue(row.withHomeYouTubeProgress().items.single().isWatched)
        assertNull(row.withHomeYouTubeProgress().items.single().viewOffsetMs)
    }

    @Test fun completedHomeVideoWithMissingCardDurationStillShowsSeen() {
        val id = "home-completion-no-duration"
        val row = ContentRowData("SmartTube", listOf(
            YouTubeItem(id, "Video", percentWatched = 20).asHomeMediaItem(),
        ), id = "youtube.home")
        YouTubeLocalProgress.record(id, 80_000, 0, completed = true)
        val completed = row.withHomeYouTubeProgress().items.single()
        assertTrue(completed.isWatched)
        assertNull(completed.viewOffsetMs)
    }
}
