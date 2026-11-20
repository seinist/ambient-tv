package com.ambient.tv.ui.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameVulkanHdrSourceContractTest {
    private val beetleHdr = mapOf(
        "beetle_psx_hw_renderer" to "hardware_vk",
        "beetle_psx_hw_color_format" to "30bit_hdr",
    )
    @Test fun knownCoreRequiresExplicitHdrOptionAndObservedTenBitVulkanImage() {
        val source = GameVulkanHdrSourceEvidence.observe("mednafen_psx_hw", true, beetleHdr, GameVulkanCoreSourceFormat(64))
        assertEquals(GameVulkanTransferMode.PQ_BT2020, source.transfer)
        assertEquals(9, source.primariesH273)
        assertEquals(16, source.transferH273)
        assertTrue(source.supports(GameVulkanTransferMode.PQ_BT2020))
        assertFalse(source.supports(GameVulkanTransferMode.HLG_BT2020))
        assertNull(source.maxCll)
        assertNull(source.maxFall)
        assertNull(source.masteringMaxNits)
    }
    @Test fun bitDepthOrManualModeAloneNeverEstablishHdrSource() {
        val variants = listOf(
            GameVulkanHdrSourceEvidence.observe("mednafen_psx_hw", false, beetleHdr, GameVulkanCoreSourceFormat(64)),
            GameVulkanHdrSourceEvidence.observe("mednafen_psx_hw", true, beetleHdr, GameVulkanCoreSourceFormat(37)),
            GameVulkanHdrSourceEvidence.observe("mednafen_psx_hw", true, beetleHdr + ("beetle_psx_hw_color_format" to "24bit"), GameVulkanCoreSourceFormat(64)),
            GameVulkanHdrSourceEvidence.observe("mednafen_psx_hw", true, beetleHdr + ("beetle_psx_hw_renderer" to "hardware_gl"), GameVulkanCoreSourceFormat(64)),
            GameVulkanHdrSourceEvidence.observe("ppsspp", true, beetleHdr, GameVulkanCoreSourceFormat(64)),
        )
        for (source in variants) {
            assertNull(source.transfer)
            assertFalse(source.supports(GameVulkanTransferMode.PQ_BT2020))
            assertFalse(source.supports(GameVulkanTransferMode.HLG_BT2020))
            assertTrue(source.supports(GameVulkanTransferMode.SDR))
        }
    }
    @Test fun noSpeculativeHlgOrMasteringMetadata() {
        val source = GameVulkanHdrSourceEvidence.observe("mednafen_psx_hw", true, beetleHdr, GameVulkanCoreSourceFormat(58))
        assertEquals(GameVulkanTransferMode.PQ_BT2020, source.transfer)
        assertNull(source.masteringMinNits)
        assertNull(source.masteringMaxNits)
        assertNull(source.maxCll)
        assertNull(source.maxFall)
    }
}
