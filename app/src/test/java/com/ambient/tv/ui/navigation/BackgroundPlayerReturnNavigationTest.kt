package com.ambient.tv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BackgroundPlayerReturnNavigationTest {
    @Test fun concreteRouteRetainsExactFeedAndEncodesProviderIds() {
        assertEquals(
            "youtube_feed/Abos%2FNeu",
            concretePlaybackOriginRoute(Destinations.YOUTUBE_FEED, mapOf("feedKey" to "Abos/Neu")),
        )
        assertEquals(
            "live_tv/guide?sourceId=iptv%3Aone",
            concretePlaybackOriginRoute(Destinations.LIVE_TV_SECTION, mapOf("tab" to "guide", "sourceId" to "iptv:one")),
        )
        assertEquals(
            "detail/series%3Aone?collectionId=abc&collectionSort=name",
            concretePlaybackOriginRoute(Destinations.DETAIL,
                mapOf("ratingKey" to "series:one", "collectionId" to "abc", "collectionSort" to "name")),
        )
    }

    @Test fun optionalQueryDoesNotProduceNullAndMissingPathCannotNavigate() {
        assertEquals("live_tv/live",
            concretePlaybackOriginRoute(Destinations.LIVE_TV_SECTION, mapOf("tab" to "live")))
        assertEquals("youtube_channel/UC123",
            concretePlaybackOriginRoute(Destinations.YOUTUBE_CHANNEL, mapOf("channelId" to "UC123")))
        assertEquals(Destinations.HOME, concretePlaybackOriginRoute(Destinations.HOME, emptyMap()))
        assertEquals(
            "youtube_feed/subscriptions",
            displayedPlaybackBrowseRoute(
                "youtube_feed/history", Destinations.YOUTUBE_FEED, "subscriptions", "home", null, null,
            ),
        )
        assertEquals(
            "radio_section/favorites",
            displayedPlaybackBrowseRoute(
                "radio", Destinations.RADIO, null, "home", null, "favorites",
            ),
        )
        assertEquals(
            "telegram_section/channel%3A100",
            displayedPlaybackBrowseRoute(
                "telegram", Destinations.TELEGRAM, null, "home", "channel:100", null,
            ),
        )
        assertNull(concretePlaybackOriginRoute(Destinations.YOUTUBE_FEED, emptyMap()))
        assertNull(concretePlaybackOriginRoute(null, emptyMap()))
    }

    @Test fun backOnlyOpensRunningMiniPlayerAfterClosingSidebar() {
        val navigation = BackgroundPlayerReturnNavigation()
        assertFalse(navigation.canResumeAfterSidebarBack(true))
        navigation.armAfterSidebarBack(true)
        assertTrue(navigation.canResumeAfterSidebarBack(true))
        assertFalse(navigation.canResumeAfterSidebarBack(false))
        navigation.disarm() // D-pad moved the selection or the user navigated
        assertFalse(navigation.canResumeAfterSidebarBack(true))
        navigation.armAfterSidebarBack(false)
        assertFalse(navigation.canResumeAfterSidebarBack(true))
    }

    @Test fun fullScreenReturnPreservesOriginAndARealNewPlayReplacesIt() {
        val navigation = BackgroundPlayerReturnNavigation()
        val smartTube = BackgroundPlayerOrigin(
            "youtube_feed/subscriptions", "feed-1", "youtube_feed/history",
        )
        val plex = BackgroundPlayerOrigin("section/movies", "section-2")
        navigation.enterPlayer("player-1", smartTube)
        assertEquals(smartTube, navigation.origin)
        assertEquals("youtube_feed/history", navigation.origin?.backStackRoute)
        navigation.enterBrowse() // player minimized
        navigation.armAfterSidebarBack(true)
        navigation.markMiniPlayerReentry("player-2")
        navigation.enterPlayer("player-2", plex) // current view is Plex, not the playback origin
        assertEquals(smartTube, navigation.origin)
        assertFalse(navigation.armedBySidebarBack)

        navigation.enterPlayer("player-3", plex) // a newly selected item
        assertEquals(plex, navigation.origin)
        navigation.clearStoppedPlayback()
        assertNull(navigation.origin)
        assertFalse(navigation.canResumeAfterSidebarBack(true))
    }
}
