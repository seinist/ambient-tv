package com.ambient.tv.ui.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeFeedEntryTest {
    @Test fun chipDestinationIsAlreadySelectedAtFirstFrame() {
        assertEquals(YouTubeFeed.SUBSCRIPTIONS,
            initialYouTubeBrowseFeed("subscriptions", "home", listOf("home", "subscriptions", "history"), false))
        assertEquals(YouTubeFeed.HISTORY,
            initialYouTubeBrowseFeed("history", "home", listOf("home", "history"), false))
    }

    @Test fun rootStartsAtConfiguredFeedButChannelPagesDoNotInheritIt() {
        assertEquals(YouTubeFeed.CHANNELS,
            initialYouTubeBrowseFeed(null, "channels", listOf("home", "channels"), false))
        assertEquals(YouTubeFeed.HOME,
            initialYouTubeBrowseFeed("channels", "channels", listOf("home", "channels"), true))
    }

    @Test fun disabledAndInvalidDestinationsSelectAnEnabledFeed() {
        assertEquals(YouTubeFeed.HOME,
            initialYouTubeBrowseFeed("playlists", "home", listOf("home", "history"), false))
        assertEquals(YouTubeFeed.SHORTS,
            initialYouTubeBrowseFeed("unknown", null, listOf("shorts", "news"), false))
    }
}
