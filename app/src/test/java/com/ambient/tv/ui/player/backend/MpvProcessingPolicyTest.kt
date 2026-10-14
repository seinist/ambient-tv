package com.ambient.tv.ui.player.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvProcessingPolicyTest {
    @Test fun gpuPathAlwaysCopiesHardwareFrames() {
        assertEquals("mediacodec-copy", mpvGpuDecoderMode(true, true))
        assertEquals("mediacodec-copy", mpvGpuDecoderMode(true, false))
        assertEquals("mediacodec-copy", mpvGpuDecoderMode(false, true))
        assertEquals("mediacodec-copy", mpvGpuDecoderMode(false, false))
    }

    @Test fun computeSupportIsConservativeForUnknownOpenGlContexts() {
        assertEquals(MpvComputeSupport.SUPPORTED, mpvComputeSupport("vulkan"))
        assertEquals(MpvComputeSupport.UNKNOWN, mpvComputeSupport("opengl"))
        assertEquals(MpvComputeSupport.UNKNOWN, mpvComputeSupport(null))
        assertEquals(MpvComputeSupport.UNSUPPORTED, mpvComputeSupport("mediacodec"))
    }

    @Test fun tvheadendSettingsMapToMpvVocabulary() {
        assertEquals("auto", tvheadendMpvDeinterlaceMode("auto"))
        assertEquals("off", tvheadendMpvDeinterlaceMode("invalid"))
        assertEquals("yadif2x", tvheadendMpvDeinterlaceFilter("bob"))
        assertEquals("yadif", tvheadendMpvDeinterlaceFilter("yadif"))
        assertEquals("auto", tvheadendMpvDeinterlaceFilter("blend"))
    }
}
