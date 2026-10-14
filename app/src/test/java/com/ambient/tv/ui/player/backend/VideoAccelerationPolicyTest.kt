package com.ambient.tv.ui.player.backend

import org.junit.Assert.*
import org.junit.Test

class VideoAccelerationPolicyTest {
    @Test fun autoRecognizesAllImageProcessingNotOnlyTheMainScaler() {
        val neutral = com.ambient.tv.data.local.MpvImageOptions(scaler = "bilinear", chromaScaler = "bilinear",
            downscaler = "bilinear", deband = "off", sigmoid = false)
        assertFalse(mpvNeedsGpuProcessing(neutral))
        listOf(neutral.copy(chromaScaler = "krig"), neutral.copy(downscaler = "ssim_down"),
            neutral.copy(sharpening = "cas"), neutral.copy(denoise = "light"),
            neutral.copy(brightness = 1), neutral.copy(toneMapping = "hable"),
            neutral.copy(svpMode = "balanced")).forEach { assertTrue(mpvNeedsGpuProcessing(it)) }
    }
    private val full = VideoAccelerationCapabilities(true,
        VulkanDeviceCapabilities(true, "test GPU", version = (1L shl 22) or (3L shl 12), profiles = listOf(VulkanDecodeProfile("h264", 8, 4096, 4096)), hostQueryReset = true),
        mpvOutputBuilt = true, mpvDecodeBuilt = true, vlcOutputBuilt = true)

    @Test fun autoKeepsDirectWithoutGpuEffects() {
        val p = planMpvAcceleration("auto", "auto", full)
        assertEquals("direct", p.output)
        assertEquals("mediacodec", p.hwdec)
    }
    @Test fun autoCanUseBothVulkanPaths() {
        val p = planMpvAcceleration("auto", "auto", full, gpuProcessing = true)
        assertEquals("gpu-next", p.vo)
        assertEquals("vulkan,mediacodec-copy", p.hwdec)
    }
    @Test fun nativeHdrKeepsDirectSurfaceEvenWithStoredFiltersOrExplicitGpuOutput() {
        val automatic = planMpvAcceleration("auto", "auto", full, gpuProcessing = true, nativeHdrPreferred = true)
        assertEquals("direct", automatic.output)
        assertEquals("mediacodec", automatic.hwdec)
        assertEquals("hdr_native_surface", automatic.fallback)
        val explicit = planMpvAcceleration("vulkan", "vulkan", full, nativeHdrPreferred = true)
        assertEquals("direct", explicit.output)
        assertEquals("mediacodec", explicit.decoder)
        assertFalse(useVlcVulkan("vulkan", full, gpuProcessing = true, nativeHdrPreferred = true))
    }
    @Test fun hardwareAloneNeverEnablesMissingBackendModules() {
        val caps = full.copy(mpvOutputBuilt = false, vlcOutputBuilt = false)
        assertFalse(caps.mpvVulkanDecode)
        assertFalse(useVlcVulkan("vulkan", caps))
        assertEquals("direct", planMpvAcceleration("vulkan", "vulkan", caps).output)
    }
    @Test fun renderOnlyDeviceUsesMediaCodecCopybackForVulkan() {
        val caps = full.copy(device = full.device.copy(profiles = emptyList()))
        val p = planMpvAcceleration("vulkan", "auto", caps)
        assertTrue(p.vulkan)
        assertEquals("mediacodec-copy", p.hwdec)
        assertFalse(caps.mpvVulkanDecode)
    }
    @Test fun softwareCannotUseOpaqueMediaCodecSurface() {
        val p = planMpvAcceleration("direct", "software", full)
        assertEquals("gpu", p.vo)
        assertEquals("no", p.hwdec)
    }
    @Test fun explicitOpenGlIsNotOverriddenByVulkanDecoder() {
        val p = planMpvAcceleration("gpu", "vulkan", full)
        assertEquals("gpu", p.output)
        assertEquals("mediacodec", p.decoder)
        assertNotNull(p.fallback)
    }
    @Test fun runtimeFallbackIsIndependentOfSavedChoices() {
        val p = planMpvAcceleration("vulkan", "vulkan", full, forceDefault = true)
        assertEquals("gpu", p.output)
        assertEquals("mediacodec", p.decoder)
    }
    @Test fun runtimeFallbackRetainsSoftwareDecodingAndUsesAnOutputForSoftwareFrames() {
        val p = fallbackMpvAcceleration(MpvAccelerationPlan("vulkan", "software"), "surface")
        assertEquals("gpu", p.vo)
        assertEquals("no", p.hwdec)
        assertEquals("no", p.hwdecForProcessing(true, true))
    }
    @Test fun vlcAutoRequiresGpuProcessing() {
        assertFalse(useVlcVulkan("auto", full))
        assertTrue(useVlcVulkan("auto", full, gpuProcessing = true))
        assertFalse(useVlcVulkan("vulkan", full.copy(device = VulkanDeviceCapabilities())))
    }
    @Test fun vulkan11MaySupportVlcButNotNewMpvLibplacebo() {
        val caps = full.copy(device = full.device.copy(version = (1L shl 22) or (1L shl 12)))
        assertTrue(caps.vlcVulkanOutput)
        assertFalse(caps.mpvVulkanOutput)
    }
    @Test fun mpvRequiresHostQueryResetButVlcDoesNot() {
        val caps = full.copy(device = full.device.copy(hostQueryReset = false))
        assertTrue(caps.vlcVulkanOutput)
        assertFalse(caps.mpvVulkanOutput)
    }
    @Test fun imageFiltersDoNotOverrideExplicitDecoder() {
        assertEquals("vulkan-copy,mediacodec-copy", planMpvAcceleration("vulkan", "vulkan", full).hwdecForProcessing(true, false))
        assertEquals("vulkan-copy,mediacodec-copy", planMpvAcceleration("vulkan", "vulkan", full).hwdecForProcessing(false, true))
        assertEquals("no", planMpvAcceleration("gpu", "software", full).hwdecForProcessing(true, true))
        assertEquals("mediacodec-copy", planMpvAcceleration("vulkan", "mediacodec", full).hwdecForProcessing(false, false))
    }

