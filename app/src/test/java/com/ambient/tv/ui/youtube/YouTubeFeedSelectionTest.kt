package com.ambient.tv.ui.youtube

import com.ambient.tv.ui.navigation.Destinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeFeedSelectionTest {
    @Test fun rootRouteMeansTheStartFeed() {
        assertEquals("home", youtubeFeedKeyOfRoute(Destinations.YOUTUBE, "home"))
        assertEquals("subscriptions", youtubeFeedKeyOfRoute(Destinations.YOUTUBE, "subscriptions"))
        assertEquals("history", youtubeFeedKeyOfRoute("youtube_feed/history", "home"))
        assertNull(youtubeFeedKeyOfRoute(Destinations.HOME, "home"))
        assertNull(youtubeFeedKeyOfRoute("youtube_channel/UC1", "home"))
    }

    @Test fun switchesInPlaceOnlyOnTheLiveFeedScreenAndForAnotherFeed() {
        assertTrue(youtubeFeedSwitchesInPlace(Destinations.YOUTUBE, "home", "subscriptions"))
        assertTrue(youtubeFeedSwitchesInPlace(Destinations.YOUTUBE_FEED, "subscriptions", "home"))
        // The same feed is a reselection (scroll to top / Home refresh), not a switch.
        assertFalse(youtubeFeedSwitchesInPlace(Destinations.YOUTUBE, "home", "home"))
        // No live feed screen (channel page, Home, player): navigate as before.
        assertFalse(youtubeFeedSwitchesInPlace(Destinations.YOUTUBE_CHANNEL, "home", "subscriptions"))
        assertFalse(youtubeFeedSwitchesInPlace(Destinations.HOME, null, "subscriptions"))
        assertFalse(youtubeFeedSwitchesInPlace(Destinations.YOUTUBE, "home", null))
    }

    @Test fun onlyTheOwnerClearsItsDisplayedFeed() {
        val first = Any()
        val second = Any()
        YouTubeFeedSelection.show(first, "home")
        YouTubeFeedSelection.show(second, "subscriptions")
        YouTubeFeedSelection.hide(first)
        assertEquals("subscriptions", YouTubeFeedSelection.displayed.value?.second)
        YouTubeFeedSelection.hide(second)
        assertNull(YouTubeFeedSelection.displayed.value)
    }

    @Test fun requestsCarryIncreasingSerials() {
        val before = YouTubeFeedSelection.requests.value?.serial ?: 0L
        YouTubeFeedSelection.request("history")
        YouTubeFeedSelection.request("history")
        val latest = checkNotNull(YouTubeFeedSelection.requests.value)
        assertEquals("history", latest.key)
        assertEquals(before + 2L, latest.serial)
    }
}
