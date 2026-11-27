package com.ambient.tv.ui.player.backend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcAhbExperimentPolicyTest {
    @Test fun dolbyVisionRpuFallbackNeverOverridesNativeHdrOrProduction() {
        assertTrue(vlcDoviRpuDecoderRequired(true, true, false, false))
        assertFalse(vlcDoviRpuDecoderRequired(true, true, false, false, hardwareRoute = true))
        // Retry disables the hardware route; the RPU-aware SW path is selected again.
        assertTrue(vlcDoviRpuDecoderRequired(true, true, false, false, hardwareRoute = false))
        assertFalse(vlcDoviRpuDecoderRequired(false, true, false, false))
        assertFalse(vlcDoviRpuDecoderRequired(true, true, true, false))
        assertFalse(vlcDoviRpuDecoderRequired(true, false, false, false))
        assertFalse(vlcDoviRpuDecoderRequired(true, true, false, true))
    }

    @Test fun hdrPlusTargetRequiresOptInAndCharacterizedDisplay() {
        val pq = vlcHdrPlusTarget(true, true, true, 34, true, true, 0.005f, 600f)
        org.junit.Assert.assertEquals(VlcHdrPlusTarget(1, 0.005f, 600f), pq)
        org.junit.Assert.assertEquals(VlcHdrPlusTarget(2, 0.005f, 600f),
            vlcHdrPlusTarget(true, true, true, 34, false, true, 0.005f, 600f))
        org.junit.Assert.assertNull(vlcHdrPlusTarget(false, true, true, 34, true, true, 0f, 600f))
        org.junit.Assert.assertNull(vlcHdrPlusTarget(true, false, true, 34, true, true, 0f, 600f))
        org.junit.Assert.assertNull(vlcHdrPlusTarget(true, true, false, 34, true, true, 0f, 600f))
        org.junit.Assert.assertNull(vlcHdrPlusTarget(true, true, true, 33, true, true, 0f, 600f))
        org.junit.Assert.assertNull(vlcHdrPlusTarget(true, true, true, 34, false, false, 0f, 600f))
        org.junit.Assert.assertNull(vlcHdrPlusTarget(true, true, true, 34, true, true, -1f, 600f))
        org.junit.Assert.assertNull(vlcHdrPlusTarget(true, true, true, 34, true, true, 0f, Float.NaN))
        org.junit.Assert.assertNull(vlcHdrPlusTarget(true, true, true, 34, false, true, 500f, 600f))
    }

    @Test fun hdrTargetRevalidationHandlesDisplayReplacementAndMissingCaps() {
        val pq = VlcHdrPlusTarget(1, 0.005f, 600f)
        assertFalse(vlcHdrPlusTargetChanged(null, 2, 0, null))
        assertFalse(vlcHdrPlusTargetChanged(pq, null, 0, null))
        assertFalse(vlcHdrPlusTargetChanged(pq, 0, 0, pq))
        assertTrue(vlcHdrPlusTargetChanged(pq, 2, 0, pq))
        assertTrue(vlcHdrPlusTargetChanged(pq, 0, 0, null))
        assertTrue(vlcHdrPlusTargetChanged(pq, 0, 0, pq.copy(maxNits = 1000f)))
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
