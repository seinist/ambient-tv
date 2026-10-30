package com.ambient.tv.ui.common

import androidx.compose.ui.input.key.Key
import com.ambient.tv.ui.player.PLAYER_PANEL_ENTER_MS
import com.ambient.tv.ui.player.PLAYER_PANEL_EXIT_MS
import com.ambient.tv.ui.player.PLAYER_PANEL_REDUCED_MS
import com.ambient.tv.ui.player.playerPanelDrawerAlpha
import com.ambient.tv.ui.player.playerPanelDrawerTranslationFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RightSidePanelMotionTest {
    @Test fun backAndEscapeAreModalCloseKeysNotDirectionalNavigation() {
        assertTrue(rightPanelBackKey(Key.Back))
        assertTrue(rightPanelBackKey(Key.Escape))
        assertFalse(rightPanelBackKey(Key.DirectionLeft))
        assertFalse(rightPanelBackKey(Key.DirectionRight))
        assertFalse(rightPanelBackKey(Key.Enter))
    }

    @Test fun simultaneousSystemBackRemoteBackOutsideTapAndFooterCloseOnlyOnce() {
        val dismiss = RightPanelDismissOnce()
        assertFalse(dismiss.closing)
        // Every dismiss source uses the same gate while the outgoing drawer animates.
        assertTrue(dismiss.claim())
        assertTrue(dismiss.closing)
        assertFalse(dismiss.claim())
        assertFalse(dismiss.claim())
        assertFalse(dismiss.claim())
    }

    @Test fun standaloneDrawersUseTheExistingPlayerMotionContract() {
        assertEquals(250, PLAYER_PANEL_ENTER_MS)
        assertEquals(210, PLAYER_PANEL_EXIT_MS)
        assertEquals(120, PLAYER_PANEL_REDUCED_MS)
        assertEquals(1f, playerPanelDrawerTranslationFraction(0f, reducedEffects = false), 0f)
        assertEquals(0f, playerPanelDrawerTranslationFraction(1f, reducedEffects = false), 0f)
        assertEquals(0f, playerPanelDrawerTranslationFraction(0f, reducedEffects = true), 0f)
        assertEquals(1f, playerPanelDrawerAlpha(1f, reducedEffects = false), 0f)
        assertEquals(0f, playerPanelDrawerAlpha(0f, reducedEffects = true), 0f)
    }
}
