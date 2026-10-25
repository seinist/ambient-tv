package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.VideoImageCatalog
import com.ambient.tv.ui.player.HdrStandard
import com.ambient.tv.ui.player.mpvEditorChromaScalers
import com.ambient.tv.ui.player.mpvEditorDownscalers
import com.ambient.tv.ui.player.playerPanelVideoVisibility
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Follow-up to the "HDR über GPU" audit of #24/#25 (K1, K2, M1–M8 and the minor items). */
class MpvHdrGpuAuditTest {
    private val vulkan12 = (1L shl 22) or (2L shl 12)
    private val caps = VideoAccelerationCapabilities(
        checked = true,
        device = VulkanDeviceCapabilities(render = true, device = "gpu", version = vulkan12, hostQueryReset = true,
            surfaceColorSpaces = listOf(0, 1000104008)),
        mpvOutputBuilt = true,
    )
    private val display = setOf(HdrStandard.HDR10)
    private fun decide(
        output: String = "direct",
        failure: MpvHdrGpuReason? = null,
        explicitDirect: Boolean = false,
        effectsActive: Boolean = true,
        tvDevice: Boolean = true,
        source: HdrStandard? = HdrStandard.HDR10,
        reduced: Boolean = false,
    ) = mpvHdrGpuDecision(true, output, source, display, caps, reduced, failure, explicitDirect, effectsActive, tvDevice)

    @After fun clearSession() = MpvHdrGpuSession.clear()

    // K1 ---------------------------------------------------------------------------------------

    @Test fun factoryDefaultDirectDoesNotBlockTheOption() {
        assertEquals(MpvHdrGpuDecision.GPU, decide(output = "direct"))
        assertEquals(MpvHdrGpuReason.OUTPUT_DIRECT, decide(output = "direct", explicitDirect = true).reason)
        // An explicit choice other than "direct" never blocks.
        assertEquals(MpvHdrGpuDecision.GPU, decide(output = "gpu", explicitDirect = true))
    }

    @Test fun onlyAChoiceMadeForThisPlaybackIsExplicit() {
        assertFalse(mpvHdrGpuExplicitDirect(null, null, null, globalOutput = "direct"))
        assertTrue(mpvHdrGpuExplicitDirect("direct", null, null, globalOutput = "direct"))
        // Resolution profiles store their output in full: "direct" equal to the default is no choice.
        assertFalse(mpvHdrGpuExplicitDirect(null, null, "direct", globalOutput = "direct"))
        assertFalse(mpvHdrGpuExplicitDirect(null, "direct", null, globalOutput = "direct"))
        assertTrue(mpvHdrGpuExplicitDirect(null, null, "direct", globalOutput = "auto"))
        assertTrue(mpvHdrGpuExplicitDirect(null, "direct", null, globalOutput = "vulkan"))
        assertFalse(mpvHdrGpuExplicitDirect("auto", "auto", "gpu", globalOutput = "auto"))
    }

    @Test fun recordedFailureRoutesDirectWithoutAnOverride() {
        assertEquals("vulkan", mpvHdrGpuOutputMode(MpvHdrGpuDecision.GPU, "direct", null))
        val failed = decide(failure = MpvHdrGpuReason.NOT_CONFIRMED)
        assertEquals("direct", mpvHdrGpuOutputMode(failed, "auto", MpvHdrGpuReason.NOT_CONFIRMED))
        // A device reason keeps the configured output (regular routing decides).
        assertEquals("auto", mpvHdrGpuOutputMode(MpvHdrGpuDecision.direct(MpvHdrGpuReason.DOLBY_VISION), "auto", null))
        // A failure that is not the decision's reason (e.g. the option is off now) changes nothing.
        assertEquals("gpu", mpvHdrGpuOutputMode(MpvHdrGpuDecision.NOT_APPLICABLE, "gpu", MpvHdrGpuReason.OVERLOAD))
    }

    // K2 ---------------------------------------------------------------------------------------

