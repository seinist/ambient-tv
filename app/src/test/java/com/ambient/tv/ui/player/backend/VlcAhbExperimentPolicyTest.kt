package com.ambient.tv.ui.player.backend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcAhbExperimentPolicyTest {
    @Test fun dolbyVisionRpuFallbackNeverOverridesNativeHdrOrProduction() {
        assertTrue(vlcDoviRpuDecoderRequired(true, true, false, false))
        assertFalse(vlcDoviRpuDecoderRequired(false, true, false, false))
        assertFalse(vlcDoviRpuDecoderRequired(true, true, true, false))
        assertFalse(vlcDoviRpuDecoderRequired(true, false, false, false))
        assertFalse(vlcDoviRpuDecoderRequired(true, true, false, true))
    }

    @Test fun onlyExplicitSdrGpuPathIsEligible() {
        assertTrue(vlcAhbExperimentEligible(true, true, false, false, "off", "off", false))
        assertFalse(vlcAhbExperimentEligible(false, true, false, false, "off", "off", false))
        assertFalse(vlcAhbExperimentEligible(true, false, false, false, "off", "off", false))
        assertFalse(vlcAhbExperimentEligible(true, true, true, false, "off", "off", false))
        assertTrue(vlcAhbExperimentEligible(true, true, true, false, "off", "off", false, supportedHdrGpu = true))
        assertFalse(vlcAhbExperimentEligible(true, true, true, true, "off", "off", false, supportedHdrGpu = true))
        assertFalse(vlcAhbExperimentEligible(true, true, true, false, "off", "off", true, supportedHdrGpu = true))
        assertFalse(vlcAhbExperimentEligible(true, true, false, true, "off", "off", false))
        assertFalse(vlcAhbExperimentEligible(true, true, false, false, "light", "off", false))
        assertFalse(vlcAhbExperimentEligible(true, true, false, false, "off", "auto", false))
        assertFalse(vlcAhbExperimentEligible(true, true, false, false, "off", "off", true))
    }
}
