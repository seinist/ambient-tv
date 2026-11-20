package com.ambient.tv.ui.games

import com.ambient.tv.games.GameHdrToneMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameVulkanTransferGateTest {
    private val allDisplay = GameHdrDisplayCapabilities(hdr10 = true, hlg = true)
    private val allSurface = GameVulkanSurfaceFormats(1 or 2 or 4 or 8 or 16)

    @Test fun manualInputIsNotInferredFromVulkanPrecision() {
        assertEquals(GameVulkanTransferMode.SDR, GameVulkanTransferMode.forInput(GameHdrToneMap.OFF))
        assertEquals(GameVulkanTransferMode.PQ_BT2020, GameVulkanTransferMode.forInput(GameHdrToneMap.PQ))
        assertEquals(GameVulkanTransferMode.HLG_BT2020, GameVulkanTransferMode.forInput(GameHdrToneMap.HLG))
        assertTrue(GameVulkanTransferGate.supported(GameVulkanTransferMode.SDR, allDisplay, allSurface))
        assertTrue(GameVulkanTransferGate.supported(GameVulkanTransferMode.PQ_BT2020, allDisplay, allSurface))
        assertTrue(GameVulkanTransferGate.supported(GameVulkanTransferMode.HLG_BT2020, allDisplay, allSurface))
    }

    @Test fun displayAndActualWindowFormatMustBothSupportTheSameEotf() {
        assertFalse(GameVulkanTransferGate.supported(GameVulkanTransferMode.PQ_BT2020,
            allDisplay.copy(hdr10 = false), allSurface))
        assertFalse(GameVulkanTransferGate.supported(GameVulkanTransferMode.HLG_BT2020,
            allDisplay.copy(hlg = false), allSurface))
        assertFalse(GameVulkanTransferGate.supported(GameVulkanTransferMode.PQ_BT2020,
            allDisplay, GameVulkanSurfaceFormats(1 or 2 or 8)))
        assertFalse(GameVulkanTransferGate.supported(GameVulkanTransferMode.HLG_BT2020,
            allDisplay, GameVulkanSurfaceFormats(1 or 2 or 4)))
        assertFalse(GameVulkanTransferGate.supported(GameVulkanTransferMode.HLG_BT2020,
            allDisplay, GameVulkanSurfaceFormats(8)))
    }

    @Test fun actualNativeResultMustMatchRequestedTransferNotOnlyPresentBit() {
        val sdr = GameVulkanSdrStatus(7)
        val pq = GameVulkanSdrStatus(7 or 16)
        val hlg = GameVulkanSdrStatus(7 or 32)
        assertTrue(GameVulkanTransferGate.frameConfirmed(GameVulkanTransferMode.SDR, sdr))
        assertTrue(GameVulkanTransferGate.frameConfirmed(GameVulkanTransferMode.PQ_BT2020, pq))
        assertTrue(GameVulkanTransferGate.frameConfirmed(GameVulkanTransferMode.HLG_BT2020, hlg))
        assertFalse(GameVulkanTransferGate.frameConfirmed(GameVulkanTransferMode.PQ_BT2020, sdr))
        assertFalse(GameVulkanTransferGate.frameConfirmed(GameVulkanTransferMode.HLG_BT2020, pq))
        assertFalse(GameVulkanTransferGate.frameConfirmed(GameVulkanTransferMode.SDR, hlg))
        assertFalse(GameVulkanTransferGate.frameConfirmed(GameVulkanTransferMode.PQ_BT2020,
            GameVulkanSdrStatus(1 or 8 or 16)))
    }
}
