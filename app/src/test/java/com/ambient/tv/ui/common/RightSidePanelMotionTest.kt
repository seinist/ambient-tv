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

    /**
     * Regression: in Low/Auto-reduced UI profiles the old drawer selected the player's
     * reduced-effects transform (always zero), leaving a fade instead of sliding in.
     * Standalone drawers must retain full-width movement in every profile.
     */
    @Test fun standaloneDrawersAlwaysSlideFromTheRightEdge() {
        assertEquals(1f, standaloneRightPanelTranslationFraction(0f), 0f)
        assertEquals(0.75f, standaloneRightPanelTranslationFraction(0.25f), 0f)
        assertEquals(0.5f, standaloneRightPanelTranslationFraction(0.5f), 0f)
        assertEquals(0.25f, standaloneRightPanelTranslationFraction(0.75f), 0f)
        assertEquals(0f, standaloneRightPanelTranslationFraction(1f), 0f)
        // Rapid Back during entrance slides from the *current* progress, never fades in place.
        assertEquals(0.65f, standaloneRightPanelTranslationFraction(0.35f), 0.0001f)
        // Alpha is only a slight accent on top of the translation (as in playback settings).
        assertTrue(standaloneRightPanelAlpha(0f) >= 0.80f)
        assertEquals(1f, standaloneRightPanelAlpha(1f), 0f)
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