    @Test fun openGlHardwareDecodeUsesCopyBackSoSoftwareToHardwareKeepsAPicture() {
        // vo=gpu cannot reliably sample opaque MediaCodec surfaces; after a decoder
        // switch from software (hwdec=no) to MediaCodec the plane stayed black.
        assertEquals("mediacodec-copy", planMpvAcceleration("gpu", "mediacodec", full).hwdec)
        assertEquals("mediacodec-copy", planMpvAcceleration("gpu", "auto", full).hwdec)
        assertEquals("mediacodec", planMpvAcceleration("direct", "mediacodec", full).hwdec)
        assertEquals("mediacodec", planMpvAcceleration("auto", "auto", full).hwdec)
    }

    @Test fun autoPrefersHardwareOverSoftware() {
        assertEquals("mediacodec", planMpvAcceleration("auto", "auto", full).decoder)
        assertEquals("mediacodec", planMpvAcceleration("direct", "auto", full).decoder)
        assertEquals("mediacodec", planMpvAcceleration("gpu", "auto", full).decoder)
        assertEquals("software", planMpvAcceleration("gpu", "software", full).decoder)
    }

    @Test fun softwareIsOnlyOfferedWithoutHardwareDecoder() {
        assertFalse("software" in mpvDecoderChoices(hardwareDecoder = true))
        assertTrue("software" in mpvDecoderChoices(hardwareDecoder = false))
        assertEquals("auto", effectiveMpvDecoder("software", hardwareDecoder = true))
        assertEquals("software", effectiveMpvDecoder("software", hardwareDecoder = false))
        assertEquals("mediacodec", effectiveMpvDecoder("mediacodec", hardwareDecoder = true))
    }
}
