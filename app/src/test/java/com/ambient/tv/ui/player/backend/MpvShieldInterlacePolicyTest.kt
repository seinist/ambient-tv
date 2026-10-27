package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.MpvImageOptions
import org.junit.Assert.*
import org.junit.Test

class MpvShieldInterlacePolicyTest {
    private val openGl = MpvAccelerationPlan("gpu", "mediacodec")
    private fun signals(codec: String? = "h264", fps: Double? = 25.0, height: Int? = 1080,
        flag: Boolean? = null, reliable: Boolean = false) = MpvInterlaceSignals(codec, flag, reliable, fps, height)

    @Test fun detectsShieldModelsOnly() {
        assertTrue(isNvidiaShield("NVIDIA", "SHIELD Android TV", "mdarcy"))
        assertTrue(isNvidiaShield("NVIDIA", "SHIELD Android TV", "foster"))
        assertTrue(isNvidiaShield("nvidia", "Some Model", "sif"))
        assertTrue(isNvidiaShield("NVIDIA", null, "darcy"))
        assertFalse(isNvidiaShield("NVIDIA", "Tegra Tablet", "tn8"))
        assertFalse(isNvidiaShield("Google", "Pixel 8", "shiba"))
        assertFalse(isNvidiaShield(null, null, null))
    }

    @Test fun coversFrameRateAndFieldRateBroadcastsWithoutTrustingMediaCodecFlags() {
        for (codec in listOf("h264", "H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10", "mpeg2video", "MPEG-2 video")) {
            for (height in listOf(480, 576, 1080, 1088)) {
                for (fps in listOf(25.0, 29.97, 50.0, 59.94)) {
                    assertTrue("$codec $height $fps", mpvShieldSoftwareRequired(signals(codec, fps, height)))
                    assertTrue(mpvShieldSoftwareRequired(signals(codec, fps, height, flag = false)))
                }
            }
        }
    }

    @Test fun progressiveOnlyCodecs720pAndUhdKeepTheirDecoder() {
        for (codec in listOf("hevc", "H.265 / HEVC", "vp9", "av1")) {
            assertFalse(mpvShieldSoftwareRequired(signals(codec, 50.0, 1080, flag = true)))
        }
        assertFalse(mpvShieldSoftwareRequired(signals(height = 720, fps = 50.0)))
        assertFalse(mpvShieldSoftwareRequired(signals(height = 2160, fps = 50.0)))
        assertFalse(mpvShieldSoftwareRequired(signals(flag = false, reliable = true)))
        // 1080p25 and 1080i25 are ambiguous without software frame flags. The workaround
        // deliberately covers both; Auto deinterlaces only frames actually marked interlaced.
        assertTrue(mpvShieldSoftwareRequired(signals(flag = false, reliable = false)))
    }

    @Test fun waitsForIncompleteMetadataAndRejectsUnrelatedFormats() {
        assertFalse(mpvShieldSoftwareRequired(signals(codec = null)))
        assertFalse(mpvShieldSoftwareRequired(signals(fps = null)))
        assertFalse(mpvShieldSoftwareRequired(signals(height = null)))
        assertFalse(mpvShieldSoftwareRequired(signals(fps = Double.NaN)))
        assertFalse(mpvShieldSoftwareRequired(signals(fps = 12.5)))
        assertFalse(mpvShieldSoftwareRequired(signals(codec = "mjpeg")))
        assertTrue(mpvShieldSoftwareRequired(signals()))
    }

    @Test fun onlyShieldGpuBroadcastTakesTheWorkaround() {
        assertTrue(mpvShieldSoftwareEligible(true, openGl, true))
        assertFalse(mpvShieldSoftwareEligible(false, openGl, true))
        assertFalse(mpvShieldSoftwareEligible(true, openGl, false))
        assertFalse(mpvShieldSoftwareEligible(true, MpvAccelerationPlan("direct", "mediacodec"), true))
        // gpu-next/Vulkan decodes through the same broken MediaCodec copy path (blocky, unwoven fields).
        assertTrue(mpvShieldSoftwareEligible(true, MpvAccelerationPlan("vulkan", "mediacodec"), true))
        assertFalse(mpvShieldSoftwareEligible(false, MpvAccelerationPlan("vulkan", "mediacodec"), true))
        assertFalse(mpvShieldSoftwareEligible(true, MpvAccelerationPlan("vulkan", "vulkan"), true))
        assertFalse(mpvShieldSoftwareEligible(true, MpvAccelerationPlan("vulkan", "software"), true))
        assertFalse(mpvShieldSoftwareEligible(true, MpvAccelerationPlan("gpu", "software"), true))
        assertFalse(mpvShieldSoftwareEligible(true, MpvAccelerationPlan("gpu", "vulkan"), true))
    }

    @Test fun softwarePlanKeepsCpuFramesWhenFiltersAndDeinterlacingChange() {
        val software = openGl.copy(decoder = "software")
        assertEquals("gpu", software.vo)
        assertEquals("no", software.hwdec)
        for (copy in listOf(false, true)) for (deint in listOf(false, true)) {
            assertEquals("no", software.hwdecForProcessing(copy, deint))
        }
        val options = MpvImageOptions(scaler = "ravu_lite_r3", sharpening = "cas", denoise = "light")
        val shaders = mpvImageShaderKeys(options, sdr = true, fittedScale = 2.0)
        assertTrue("ravu_lite_r3" in shaders)
        assertTrue("cas" in shaders)
        assertTrue(mpvCpuImageFilters(options, sdr = true).isNotEmpty())
    }

    @Test fun shieldAutoUsesFrameRateButExplicitDoubleRateStillWorks() {
        val single = mpvSingleRateDeinterlace(true, 25.0, "auto", shieldSoftware = true)
        assertTrue(single)
        assertEquals("bwdif=mode=send_frame:parity=auto:deint=interlaced", mpvDeinterlaceGraph("auto", true, singleRate = single))
        assertEquals(25.0, mpvLiveDisplayFps(25.0, true, "auto", "auto", shieldSoftware = true)!!, 0.0)
        assertFalse(mpvSingleRateDeinterlace(true, 25.0, "bwdif2x", shieldSoftware = true))
        assertEquals(50.0, mpvLiveDisplayFps(25.0, true, "on", "bwdif2x", shieldSoftware = true)!!, 0.0)
        assertEquals(50.0, mpvLiveDisplayFps(50.0, true, "on", "bwdif2x", shieldSoftware = true)!!, 0.0)
        assertEquals(50.0, mpvLiveDisplayFps(25.0, true, "auto", "auto")!!, 0.0)
        assertEquals(25.0, mpvLiveDisplayFps(25.0, false, "auto", "auto")!!, 0.0)
    }
}
