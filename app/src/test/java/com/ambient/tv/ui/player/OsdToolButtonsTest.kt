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

    @Test fun youtubeOffersPreviousAndNextInItsOsdSettings() {
        assertTrue(OsdButton.NEXT in OsdPlayer.YOUTUBE.buttons)
        assertTrue(osdButtonVisible(OsdButton.NEXT, OsdPlayer.YOUTUBE, emptySet()))
    }

    @Test fun youtubePreviousAndNextVisibilityIsIndependentOfOtherPlayers() {
        val hidden = setOf("YOUTUBE/NEXT")
        assertFalse(osdButtonVisible(OsdButton.NEXT, OsdPlayer.YOUTUBE, hidden))
        assertTrue(osdButtonVisible(OsdButton.NEXT, OsdPlayer.MEDIA, hidden))
        assertTrue(osdButtonVisible(OsdButton.NEXT, OsdPlayer.SPOTIFY, hidden))
    }
}