    @Test fun failureHoldsTheFrameButKeepsThePlayIntent() {
        assertEquals(MpvHdrFailureHold(pauseNative = true, playIntent = true), mpvHdrFailureHold(playIntent = true))
        assertEquals(MpvHdrFailureHold(pauseNative = true, playIntent = false), mpvHdrFailureHold(playIntent = false))
        // Detached (background audio): nothing would show the held frame, audio plays on.
        assertEquals(MpvHdrFailureHold(pauseNative = false, playIntent = true), mpvHdrFailureHold(playIntent = true, attached = false))
    }

    @Test fun passthroughNeverTakesTheOpenGlFallback() {
        assertTrue(mpvHdrPassthroughBlocksGlFallback(hdrGpuPassthrough = true, gpuOutput = true, released = false))
        assertFalse(mpvHdrPassthroughBlocksGlFallback(hdrGpuPassthrough = true, gpuOutput = true, released = true))
        assertFalse(mpvHdrPassthroughBlocksGlFallback(hdrGpuPassthrough = false, gpuOutput = true, released = false))
        assertFalse(mpvHdrPassthroughBlocksGlFallback(hdrGpuPassthrough = true, gpuOutput = false, released = false))
    }

    // M1 ---------------------------------------------------------------------------------------

    @Test fun noEffectsKeepsHdrDirect() {
        assertEquals(MpvHdrGpuReason.NO_EFFECTS, decide(effectsActive = false).reason)
        // Sparsam and explicit direct are named first.
        assertEquals(MpvHdrGpuReason.REDUCED_EFFECTS, decide(effectsActive = false, reduced = true).reason)
        assertEquals(MpvHdrGpuReason.OUTPUT_DIRECT, decide(effectsActive = false, explicitDirect = true).reason)
    }

    @Test fun effectsActiveFollowsHdrSafeShadersAndDeband() {
        val none = MpvImageOptions(scaler = "lanczos", sharpening = "off", deband = "off", filmGrain = "off")
        assertFalse(mpvHdrGpuEffectsActive(none))
        assertTrue(mpvHdrGpuEffectsActive(none.copy(deband = "light")))
        assertTrue(mpvHdrGpuEffectsActive(none.copy(sharpening = "cas")))
        assertTrue(mpvHdrGpuEffectsActive(none.copy(sharpening = "adaptive")))
        assertTrue(mpvHdrGpuEffectsActive(none.copy(scaler = "ravu_lite_r3")))
        // SDR-only processing and grain (no grain on PQ output) do not justify the GPU route.
        assertFalse(mpvHdrGpuEffectsActive(none.copy(denoise = "light", filmGrain = "light", scaler = "artcnn16")))
        // The upscaler only counts while it would actually run.
        assertFalse(mpvHdrGpuEffectsActive(none.copy(scaler = "ravu_lite_r3"), fittedScale = 1.0))
    }

    // M2 ---------------------------------------------------------------------------------------

    @Test fun adaptiveSharpenBecomesCasOnPqOutput() {
        val adaptive = MpvImageOptions(sharpening = "adaptive")
        assertEquals(listOf("cas"), mpvImageShaderKeys(adaptive, sdr = false, hdr = true, gpuNext = true, pqOutput = true))
        // Tone-mapped HDR and SDR keep Adaptive Sharpen.
        assertEquals(listOf("adaptive"), mpvImageShaderKeys(adaptive, sdr = false, hdr = true, gpuNext = true))
        assertEquals(listOf("adaptive"), mpvImageShaderKeys(adaptive, sdr = true, gpuNext = true, pqOutput = true))
        // The overload step for the substituted CAS is "off", not Adaptive again.
        assertEquals("off", mpvSharpeningAfterOverload("adaptive", listOf("cas")))
        assertEquals("cas", mpvSharpeningAfterOverload("adaptive", listOf("adaptive")))
    }

    // M4 ---------------------------------------------------------------------------------------

