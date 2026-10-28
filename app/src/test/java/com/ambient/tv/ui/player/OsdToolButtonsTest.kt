package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class OsdToolButtonsTest {
    private val tools = listOf(OsdButton.SCREENSHOT, OsdButton.BOOKMARK, OsdButton.AB_REPEAT, OsdButton.FRAME_STEP,
        OsdButton.LYRICS, OsdButton.TOOLS)

    @Test fun toolButtonsStartHiddenAndCanBeSwitchedOn() {
        tools.forEach { button ->
            assertTrue(button.optIn)
            assertFalse(osdButtonVisible(button, OsdPlayer.MEDIA, emptySet()))
            assertTrue(osdButtonVisible(button, OsdPlayer.MEDIA, setOf("+MEDIA/${button.name}")))
        }
    }

    @Test fun videoPlayersOfferTheToolsSpotifyDoesNot() {
        listOf(OsdPlayer.MEDIA, OsdPlayer.YOUTUBE, OsdPlayer.LIVE_TV).forEach { assertTrue(it.buttons.containsAll(tools)) }
        assertTrue(OsdPlayer.SPOTIFY.buttons.none { it in tools })
    }
}
