package com.ambient.tv.ui.games

import com.ambient.tv.games.GameAspect
import com.ambient.tv.games.GameShader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameVulkanPictureCompatibilityTest {
    @Test fun nativePreviewDoesNotCoverUnsupportedGlesFilters() {
        assertTrue(GameVulkanPictureCompatibility.rawFullscreenEligible(
            GameShader.SHARP, GameAspect.CORE, false))
        for (shader in GameShader.entries.filterNot { it == GameShader.SHARP }) {
            assertFalse(GameVulkanPictureCompatibility.rawFullscreenEligible(
                shader, GameAspect.CORE, false))
        }
    }

    @Test fun aspectAndWholePixelScalingRemainInGles() {
        assertFalse(GameVulkanPictureCompatibility.rawFullscreenEligible(
            GameShader.SHARP, GameAspect.FOUR_THREE, false))
        assertFalse(GameVulkanPictureCompatibility.rawFullscreenEligible(
            GameShader.SHARP, GameAspect.WIDE, false))
        assertFalse(GameVulkanPictureCompatibility.rawFullscreenEligible(
            GameShader.SHARP, GameAspect.CORE, true))
    }
}
