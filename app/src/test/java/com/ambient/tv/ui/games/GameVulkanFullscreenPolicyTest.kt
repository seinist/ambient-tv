package com.ambient.tv.ui.games

import com.ambient.tv.games.GameHdrToneMap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameVulkanFullscreenPolicyTest {
    @Test fun fullscreenIsManualAndRequiresConfirmedNativeSdrFrames() {
        val presenter = GameVulkanSdrStatus(7)
        assertTrue(GameVulkanFullscreenPolicy.canEnable(true, GameHdrToneMap.OFF, true, presenter, false))
        assertFalse(GameVulkanFullscreenPolicy.canEnable(false, GameHdrToneMap.OFF, true, presenter, false))
        assertFalse(GameVulkanFullscreenPolicy.canEnable(true, GameHdrToneMap.OFF, false, presenter, false))
        assertFalse(GameVulkanFullscreenPolicy.canEnable(true, GameHdrToneMap.OFF, true, presenter, true))
        assertFalse(GameVulkanFullscreenPolicy.canEnable(true, GameHdrToneMap.OFF, true, GameVulkanSdrStatus(3), false))
        assertFalse(GameVulkanFullscreenPolicy.canEnable(true, GameHdrToneMap.OFF, true, GameVulkanSdrStatus(9), false))
    }

    @Test fun hdrInputMustNeverUseRawSdrSwapchain() {
        val presenter = GameVulkanSdrStatus(7)
        assertFalse(GameVulkanFullscreenPolicy.canEnable(true, GameHdrToneMap.PQ, true, presenter, false))
        assertFalse(GameVulkanFullscreenPolicy.canEnable(true, GameHdrToneMap.HLG, true, presenter, false))
    }

    @Test fun onlyConfirmedPresentingCanExposeRequestedFullscreen() {
        assertFalse(GameVulkanFullscreenPolicy.shouldShowFullscreen(false, GameVulkanSdrStatus(7)))
        assertFalse(GameVulkanFullscreenPolicy.shouldShowFullscreen(true, GameVulkanSdrStatus(3)))
        assertTrue(GameVulkanFullscreenPolicy.shouldShowFullscreen(true, GameVulkanSdrStatus(7)))
        assertFalse(GameVulkanFullscreenPolicy.mustFallback(GameVulkanSdrStatus(3)))
        assertTrue(GameVulkanFullscreenPolicy.mustFallback(GameVulkanSdrStatus(9)))
    }
}
