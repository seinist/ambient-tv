package com.ambient.tv.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerReturnFocusPolicyTest {
    @Test
    fun leavingEveryFullscreenPlayerRestoresContentFocus() {
        listOf(
            Destinations.PLAYER,
            Destinations.TVH_PLAYER,
            Destinations.YOUTUBE_PLAYER,
            Destinations.SPOTIFY_PLAYER,
        ).forEach { playerRoute ->
            assertTrue(returnsFromFullscreenPlayer(playerRoute, Destinations.HOME))
            assertTrue(returnsFromFullscreenPlayer(playerRoute, Destinations.DETAIL))
        }
    }

    @Test
    fun browseNavigationAndPlayerReplacementDoNotTriggerReturnFocus() {
        assertFalse(returnsFromFullscreenPlayer(Destinations.HOME, Destinations.SECTION))
        assertFalse(returnsFromFullscreenPlayer(Destinations.PLAYER, Destinations.PLAYER))
        assertFalse(returnsFromFullscreenPlayer(null, Destinations.HOME))
    }
}
