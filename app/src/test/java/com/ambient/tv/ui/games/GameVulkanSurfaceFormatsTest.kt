package com.ambient.tv.ui.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameVulkanSurfaceFormatsTest {
    @Test fun absentDisplaySurfaceReportsNoHdr() {
        val absent = GameVulkanSurfaceFormats(0)
        assertFalse(absent.presentable)
        assertFalse(absent.hdr10Pq)
        assertFalse(absent.hlg)
        assertEquals("Unavailable / unknown", absent.candidateTypesLabel())
    }

    @Test fun untrustedBitsWithoutPresentableQueueCannotEnableHdr() {
        val noPresentQueue = GameVulkanSurfaceFormats(2 or 4 or 8 or 16 or 256)
        assertFalse(noPresentQueue.sdr)
        assertFalse(noPresentQueue.hdr10Pq)
        assertFalse(noPresentQueue.hlg)
        assertFalse(noPresentQueue.scrgbFp16)
    }

    @Test fun availablePairsAreIndividualNotInterchangeable() {
        val sdr = GameVulkanSurfaceFormats(1 or 2)
        assertTrue(sdr.presentable)
        assertTrue(sdr.sdr)
        assertFalse(sdr.hdr10Pq)
        assertFalse(sdr.hlg)
        val pq = GameVulkanSurfaceFormats(1 or 4)
        assertTrue(pq.hdr10Pq)
        assertFalse(pq.hlg)
        assertFalse(pq.scrgbFp16)
        assertEquals("HDR10 PQ", pq.candidateTypesLabel())
        assertEquals("SDR · HDR10 PQ · HLG · scRGB FP16", GameVulkanSurfaceFormats(31).candidateTypesLabel())
    }
}
