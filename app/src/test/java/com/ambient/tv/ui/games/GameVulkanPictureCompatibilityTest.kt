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

    @Test fun onlyAllowlistedOriginalSlangPresetsEligibleForNativeShaderOutput() {
        val allowed = setOf(
            GameShader.CRT_LIGHT, GameShader.CRT_GEOMETRY, GameShader.CRT_APERTURE,
            GameShader.CRT_GLOW, // two-pass Vulkan Slang framebuffer chain
            GameShader.GAMEBOY_DMG, GameShader.GBC_GRID, GameShader.NTSC_COMPOSITE,
            GameShader.COLOR_BOOST, GameShader.SHARP_BILINEAR,
        )
        GameShader.entries.forEach { shader ->
            assertTrue("Shader eligibility must be explicit: " + shader.key,
                GameVulkanSlangPresets.supports(shader, null) == (shader in allowed))
            assertFalse(GameVulkanSlangPresets.supports(shader, "external-pack/example"))
        }
    }

    @Test fun sharpIsNotPixelPerfectWithNearestVulkanBlit() {
        assertFalse(GameVulkanPictureCompatibility.rawFullscreenEligible(GameShader.SHARP))
        assertFalse(GameVulkanPictureCompatibility.rawFullscreenEligible(GameShader.SMOOTH))
        assertFalse(GameVulkanPictureCompatibility.rawFullscreenEligible(GameShader.CRT))
        assertFalse(GameVulkanPictureCompatibility.rawFullscreenEligible(GameShader.LCD))
    }
}