    @Test fun mediaKeysDropQueryAndFragment() {
        assertEquals("https://h/v.mkv", MpvHdrGpuSession.mediaKey("https://h/v.mkv?X-Plex-Token=abc&session=1#t=3"))
        // Without a media id the URL (query dropped) is the identity.
        val first = mpvHdrGpuMediaKey(null, null, null, null, trailer = false, url = "https://h/v.mkv?session=1")!!
        val second = mpvHdrGpuMediaKey(null, null, null, null, trailer = false, url = "https://h/v.mkv?session=2")!!
        assertEquals(first, second)
        MpvHdrGpuSession.recordFailure(first, "3840x2160", "d1", MpvHdrGpuReason.OVERLOAD)
        assertEquals(MpvHdrGpuReason.OVERLOAD, MpvHdrGpuSession.failure(second, "3840x2160", "d1"))
        // Other resolution or display: not affected.
        assertNull(MpvHdrGpuSession.failure(first, "1920x1080", "d1"))
        assertNull(MpvHdrGpuSession.failure(first, "3840x2160", "d2"))
    }

    @Test fun failuresAreLevelled() {
        MpvHdrGpuSession.recordFailure("a", null, "tv", MpvHdrGpuReason.SWAPCHAIN_NO_HDR10)
        assertEquals(MpvHdrGpuReason.SWAPCHAIN_NO_HDR10, MpvHdrGpuSession.failure("other", null, "tv"))
        assertNull(MpvHdrGpuSession.failure("other", null, "avr"))
        MpvHdrGpuSession.clear()
        // One NOT_CONFIRMED stays with its media; the second on the same display blocks the display.
        MpvHdrGpuSession.recordFailure("a", null, "tv", MpvHdrGpuReason.NOT_CONFIRMED)
        assertEquals(MpvHdrGpuReason.NOT_CONFIRMED, MpvHdrGpuSession.failure("a", null, "tv"))
        assertNull(MpvHdrGpuSession.failure("b", null, "tv"))
        MpvHdrGpuSession.recordFailure("b", null, "tv", MpvHdrGpuReason.NOT_CONFIRMED)
        assertEquals(MpvHdrGpuReason.NOT_CONFIRMED, MpvHdrGpuSession.failure("c", null, "tv"))
        // Overload never escalates beyond the media.
        MpvHdrGpuSession.clear()
        repeat(3) { MpvHdrGpuSession.recordFailure("m$it", null, "tv", MpvHdrGpuReason.OVERLOAD) }
        assertNull(MpvHdrGpuSession.failure("x", null, "tv"))
    }

    @Test fun sessionIsBoundedAndClears() {
        repeat(MpvHdrGpuSession.MAX_ENTRIES * 3) {
            MpvHdrGpuSession.recordFailure("u$it", null, "tv$it", MpvHdrGpuReason.NOT_CONFIRMED)
            MpvHdrGpuSession.recordDetected("u$it", HdrStandard.HDR10, nowMs = 0L)
        }
        assertTrue(MpvHdrGpuSession.sizes().all { it <= MpvHdrGpuSession.MAX_ENTRIES })
        MpvHdrGpuSession.clear()
        assertEquals(listOf(0, 0, 0, 0, 0, 0), MpvHdrGpuSession.sizes())
    }

    @Test fun displayKeyChangesWithTheDisplayOrItsCapabilities() {
        val tv = mpvHdrDisplayKey(0, "HDMI", setOf(HdrStandard.HDR10, HdrStandard.HLG))
        assertEquals(tv, mpvHdrDisplayKey(0, "HDMI", setOf(HdrStandard.HLG, HdrStandard.HDR10)))
        assertNotEquals(tv, mpvHdrDisplayKey(0, "HDMI", setOf(HdrStandard.HLG)))
        assertNotEquals(tv, mpvHdrDisplayKey(1, "HDMI", setOf(HdrStandard.HDR10, HdrStandard.HLG)))
    }

