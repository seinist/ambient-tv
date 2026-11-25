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
    private val accepted = GameCoreHdrPixelHandshake(3)

    @Test fun knownCoreNeedsAcceptedLibretroHdr10AndObservedTenBitVulkanImage() {
        val source = GameVulkanHdrSourceEvidence.observe(
            "mednafen_psx_hw", true, beetleHdr, GameVulkanCoreSourceFormat(64), accepted)
        assertEquals(GameVulkanTransferMode.PQ_BT2020, source.transfer)
        assertEquals(9, source.primariesH273)
        assertEquals(16, source.transferH273)
        assertTrue(source.supports(GameVulkanTransferMode.PQ_BT2020))
        assertFalse(source.supports(GameVulkanTransferMode.HLG_BT2020))
        assertNull(source.maxCll)
        assertNull(source.maxFall)
        assertNull(source.masteringMaxNits)
    }

    @Test fun optionAndTenBitNeverProveHdrWithoutCoreHandshake() {
        for (status in listOf(0, 1, 4, 5)) {
            val source = GameVulkanHdrSourceEvidence.observe(
                "mednafen_psx_hw", true, beetleHdr, GameVulkanCoreSourceFormat(64),
                GameCoreHdrPixelHandshake(status))
            assertNull(source.transfer)
        }
        assertFalse(GameCoreHdrPixelHandshake(1).hdr10Accepted)
        assertFalse(GameCoreHdrPixelHandshake(4).hdr10Accepted)
        assertTrue(GameCoreHdrPixelHandshake(3).hdr10Accepted)
        assertTrue(GameCoreHdrPixelHandshake(7).hdr10Accepted) // Rejected scRGB leaves accepted PQ intact.
        assertEquals("HDR10/PQ core pixel format rejected", GameCoreHdrPixelHandshake(1).description)
    }

    @Test fun bitDepthOrManualModeAloneNeverEstablishHdrSource() {
        val variants = listOf(
            GameVulkanHdrSourceEvidence.observe("mednafen_psx_hw", false, beetleHdr, GameVulkanCoreSourceFormat(64), accepted),
            GameVulkanHdrSourceEvidence.observe("mednafen_psx_hw", true, beetleHdr, GameVulkanCoreSourceFormat(37), accepted),
            GameVulkanHdrSourceEvidence.observe("mednafen_psx_hw", true, beetleHdr + ("beetle_psx_hw_color_format" to "24bit"), GameVulkanCoreSourceFormat(64), accepted),
            GameVulkanHdrSourceEvidence.observe("mednafen_psx_hw", true, beetleHdr + ("beetle_psx_hw_renderer" to "hardware_gl"), GameVulkanCoreSourceFormat(64), accepted),
            GameVulkanHdrSourceEvidence.observe("ppsspp", true, beetleHdr, GameVulkanCoreSourceFormat(64), accepted),
        )
        for (source in variants) {
            assertNull(source.transfer)
            assertFalse(source.supports(GameVulkanTransferMode.PQ_BT2020))
            assertFalse(source.supports(GameVulkanTransferMode.HLG_BT2020))
            assertTrue(source.supports(GameVulkanTransferMode.SDR))
        }
    }

    @Test fun noSpeculativeHlgOrMasteringMetadata() {
        val source = GameVulkanHdrSourceEvidence.observe(
            "mednafen_psx_hw", true, beetleHdr, GameVulkanCoreSourceFormat(58), accepted)
        assertEquals(GameVulkanTransferMode.PQ_BT2020, source.transfer)
        assertNull(source.masteringMinNits)
        assertNull(source.masteringMaxNits)
        assertNull(source.maxCll)
        assertNull(source.maxFall)
    }
}
