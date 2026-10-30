package com.ambient.tv.ui.youtube

import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.youtubeChannelKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeBrowsePlaybackContextTest {
    private fun video(id: String) = YouTubeItem(videoId = id, title = id)

    @Test fun nextStaysInTheOriginalRowAndNeverUsesSuggestions() {
        val subscriptions = listOf(video("abo-1"), video("abo-2"), video("abo-3"))
        assertEquals("abo-2", nextPlayableBrowseVideo(subscriptions, "abo-1")?.videoId)
        assertEquals("abo-3", nextPlayableBrowseVideo(subscriptions, "abo-2")?.videoId)
        assertNull(nextPlayableBrowseVideo(subscriptions, "abo-3"))
        assertNull(nextPlayableBrowseVideo(subscriptions, "home-recommendation"))
    }

    @Test fun insertedItemsDoNotChangeTheIdentityOfTheCurrentVideo() {
        val row = listOf(video("new"), video("abo-1"), video("abo-2"))
        assertEquals("abo-2", nextPlayableBrowseVideo(row, "abo-1")?.videoId)
    }

    @Test fun unplayableAndChannelTilesAreNeverAutomaticSuccessors() {
        val items = listOf(
            video("a"),
            YouTubeItem("live", "Live", isLive = true),
            YouTubeItem("upcoming", "Upcoming", isUpcoming = true),
            video(youtubeChannelKey("UC123")),
            video("b"),
        )
        assertEquals("b", nextPlayableBrowseVideo(items, "a")?.videoId)
        assertNull(nextPlayableBrowseVideo(items, "b"))
    }
}
