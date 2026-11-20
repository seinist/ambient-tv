package com.ambient.tv.ui.games

import org.junit.Assert.assertEquals
import org.junit.Test

class GameVulkanSdrStatusTest {
    @Test fun nativeStatusNeverMistakesRequestedWindowForPresentation() {
        assertEquals(GameVulkanSdrStatus.Phase.OFF, GameVulkanSdrStatus().phase)
        assertEquals(GameVulkanSdrStatus.Phase.REQUESTED, GameVulkanSdrStatus(1).phase)
        assertEquals(GameVulkanSdrStatus.Phase.SWAPCHAIN, GameVulkanSdrStatus(3).phase)
        assertEquals(GameVulkanSdrStatus.Phase.PRESENTING, GameVulkanSdrStatus(7).phase)
    }

    @Test fun fallbackAlwaysWinsOverPreviouslyPresentedStatus() {
        assertEquals(GameVulkanSdrStatus.Phase.GLES_FALLBACK, GameVulkanSdrStatus(9).phase)
        assertEquals(GameVulkanSdrStatus.Phase.GLES_FALLBACK, GameVulkanSdrStatus(15).phase)
        assertEquals(GameVulkanSdrStatus.Phase.OFF, GameVulkanSdrStatus(14).phase)
    }
}
