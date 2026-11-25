package com.ambient.tv.ui.games

import com.ambient.tv.games.GameHdrToneMap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameVulkanFullscreenPolicyTest {
    private val display = GameHdrDisplayCapabilities(hdr10 = true, hlg = true)
    private val surface = GameVulkanSurfaceFormats(1 or 2 or 4 or 8 or 16)

    private fun can(
        input: GameHdrToneMap = GameHdrToneMap.OFF,
        vulkan: Boolean = true,
        diagnostic: Boolean = true,
        status: GameVulkanSdrStatus = GameVulkanSdrStatus(7),
        netplay: Boolean = false,
        caps: GameHdrDisplayCapabilities = display,
        formats: GameVulkanSurfaceFormats = surface,
    ) = GameVulkanFullscreenPolicy.canEnable(
        vulkan, input, diagnostic, status, netplay, caps, formats,
    )

    @Test fun fullscreenIsManualAndNeedsActuallyPresentedFrames() {
        assertTrue(can())
        assertFalse(can(vulkan = false))
        assertFalse(can(diagnostic = false))
        assertFalse(can(netplay = true))
        assertFalse(can(status = GameVulkanSdrStatus(3)))
        assertFalse(can(status = GameVulkanSdrStatus(9)))
    }

    @Test fun hdrRequiresMatchingEotfAndPhysicallyPresentingTenBitFrames() {
        assertFalse(can(input = GameHdrToneMap.PQ)) // SDR frame is not HDR
        assertFalse(can(input = GameHdrToneMap.HLG))
        assertTrue(can(input = GameHdrToneMap.PQ, status = GameVulkanSdrStatus(23)))
        assertTrue(can(input = GameHdrToneMap.HLG, status = GameVulkanSdrStatus(39)))
        assertFalse(can(input = GameHdrToneMap.PQ, status = GameVulkanSdrStatus(39)))
        assertFalse(can(input = GameHdrToneMap.HLG, status = GameVulkanSdrStatus(23)))
        assertFalse(can(input = GameHdrToneMap.PQ, status = GameVulkanSdrStatus(23),
            caps = display.copy(hdr10 = false)))
        assertFalse(can(input = GameHdrToneMap.HLG, status = GameVulkanSdrStatus(39),
            formats = GameVulkanSurfaceFormats(1 or 2 or 4)))
    }

    @Test fun fullscreenPromotionAlwaysRequiresMatchingPresentSuccess() {
        assertFalse(GameVulkanFullscreenPolicy.shouldShowFullscreen(false, GameVulkanSdrStatus(7)))
        assertFalse(GameVulkanFullscreenPolicy.shouldShowFullscreen(true, GameVulkanSdrStatus(3)))
        assertTrue(GameVulkanFullscreenPolicy.shouldShowFullscreen(true, GameVulkanSdrStatus(7)))
        assertFalse(GameVulkanFullscreenPolicy.shouldShowFullscreen(
            true, GameVulkanSdrStatus(7), GameVulkanTransferMode.PQ_BT2020))
        assertTrue(GameVulkanFullscreenPolicy.shouldShowFullscreen(
            true, GameVulkanSdrStatus(23), GameVulkanTransferMode.PQ_BT2020))
        assertTrue(GameVulkanFullscreenPolicy.shouldShowFullscreen(
            true, GameVulkanSdrStatus(39), GameVulkanTransferMode.HLG_BT2020))
        assertFalse(GameVulkanFullscreenPolicy.mustFallback(GameVulkanSdrStatus(3)))
        assertTrue(GameVulkanFullscreenPolicy.mustFallback(GameVulkanSdrStatus(9)))
    }

    @Test fun skippingGlesNeedsAConfirmedFullscreenSurfaceInTheCurrentTransfer() {
        fun skip(requested: Boolean = true, visible: Boolean = true, ready: Boolean = true,
                 bits: Int = 7, transfer: GameVulkanTransferMode = GameVulkanTransferMode.SDR) =
            GameVulkanFullscreenPolicy.canSkipGles(requested, visible, ready, GameVulkanSdrStatus(bits), transfer)
        assertTrue(skip())
        assertFalse(skip(requested = false)) // Menu or pause
        assertFalse(skip(visible = false)) // Tiny diagnostic probe
        assertFalse(skip(ready = false)) // Fullscreen resize still pending
        assertFalse(skip(bits = 1)) // Newly attached surface
        assertFalse(skip(bits = 9)) // Lost presentation
        assertFalse(skip(transfer = GameVulkanTransferMode.PQ_BT2020)) // Old SDR success
        assertTrue(skip(bits = 23, transfer = GameVulkanTransferMode.PQ_BT2020))
    }
}
