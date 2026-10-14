@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.display

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.ambient.tv.ui.player.HdrStandard
import com.ambient.tv.ui.player.HdrSupport
import org.junit.Assert.*
import org.junit.Test

class UiHdrSourceTest {
    private fun format(transfer: Int) = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265)
        .setColorInfo(ColorInfo.Builder().setColorTransfer(transfer).build()).build()

    @Test fun hdrToSdrAndUnknownStreamImmediatelyDisableHdrUi() {
        var hdr = false
        val output = object : UiHdrOutput {
            override fun hdr(headroom: Float) { hdr = true }
            override fun sdr() { hdr = false }
        }
        val controller = UiHdrController(output)
        val capableDisplay = UiHdrEligibility(35, true, true, true, true, true, 2f)
        val hdrSource = HdrSupport.sourceStandard(format(C.COLOR_TRANSFER_ST2084))
        assertEquals(HdrStandard.HDR10, hdrSource)
        controller.update(capableDisplay.copy(sourceSupported = hdrSource != null), "stream", 0)
        controller.update(capableDisplay.copy(sourceSupported = hdrSource != null), "stream", 1_000)
        assertTrue(hdr)
        val sdrSource = HdrSupport.sourceStandard(format(C.COLOR_TRANSFER_SDR))
        assertNull(sdrSource)
        controller.update(capableDisplay.copy(sourceSupported = sdrSource != null), "stream", 1_001)
        assertFalse(hdr)
        assertNull(HdrSupport.sourceStandard(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build()))
        assertNull(HdrSupport.sourceStandard(null))
        controller.update(capableDisplay.copy(sourceSupported = false), "unknown", 10_000)
        assertFalse(hdr)
    }

    @Test fun recognisesHlgAndDolbyVisionFromRuntimeFormat() {
        assertEquals(HdrStandard.HLG, HdrSupport.sourceStandard(format(C.COLOR_TRANSFER_HLG)))
        assertEquals(HdrStandard.DOLBY_VISION, HdrSupport.sourceStandard(Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).build()))
        assertEquals(HdrStandard.DOLBY_VISION, HdrSupport.sourceStandard(Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("dvh1.08.06").build()))
    }
}
