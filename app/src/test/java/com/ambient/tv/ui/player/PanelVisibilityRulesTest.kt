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
        // Without a remembered output the toggle asks for "auto", never for forced passthrough.
        assertEquals("auto", bitstreamToggleOutput(true, "pcm", null, permitsAll))
        assertEquals("force", bitstreamToggleOutput(true, "pcm", "force", permitsAll))
        assertEquals("force", bitstreamToggleOutput(true, "force", null, permitsAll))
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
