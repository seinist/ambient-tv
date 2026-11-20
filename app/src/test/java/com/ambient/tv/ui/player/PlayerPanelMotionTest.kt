package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPanelMotionTest {
    @Test fun hierarchyDepthDefinesDirectedContentMotion() {
        assertEquals(PlayerPanelContentMotion.FORWARD, playerPanelContentMotion(1, 2))
        assertEquals(PlayerPanelContentMotion.FORWARD, playerPanelContentMotion(2, 4))
        assertEquals(PlayerPanelContentMotion.BACKWARD, playerPanelContentMotion(3, 2))
        assertEquals(PlayerPanelContentMotion.REPLACE, playerPanelContentMotion(1, 1))
    }

    @Test fun normalDrawerSlidesFromTheRightAndKeepsOnlyASubtleFade() {
        assertEquals(1f, playerPanelDrawerTranslationFraction(0f, reducedEffects = false), 0.0001f)
        assertEquals(0.5f, playerPanelDrawerTranslationFraction(0.5f, reducedEffects = false), 0.0001f)
        assertEquals(0f, playerPanelDrawerTranslationFraction(1f, reducedEffects = false), 0.0001f)
        assertEquals(PLAYER_PANEL_DRAWER_MIN_ALPHA, playerPanelDrawerAlpha(0f, reducedEffects = false), 0.0001f)
        assertEquals(1f, playerPanelDrawerAlpha(1f, reducedEffects = false), 0.0001f)
    }

    @Test fun reducedEffectsStillSlidesInButKeepsTheLightweightFade() {
        // Sparsam uses a shorter tween, not a fade-only popup.
        assertEquals(1f, playerPanelDrawerTranslationFraction(0f, reducedEffects = true), 0.0001f)
        assertEquals(0.5f, playerPanelDrawerTranslationFraction(0.5f, reducedEffects = true), 0.0001f)
        assertEquals(0f, playerPanelDrawerTranslationFraction(1f, reducedEffects = true), 0.0001f)
        assertEquals(0f, playerPanelDrawerAlpha(0f, reducedEffects = true), 0.0001f)
        assertEquals(0.5f, playerPanelDrawerAlpha(0.5f, reducedEffects = true), 0.0001f)
        assertEquals(1f, playerPanelDrawerAlpha(1f, reducedEffects = true), 0.0001f)
    }

    @Test fun entranceGateWaitsForPlacementAndCanResetForAnotherSession() {
        val gate = PanelEntranceGate()
        assertFalse(gate.placed)
        gate.markPlaced()
        assertTrue(gate.placed)
        gate.markPlaced()
        assertTrue(gate.placed)
        gate.reset()
        assertFalse(gate.placed)
    }
}
