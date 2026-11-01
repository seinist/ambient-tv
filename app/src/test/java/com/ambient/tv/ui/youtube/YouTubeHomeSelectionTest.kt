package com.ambient.tv.ui.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeHomeSelectionTest {
    @Test fun homeLoadsInitiallyAndOnExplicitSelectionOnly() {
        assertTrue(youtubeShouldRefreshOnEntry(YouTubeFeed.HOME, true, false, false))
        assertFalse(youtubeShouldRefreshOnEntry(YouTubeFeed.HOME, false, false, false))
        assertTrue(youtubeShouldRefreshOnEntry(YouTubeFeed.HOME, false, false, true))
    }

    @Test fun closingHomeSearchDoesNotRequestAnotherFeedRefresh() {
        assertTrue(youtubeRestoreHomeAfterSearch("cats", ""))
        assertTrue(youtubeRestoreHomeAfterSearch("cats", "c"))
        assertFalse(youtubeRestoreHomeAfterSearch("c", ""))
        assertFalse(youtubeRestoreHomeAfterSearch("", "c"))
    }

    @Test fun subscriptionsAndChannelPagesStillRefreshOnEntry() {
        assertTrue(youtubeShouldRefreshOnEntry(YouTubeFeed.SUBSCRIPTIONS, false, false, false))
        assertTrue(youtubeShouldRefreshOnEntry(YouTubeFeed.HOME, false, true, false))
    }
}
