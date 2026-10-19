package com.ambient.tv.ui.player.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvShieldInterlacePolicyTest {
    private val caps = VideoAccelerationCapabilities(true,
        VulkanDeviceCapabilities(true, "test GPU", version = (1L shl 22) or (3L shl 12),
            profiles = listOf(VulkanDecodeProfile("h264", 8, 4096, 4096)), hostQueryReset = true),
        mpvOutputBuilt = true, mpvDecodeBuilt = true, vlcOutputBuilt = true)
    private val openGl = planMpvAcceleration("gpu", "auto", caps)
    private val vulkan = planMpvAcceleration("vulkan", "auto", caps)
    private val direct = planMpvAcceleration("direct", "auto", caps)

    private fun zeroCopy(shield: Boolean, plan: MpvAccelerationPlan, live: Boolean, fps: Double?, height: Int?) =
        mpvShieldZeroCopyEligible(shield, plan, live) && mpvLiveInterlacedSource(live, fps, height)

    @Test fun detectsShieldModelsOnly() {
        assertTrue(isNvidiaShield("NVIDIA", "SHIELD Android TV", "mdarcy"))
        assertTrue(isNvidiaShield("NVIDIA", "SHIELD Android TV", "foster"))
        assertTrue(isNvidiaShield("nvidia", "Some Model", "sif"))
        assertTrue(isNvidiaShield("NVIDIA", null, "darcy"))
        assertFalse(isNvidiaShield("NVIDIA", "Tegra Tablet", "tn8"))
        assertFalse(isNvidiaShield("Google", "Pixel 8", "shiba"))
        assertFalse(isNvidiaShield("Xiaomi", "SHIELD clone", "mdarcy"))
        assertFalse(isNvidiaShield(null, null, null))
    }

    @Test fun interlacedNeedsFieldRateAndInterlacedHeight() {
        assertTrue(mpvLiveInterlacedSource(true, 50.0, 1080))
        assertTrue(mpvLiveInterlacedSource(true, 50.0, 1088))
        assertTrue(mpvLiveInterlacedSource(true, 59.94, 1080))
        assertTrue(mpvLiveInterlacedSource(true, 50.0, 576))
        assertTrue(mpvLiveInterlacedSource(true, 25.0, 576))
        // 720p50 shares the field rate but is progressive.
        assertFalse(mpvLiveInterlacedSource(true, 50.0, 720))
        assertFalse(mpvLiveInterlacedSource(true, 25.0, 1080))
        assertFalse(mpvLiveInterlacedSource(true, null, 1080))
        assertFalse(mpvLiveInterlacedSource(true, 50.0, null))
        // VOD keeps its path.
        assertFalse(mpvLiveInterlacedSource(false, 50.0, 1080))
    }

    @Test fun shieldOpenGlInterlacedLiveUsesZeroCopyMediacodec() {
        assertTrue(zeroCopy(true, openGl, true, 50.0, 1080))
        assertTrue(zeroCopy(true, openGl, true, 50.0, 576))
        assertEquals("mediacodec", openGl.initialHwdec(zeroCopy = true))
        assertEquals("mediacodec", openGl.hwdecForProcessing(copyBack = true, deinterlace = true, zeroCopy = true))
    }

    @Test fun everythingElseKeepsItsDecoder() {
        assertFalse("Shield progressive", zeroCopy(true, openGl, true, 50.0, 720))
        assertFalse("Shield progressive 25p", zeroCopy(true, openGl, true, 25.0, 1080))
        assertFalse("phone interlaced", zeroCopy(false, openGl, true, 50.0, 1080))
        assertFalse("Shield VOD", zeroCopy(true, openGl, false, 50.0, 1080))
        assertFalse("Shield Vulkan", zeroCopy(true, vulkan, true, 50.0, 1080))
        assertFalse("Shield direct", zeroCopy(true, direct, true, 50.0, 1080))
        assertFalse("Shield software", zeroCopy(true, planMpvAcceleration("gpu", "software", caps), true, 50.0, 1080))
        assertEquals("mediacodec-copy", openGl.initialHwdec(zeroCopy = false))
        assertEquals("mediacodec-copy", openGl.hwdecForProcessing(copyBack = true, deinterlace = true))
        assertEquals(vulkan.hwdec, vulkan.initialHwdec(zeroCopy = true))
        assertEquals(vulkan.hwdecForProcessing(true, true), vulkan.hwdecForProcessing(true, true, zeroCopy = true))
        assertEquals("mediacodec", direct.initialHwdec(zeroCopy = true))
        assertEquals("mediacodec-copy", direct.hwdecForProcessing(copyBack = false, deinterlace = true, zeroCopy = true))
    }

    @Test fun zeroCopyDropsPlaneShadersAndCpuFilters() {
        val keys = listOf("nnedi3_nns32", "ravu_lite_r3", "artcnn16", "fsrcnnx8", "krig", "adaptive", "ssim_down", "cas")
        val kept = mpvZeroCopyShaderKeys(keys)
        listOf("nnedi3_nns32", "ravu_lite_r3", "artcnn16", "fsrcnnx8", "krig").forEach { assertFalse(it, it in kept) }
        assertTrue("adaptive" in kept)
        assertTrue("ssim_down" in kept)
        assertEquals(keys.toSet(), (kept + mpvZeroCopySkippedShaders(keys)).toSet())
        val cpu = mapOf("plex-svp" to "svp", "plex-denoise" to "hqdn3d")
        assertTrue(mpvZeroCopyCpuFilters(cpu, zeroCopy = true).isEmpty())
        assertEquals(cpu, mpvZeroCopyCpuFilters(cpu, zeroCopy = false))
    }

    @Test fun deinterlaceIsNoCopyBackReasonOnZeroCopy() {
        assertTrue(mpvCopyBackNeeded(emptyList(), cpuFilters = false, deinterlaceEnabled = true))
        assertEquals("mediacodec", openGl.hwdecForProcessing(copyBack = mpvCopyBackNeeded(emptyList(), false, true),
            deinterlace = true, zeroCopy = true))
    }

    @Test fun statusReasonKeyIsStable() {
        assertEquals("shield-interlaced-zero-copy", MPV_ZERO_COPY_WARNING)
    }

    @Test fun knownChannelsStartZeroCopyBeforeLoadfile() {
        MpvShieldInterlacedChannels.clear()
        assertFalse(MpvShieldInterlacedChannels.isKnown("http://tvh/stream/channel/1"))
        MpvShieldInterlacedChannels.remember("http://tvh/stream/channel/1")
        assertTrue(MpvShieldInterlacedChannels.isKnown("http://tvh/stream/channel/1"))
        assertFalse(MpvShieldInterlacedChannels.isKnown("http://tvh/stream/channel/2"))
        MpvShieldInterlacedChannels.clear()
    }

    @Test fun zeroCopyReportsSvpAsFormatPassthroughNotStarting() {
        assertEquals(SvpInactiveReason.FORMAT, mpvZeroCopySvpReason(true))
        assertEquals(null, mpvZeroCopySvpReason(false))
        val state = svpState(
            mode = "balanced", custom = false, unavailable = mpvZeroCopySvpReason(true), overloaded = false,
            decision = SvpDecision.Run(25.0, 50.0), inChain = false, runtimeReason = null,
        )
        assertEquals("passthrough (format)", formatSvpStatus(state))
        assertEquals(null, svpBadgeLabel(state))
    }

    private fun signals(codec: String?, fps: Double?, height: Int?, flag: Boolean? = null, reliable: Boolean = false) =
        MpvInterlaceSignals(codec, flag, reliable, fps, height)

    @Test fun hevc1080p50IsNeverInterlaced() {
        // M1: same cadence and lines as 1080i, but HEVC broadcast is progressive.
        assertFalse(mpvBroadcastInterlacedSource(true, signals("H.265 / HEVC (High Efficiency Video Coding) (hevc)", 50.0, 1080)))
        assertFalse(mpvBroadcastInterlacedSource(true, signals("hevc", 50.0, 1088, flag = null)))
        assertFalse(mpvBroadcastInterlacedSource(true, signals("av1", 50.0, 1080)))
        assertTrue(mpvBroadcastInterlacedSource(true, signals("h264", 50.0, 1080)))
        assertTrue(mpvBroadcastInterlacedSource(true, signals("MPEG-2 video (mpeg2video)", 50.0, 1080)))
    }

    @Test fun aReliableDecoderFlagDecides() {
        assertFalse("software decoder says progressive", mpvBroadcastInterlacedSource(true, signals("h264", 50.0, 1080, flag = false, reliable = true)))
        assertTrue("flag set on 1080p25-looking stream", mpvBroadcastInterlacedSource(true, signals("h264", 25.0, 1080, flag = true)))
        assertTrue("MediaCodec has no flag, cadence decides", mpvBroadcastInterlacedSource(true, signals("h264", 50.0, 1080, flag = false, reliable = false)))
    }

    @Test fun sdReportedAtFrameRateIsInterlaced() {
        // M3: MPEG-2 576i arrives as 25 fps, 480i as 29.97.
        assertTrue(mpvBroadcastInterlacedSource(true, signals("mpeg2video", 25.0, 576)))
        assertTrue(mpvBroadcastInterlacedSource(true, signals("mpeg2video", 29.97, 480)))
        assertFalse(mpvBroadcastInterlacedSource(true, signals("h264", 25.0, 1080)))
        assertFalse(mpvBroadcastInterlacedSource(true, signals("h264", 50.0, 720)))
        assertFalse(mpvBroadcastInterlacedSource(true, signals("mpeg2video", 12.5, 576)))
    }

    @Test fun recordingsAreBroadcastToo() {
        // A TVHeadend recording is no live stream but takes the same path on the SHIELD.
        assertTrue(mpvShieldZeroCopyEligible(true, openGl, broadcast = true) &&
            mpvBroadcastInterlacedSource(true, signals("mpeg2video", 25.0, 576)))
        assertFalse(mpvBroadcastInterlacedSource(false, signals("mpeg2video", 25.0, 576)))
    }
}
