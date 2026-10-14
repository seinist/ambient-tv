package com.ambient.tv.ui.common.projectm

import com.ambient.tv.ui.display.SdrToHdrSwitchFacts
import org.junit.Assert.*
import org.junit.Test

class ProjectMHdrSupportTest {
    private val supported = SdrToHdrSwitchFacts(35, true, true, "auto", false, 1f, 600f)

    @Test fun unsupportedDisplaysAndMissingOutputHideHdrOptions() {
        assertTrue(projectMHdrSupported(supported, true))
        assertFalse(projectMHdrSupported(supported, false))
        for (facts in listOf(
            supported.copy(sdk = 33), supported.copy(displayHdr = false),
            supported.copy(wideColor = false), supported.copy(hdr10Supported = false),
            supported.copy(ratio = null), supported.copy(peakNits = null),
            supported.copy(reducedEffects = true),
        )) assertFalse(projectMHdrSupported(facts, true))
    }

    @Test fun turningHdrOffDoesNotHideSupportedHardware() {
        assertTrue(projectMHdrSupported(supported.copy(mode = "sdr"), true))
    }
}
