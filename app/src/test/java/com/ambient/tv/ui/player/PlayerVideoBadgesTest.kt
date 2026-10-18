package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun streamBadgesKeepAtMostFourExtras() {
        // MPV Live TV passes six values; with SVP as a seventh it would be cut, hence its own slot.
        val values = listOf("HDR10", "1920x1080", "25 fps", "h264", "mp2", "mediacodec", "SVP 25 → 50")
        val parts = playerStreamBadgeParts(values)
        assertEquals("1920x1080", parts.dimensions)
        assertEquals("HDR10", parts.hdr)
        assertEquals(PLAYER_STREAM_EXTRA_BADGES, parts.extras.size)
        assertEquals(listOf("25 fps", "h264", "mp2", "mediacodec"), parts.extras)
        assertFalse(parts.extras.any { it.startsWith("SVP") })
    }

    @Test fun streamBadgesSplitCombinedLabels() {
        val parts = playerStreamBadgeParts(listOf("1280x720 · H264", "AAC · AAC"))
        assertEquals("1280x720", parts.dimensions)
        assertEquals(listOf("H264", "AAC"), parts.extras)
    }

    @Test fun badgeLineFadesOnlyWhereCut() {
        assertEquals(false to false, playerBadgeRowFades(overflowing = false, marquee = true, scrollPx = 0, maxScrollPx = 0))
        assertEquals(false to false, playerBadgeRowFades(overflowing = false, marquee = false, scrollPx = 0, maxScrollPx = 0))
        assertEquals(true to true, playerBadgeRowFades(overflowing = true, marquee = true, scrollPx = 0, maxScrollPx = 0))
        assertEquals(false to true, playerBadgeRowFades(overflowing = true, marquee = false, scrollPx = 0, maxScrollPx = 120))
        assertEquals(true to true, playerBadgeRowFades(overflowing = true, marquee = false, scrollPx = 60, maxScrollPx = 120))
        assertEquals(true to false, playerBadgeRowFades(overflowing = true, marquee = false, scrollPx = 120, maxScrollPx = 120))
    }
}
