package com.ambient.tv.ui.games

import com.ambient.tv.games.GameShader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameVulkanPictureCompatibilityTest {
    @Test fun onlyActualUnfilteredPixelPresetMatchesRawNativeVulkanBlit() {
        assertTrue(GameVulkanPictureCompatibility.rawFullscreenEligible(GameShader.PIXEL))
        for (shader in GameShader.entries.filterNot { it == GameShader.PIXEL }) {
            assertFalse(GameVulkanPictureCompatibility.rawFullscreenEligible(shader))
        }
    }

    @Test fun sharpIsNotPixelPerfectWithNearestVulkanBlit() {
        assertFalse(GameVulkanPictureCompatibility.rawFullscreenEligible(GameShader.SHARP))
        assertFalse(GameVulkanPictureCompatibility.rawFullscreenEligible(GameShader.SMOOTH))
        assertFalse(GameVulkanPictureCompatibility.rawFullscreenEligible(GameShader.CRT))
        assertFalse(GameVulkanPictureCompatibility.rawFullscreenEligible(GameShader.LCD))
    }
}