    // M5 ---------------------------------------------------------------------------------------

    @Test fun dolbyVisionProfileWinsOverItsPqBaseLayer() {
        assertEquals(HdrStandard.DOLBY_VISION, mpvRuntimeHdrStandard("pq", 8))
        assertEquals(HdrStandard.DOLBY_VISION, mpvRuntimeHdrStandard("pq", 5))
        assertEquals(HdrStandard.HDR10, mpvRuntimeHdrStandard("pq", 0))
        assertEquals(HdrStandard.HDR10, mpvRuntimeHdrStandard("pq", null))
        assertNull(mpvRuntimeHdrStandard("bt.1886", null))
        assertEquals(MpvHdrGpuReason.DOLBY_VISION, decide(source = mpvRuntimeHdrStandard("pq", 8)).reason)
    }

    // M6 ---------------------------------------------------------------------------------------

    @Test fun vulkanDecodeDoesNotCopyBackForShadersOnly() {
        val decode = caps.copy(mpvDecodeBuilt = true, device = caps.device.copy(profiles = listOf(VulkanDecodeProfile("hevc", 10, 3840, 2160))))
        val plan = planMpvAcceleration("vulkan", "vulkan", decode)
        assertEquals("vulkan", plan.decoder)
        // LUMA shaders (copyBack requested by the shader) sample the Vulkan image directly.
        assertEquals(plan.hwdec, plan.hwdecForProcessing(copyBack = true, deinterlace = false, cpuFrames = false))
        // CPU filters and deinterlace still need system memory frames.
        assertEquals("vulkan-copy,mediacodec-copy", plan.hwdecForProcessing(copyBack = true, deinterlace = false, cpuFrames = true))
        assertEquals("vulkan-copy,mediacodec-copy", plan.hwdecForProcessing(copyBack = false, deinterlace = true, cpuFrames = false))
        // MediaCodec on OpenGL keeps copy-back for shaders.
        assertEquals("mediacodec-copy", planMpvAcceleration("gpu", "mediacodec", caps)
            .hwdecForProcessing(copyBack = true, deinterlace = false, cpuFrames = false))
    }

    // M7 ---------------------------------------------------------------------------------------

    @Test fun passthroughSkipsPeakDetection() {
        assertEquals("no", MPV_HDR_PASSTHROUGH_OPTIONS["hdr-compute-peak"])
    }

    @Test fun hdrMetadataPlausibility() {
        assertNull(mpvHdrMetadataPlausible(null, null))
        assertEquals(true, mpvHdrMetadataPlausible(1000.0, 800.0))
        assertEquals(true, mpvHdrMetadataPlausible(4000.0, null))
        assertEquals(true, mpvHdrMetadataPlausible(null, 0.0))
        assertEquals(false, mpvHdrMetadataPlausible(203.0, null))
        assertEquals(false, mpvHdrMetadataPlausible(100.0, 400.0))
        assertEquals(false, mpvHdrMetadataPlausible(10_001.0, null))
        assertEquals(false, mpvHdrMetadataPlausible(1000.0, -1.0))
        assertEquals(false, mpvHdrMetadataPlausible(Double.NaN, null))
    }

    @Test fun monitorRecordsTheMetadataVerdictWithoutFailing() {
        val pqSurface = (6 shl 16) or (7 shl 22) or (1 shl 27)
        fun facts(luma: Double?) = MpvHdrPassthroughFacts(true, true, "pq", "pq", "bt.2020", "rgb10a2", pqSurface, true,
            targetMaxLuma = luma, targetMaxCll = null)
        val implausible = MpvHdrPassthroughMonitor()
        assertNull(implausible.update(facts(100.0), 0L))
        assertTrue(implausible.confirmed)
        assertEquals(false, implausible.metadataPlausible)
        val good = MpvHdrPassthroughMonitor()
        good.update(facts(1000.0), 0L)
        assertEquals(true, good.metadataPlausible)
    }

