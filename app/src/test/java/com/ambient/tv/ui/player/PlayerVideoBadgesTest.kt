package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.ambient.tv.ui.display.SdrToHdrStatus

class PlayerVideoBadgesTest {
    @Test fun onlyConfirmedConversionIsAdvertised() {
        assertEquals("SDR → HDR", playerDynamicRangeLabel(null, SdrToHdrStatus.ACTIVE))
        for (status in SdrToHdrStatus.entries.filter { it != SdrToHdrStatus.ACTIVE }) {
            assertEquals("SDR", playerDynamicRangeLabel(null, status))
            assertEquals("HDR10+", playerDynamicRangeLabel("HDR10+", status))
        }
        assertEquals("DOLBY VISION", playerDynamicRangeLabel("Dolby Vision", null))
        assertEquals("HLG", playerDynamicRangeLabel("HLG", null))
    }

    @Test fun croppedCinemaKeepsItsResolutionTier() {
        assertEquals("4K", playerResolutionLabel(3840, 1600))
        assertEquals("4K", playerResolutionLabel(4096, 1716))
        assertEquals("Full HD", playerResolutionLabel(1920, 800))
        assertEquals("HD", playerResolutionLabel(1280, 536))
    }

    @Test fun standardAndPortraitSizes() {
        assertEquals("8K", playerResolutionLabel(7680, 4320))
        assertEquals("QHD", playerResolutionLabel(2560, 1440))
        assertEquals("Full HD", playerResolutionLabel(1080, 1920))
        assertEquals("SD", playerResolutionLabel(720, 576))
    }

    @Test fun unknownAndUnusualSizesDoNotInventATier() {
        assertNull(playerResolutionLabel(null, null))
        assertNull(playerResolutionLabel(-1, 1080))
        assertNull(playerResolutionLabel(1920, 0))
        assertEquals("1100×600", playerResolutionLabel(1100, 600))
    }
}
