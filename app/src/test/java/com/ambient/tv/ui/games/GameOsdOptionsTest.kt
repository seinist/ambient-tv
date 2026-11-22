package com.ambient.tv.ui.games

import com.ambient.tv.ui.player.OsdButton
import com.ambient.tv.ui.player.OsdPlayer
import com.ambient.tv.ui.player.osdButtonVisible
import com.ambient.tv.ui.player.osdHiddenAfterToggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameOsdOptionsTest {
    @Test fun everyGameActionCanBeHiddenWithoutChangingVideoVisibility() {
        var hidden = setOf("MEDIA/EXIT", "YOUTUBE/NEXT")
        gameOsdButtons(achievements = true, multipleDiscs = true).forEach { button ->
            assertTrue(osdButtonVisible(button, OsdPlayer.GAMES, hidden))
            hidden = osdHiddenAfterToggle(hidden, OsdPlayer.GAMES, button, visible = false)
            assertFalse(osdButtonVisible(button, OsdPlayer.GAMES, hidden))
        }
        assertTrue(hidden.containsAll(setOf("MEDIA/EXIT", "YOUTUBE/NEXT")))
        assertTrue(osdButtonVisible(OsdButton.EXIT, OsdPlayer.YOUTUBE, hidden))
        OsdPlayer.GAMES.buttons.forEach { button ->
            hidden = osdHiddenAfterToggle(hidden, OsdPlayer.GAMES, button, visible = true)
        }
        assertEquals(setOf("MEDIA/EXIT", "YOUTUBE/NEXT"), hidden)
    }

    @Test fun onlyAvailableConditionalGameActionsAreOffered() {
        val offered = gameOsdButtons(achievements = false, multipleDiscs = false)
        assertFalse(OsdButton.GAME_ACHIEVEMENTS in offered)
        assertFalse(OsdButton.GAME_DISC in offered)
        assertTrue(OsdButton.GAME_PICTURE in offered)
        assertTrue(OsdButton.GAME_CONTROLS in offered)
        assertTrue(OsdButton.EXIT in offered)
        assertEquals(OsdPlayer.GAMES.buttons, gameOsdButtons(achievements = true, multipleDiscs = true))
    }
}