    // M8 / UI ----------------------------------------------------------------------------------

    @Test fun panelUsesTheRunningPassthroughNotTheHdrOutputGuess() {
        // HDR shown on the display but no passthrough running: tone mapping applies, grain stays.
        val guess = playerPanelVideoVisibility("mpv", gpuFilters = true, shadersApply = false, media3ScalerApplies = false,
            hdrInput = true, hdrOutput = true, hdrGpuPassthrough = false)
        assertFalse(guess.hdrPassthrough)
        assertTrue(guess.toneMapping)
        val running = playerPanelVideoVisibility("mpv", gpuFilters = true, shadersApply = false, media3ScalerApplies = false,
            hdrInput = true, hdrOutput = false, hdrGpuPassthrough = true)
        assertTrue(running.hdrPassthrough)
        assertFalse(running.toneMapping)
    }

    @Test fun hdrEditorOffersOnlyChoicesThatLoad() {
        val chroma = mpvEditorChromaScalers(hdr = true).map { it.first }
        assertTrue(chroma.none { it in VideoImageCatalog.chromaShaders })
        assertTrue(VideoImageCatalog.chromaShaders.keys.all { key -> mpvEditorChromaScalers(hdr = false).any { it.first == key } })
        assertFalse(mpvEditorDownscalers(hdr = true).any { it.first == "ssim_down" })
        assertTrue(mpvEditorDownscalers(hdr = false).any { it.first == "ssim_down" })
    }

    // Minor items --------------------------------------------------------------------------------

    @Test fun optionIsForTvDevicesOnly() {
        assertEquals(MpvHdrGpuDecision.NOT_APPLICABLE, decide(tvDevice = false))
    }

    @Test fun liveDetectionExpiresAndReopensAtMostOncePerWindow() {
        val url = "http://tvh/stream/channel/1?profile=pass"
        assertTrue(MpvHdrGpuSession.recordDetected(url, HdrStandard.HDR10, live = true, nowMs = 0L))
        assertEquals(HdrStandard.HDR10, MpvHdrGpuSession.detectedHdr(url, nowMs = 1_000L))
        assertNull(MpvHdrGpuSession.detectedHdr(url, nowMs = MpvHdrGpuSession.LIVE_DETECTION_TTL_MS + 1))
        // SDR reading: forgotten; a new HDR reading inside the reopen window does not reopen again.
        MpvHdrGpuSession.forgetDetected(url)
        assertNull(MpvHdrGpuSession.detectedHdr(url, nowMs = 2_000L))
        assertFalse(MpvHdrGpuSession.recordDetected(url, HdrStandard.HDR10, live = true, nowMs = 3_000L))
        assertTrue(MpvHdrGpuSession.recordDetected(url, HdrStandard.HDR10, live = true,
            nowMs = MpvHdrGpuSession.LIVE_REOPEN_WINDOW_MS + 1))
        // Files keep their detection without expiry; the same reading is no change.
        assertTrue(MpvHdrGpuSession.recordDetected("file.mkv", HdrStandard.HDR10, nowMs = 0L))
        assertFalse(MpvHdrGpuSession.recordDetected("file.mkv", HdrStandard.HDR10, nowMs = 10L))
        assertEquals(HdrStandard.HDR10, MpvHdrGpuSession.detectedHdr("file.mkv", nowMs = Long.MAX_VALUE / 2))
    }

    @Test fun filmGrainAloneNeedsTheGpuPath() {
        val nothing = MpvImageOptions(scaler = "bilinear", chromaScaler = "bilinear", downscaler = "bilinear",
            deband = "off", sharpening = "off", denoise = "off", deblock = "off", filmGrain = "off",
            superResolution = false, antiring = 0, sigmoid = false, svpMode = "off")
        assertFalse(mpvNeedsGpuProcessing(nothing))
        assertTrue(mpvNeedsGpuProcessing(nothing.copy(filmGrain = "light")))
    }
}
