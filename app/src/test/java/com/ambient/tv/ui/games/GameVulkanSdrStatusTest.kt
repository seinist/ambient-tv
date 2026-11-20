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
        assertEquals(null, GameVulkanSdrStatus(1 or 8 or 16).presentingTransfer)
        assertEquals(GameVulkanTransferMode.SDR, GameVulkanSdrStatus(7).presentingTransfer)
        assertEquals(GameVulkanTransferMode.PQ_BT2020, GameVulkanSdrStatus(23).presentingTransfer)
        assertEquals(GameVulkanTransferMode.HLG_BT2020, GameVulkanSdrStatus(39).presentingTransfer)
        assertEquals(null, GameVulkanSdrStatus(55).presentingTransfer)
    }
}
