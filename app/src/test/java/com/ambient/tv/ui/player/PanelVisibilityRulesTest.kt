package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelVisibilityRulesTest {
    @Test fun bitstreamToggleOnlyForPassthroughCodecs() {
        assertTrue(bitstreamToggleApplies("truehd"))
        assertTrue(bitstreamToggleApplies("eac3"))
        assertTrue(bitstreamToggleApplies(null))
        assertFalse(bitstreamToggleApplies("aac"))
        assertFalse(bitstreamToggleApplies("flac"))
    }

    @Test fun bitstreamToggleRestoresPreviousOutput() {
        val permitsAll: (String) -> Boolean = { it != "pcm" }
        assertEquals("pcm", bitstreamToggleOutput(false, "source", null, permitsAll))
        assertEquals("source", bitstreamToggleOutput(true, "pcm", "source", permitsAll))
        assertEquals("auto", bitstreamToggleOutput(true, "pcm", "auto", permitsAll))
        assertEquals("bitstream", bitstreamToggleOutput(true, "pcm", null, permitsAll))
        // "auto" with PCM processing does not permit bitstream: fall back to an explicit request.
        assertEquals("bitstream", bitstreamToggleOutput(true, "pcm", "auto") { it == "bitstream" })
        assertEquals("bitstream", bitstreamToggleOutput(true, "auto", null) { it == "bitstream" })
    }

    @Test fun superResOnlyWhenItWouldLoad() {
        assertTrue(mpvSuperResRowVisible("ewa_lanczos", VideoScaleRelation.UPSCALE))
        assertTrue(mpvSuperResRowVisible("ewa_lanczos", VideoScaleRelation.UNKNOWN))
        assertFalse(mpvSuperResRowVisible("ewa_lanczos", VideoScaleRelation.NATIVE))
        assertFalse(mpvSuperResRowVisible("ewa_lanczos", VideoScaleRelation.DOWNSCALE))
        assertFalse(mpvSuperResRowVisible("fsr", VideoScaleRelation.UPSCALE))
        assertFalse(mpvSuperResRowVisible("nis", VideoScaleRelation.UPSCALE))
    }
}
