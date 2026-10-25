package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.ui.performance.PerformanceMode
import com.ambient.tv.ui.performance.PerformanceProfile
import com.ambient.tv.ui.player.gpuRenderAtPanelResolution
import com.ambient.tv.ui.player.gpuRenderResolutionLabel
import org.junit.Assert.*
import org.junit.Test

class GpuFramedropWashoutPolicyTest {
    private val vulkan12 = (1L shl 22) or (2L shl 12)
    private val caps = VideoAccelerationCapabilities(
        checked = true,
        device = VulkanDeviceCapabilities(render = true, device = "gpu", version = vulkan12, hostQueryReset = true,
            surfaceColorSpaces = listOf(0, 1000104008)),
        mpvOutputBuilt = true,
    )

    // P3
    @Test fun renderResolutionAutoUsesPanelOnlyForChosenWorkOnCapableDevices() {
        val auto = AppPreferences.GPU_RENDER_AUTO
        assertTrue(gpuRenderAtPanelResolution(auto, gpuOutput = true, lowEndDevice = false, userImageProcessing = true))
        assertFalse(gpuRenderAtPanelResolution(auto, gpuOutput = true, lowEndDevice = false, userImageProcessing = false))
        assertFalse(gpuRenderAtPanelResolution(auto, gpuOutput = true, lowEndDevice = true, userImageProcessing = true))
        assertTrue(gpuRenderAtPanelResolution(AppPreferences.GPU_RENDER_PANEL, true, lowEndDevice = true, userImageProcessing = false))
        assertFalse(gpuRenderAtPanelResolution(AppPreferences.GPU_RENDER_UI, true, lowEndDevice = false, userImageProcessing = true))
        // A direct MediaCodec surface never gets a fixed buffer size.
        for (key in AppPreferences.GPU_RENDER_RESOLUTIONS)
            assertFalse(gpuRenderAtPanelResolution(key, gpuOutput = false, lowEndDevice = false, userImageProcessing = true))
        assertEquals("Panel 3840×2160", gpuRenderResolutionLabel(true, true, 3840, 2160))
        assertEquals("UI 1920×1080", gpuRenderResolutionLabel(true, false, 1920, 1080))
        assertEquals("Direct", gpuRenderResolutionLabel(false, false, null, null))
    }

    // P2
    @Test fun openGlZeroCopyListsCopyBackAsMpvFallback() {
        val plan = planMpvAcceleration("gpu", "mediacodec", caps, gpuProcessing = true, zeroCopy = true)
        assertTrue(plan.zeroCopy)
        assertEquals(MPV_ZERO_COPY_HWDEC, plan.hwdec)
        assertEquals("mediacodec,mediacodec-copy", MPV_ZERO_COPY_HWDEC)
        // CPU filters still need system-memory frames.
        assertEquals("mediacodec-copy", plan.hwdecForProcessing(copyBack = true, deinterlace = false))
        assertEquals(MPV_ZERO_COPY_HWDEC, plan.hwdecForProcessing(copyBack = false, deinterlace = false))
        // Recorded failure: copy-back only.
        val failed = planMpvAcceleration("gpu", "mediacodec", caps, gpuProcessing = true, zeroCopy = false)
        assertFalse(failed.zeroCopy)
        assertEquals("mediacodec-copy", failed.hwdec)
    }

    @Test fun vulkanAndDirectNeverUseTheOpenGlZeroCopyList() {
        val vulkan = planMpvAcceleration("vulkan", "mediacodec", caps, gpuProcessing = true, zeroCopy = true)
        assertFalse(vulkan.zeroCopy)
        assertEquals("mediacodec-copy", vulkan.hwdec)
        val direct = planMpvAcceleration("direct", "mediacodec", caps, zeroCopy = true)
        assertFalse(direct.zeroCopy)
        assertEquals("mediacodec", direct.hwdec)
    }

    @Test fun zeroCopyInteropFailureIsOnlyCopyBackOnAZeroCopyPlan() {
        val plan = MpvAccelerationPlan("gpu", "mediacodec", zeroCopy = true)
        assertTrue(mpvZeroCopyInteropFailed(plan, "mediacodec-copy"))
        assertFalse(mpvZeroCopyInteropFailed(plan, "mediacodec"))
        assertFalse(mpvZeroCopyInteropFailed(plan.copy(zeroCopy = false), "mediacodec-copy"))
        assertEquals("MediaCodec (zero-copy)", mpvDecoderPathLabel("mediacodec"))
        assertEquals("MediaCodec (copy-back)", mpvDecoderPathLabel("mediacodec-copy"))
        assertEquals("Software", mpvDecoderPathLabel("no"))
    }

    @Test fun untouchedDefaultsAreNoUserImageProcessing() {
        assertFalse(mpvUserImageProcessing(MpvImageOptions(), reducedEffects = false, allowSvp = false))
        assertFalse(mpvUserImageProcessing(MpvImageOptions(), reducedEffects = true, allowSvp = false))
        assertTrue(mpvUserImageProcessing(MpvImageOptions(brightness = 10), reducedEffects = false, allowSvp = false))
    }

    // P5
    @Test fun svpInAutoNeedsAnExplicitChoice() {
        assertFalse(svpModeAllowsInterpolation(PerformanceMode.AUTO, profileAllows = true, explicitChoice = false))
        assertTrue(svpModeAllowsInterpolation(PerformanceMode.AUTO, profileAllows = true, explicitChoice = true))
        assertTrue(svpModeAllowsInterpolation(PerformanceMode.MAXIMUM, profileAllows = true, explicitChoice = false))
        assertFalse(svpModeAllowsInterpolation(PerformanceMode.MAXIMUM, profileAllows = false, explicitChoice = true))
    }

    // B2
    @Test fun sdrCurveAutoFollowsTheDisplayAndExplicitChoicesWin() {
        assertEquals("gamma2.2", mpvSdrCurveTransfer(AppPreferences.MPV_SDR_CURVE_AUTO, displayInHdrMode = true))
        assertEquals("bt.1886", mpvSdrCurveTransfer(AppPreferences.MPV_SDR_CURVE_AUTO, displayInHdrMode = false))
        assertEquals("gamma2.2", mpvSdrCurveTransfer(AppPreferences.MPV_SDR_CURVE_GAMMA22, false))
        assertEquals("bt.1886", mpvSdrCurveTransfer(AppPreferences.MPV_SDR_CURVE_BT1886, true))
        assertEquals("bt.1886", mpvSdrCurveTransfer("garbage", false))
    }

    @Test fun surfaceTagFollowsTheTargetCurve() {
        assertEquals(MPV_BT709_GAMMA22_FULL,
            mpvSdrSurfaceDataSpace("gpu", "bt.1886", "bt.1886", "bt.709", "gamma2.2", "bt.709", false))
        assertEquals(MPV_BT709_FULL,
            mpvSdrSurfaceDataSpace("gpu", "bt.1886", "bt.1886", "bt.709", "bt.1886", "bt.709", false))
        // STANDARD_BT709 | TRANSFER_GAMMA2_2 | RANGE_FULL
        assertEquals((1 shl 16) or (4 shl 22) or (1 shl 27), MPV_BT709_GAMMA22_FULL)
        assertNull(mpvSdrSurfaceDataSpace("gpu", "bt.1886", "bt.1886", "bt.709", "gamma2.4", "bt.709", false))
        assertTrue(MPV_BT709_FULL in MPV_SDR_SURFACE_DATASPACES && MPV_BT709_GAMMA22_FULL in MPV_SDR_SURFACE_DATASPACES)
    }

    @Test fun hdrToneMappedToSdrIsTaggedWithTheSameCurve() {
        assertNull(mpvSdrSurfaceDataSpace("gpu-next", "pq", "pq", "bt.2020", "gamma2.2", "bt.709", false))
        assertEquals(MPV_BT709_GAMMA22_FULL,
            mpvSdrSurfaceDataSpace("gpu-next", "pq", "pq", "bt.2020", "gamma2.2", "bt.709", false, toneMappedToSdr = true))
        // Passthrough (PQ target) and conversions are never relabelled.
        assertNull(mpvSdrSurfaceDataSpace("gpu-next", "pq", "pq", "bt.2020", "pq", "bt.2020", false, toneMappedToSdr = true))
        assertNull(mpvSdrSurfaceDataSpace("gpu-next", "pq", "pq", "bt.2020", "gamma2.2", "bt.709", true, toneMappedToSdr = true))
    }

    // B1 / P4
    @Test fun sdrToHdrStopsWhenTheDisplayStaysSdr() {
        val watch = SdrToHdrDisplayWatch(timeoutMs = 4_000L)
        assertFalse(watch.update(false, playing = true, nowMs = 0L))
        assertFalse(watch.update(false, playing = true, nowMs = 3_999L))
        assertTrue(watch.update(false, playing = true, nowMs = 4_000L))
        assertEquals(false, watch.switched)
    }

    @Test fun sdrToHdrKeepsRunningWhenSwitchedOrUnknown() {
        val unknown = SdrToHdrDisplayWatch(4_000L)
        assertFalse(unknown.update(null, true, 0L))
        assertFalse(unknown.update(null, true, 60_000L))
        assertNull(unknown.switched)
        val switched = SdrToHdrDisplayWatch(4_000L)
        assertFalse(switched.update(true, true, 0L))
        assertFalse(switched.update(false, true, 10_000L))
        assertFalse(switched.update(false, true, 20_000L))
        val paused = SdrToHdrDisplayWatch(4_000L)
        assertFalse(paused.update(false, true, 0L))
        assertFalse(paused.update(false, false, 10_000L))
        assertFalse(paused.update(false, true, 11_000L))
        assertTrue(paused.update(false, true, 15_000L))
    }

    @Test fun sdrToHdrIsGatedOnLowEndDevicesExceptMaximum() {
        assertTrue(PerformanceProfile.resolve(PerformanceMode.BALANCED, lowEndDevice = true).gateSdrToHdr)
        assertTrue(PerformanceProfile.resolve(PerformanceMode.AUTO, lowEndDevice = true).gateSdrToHdr)
        assertFalse(PerformanceProfile.resolve(PerformanceMode.MAXIMUM, lowEndDevice = true).gateSdrToHdr)
        assertFalse(PerformanceProfile.resolve(PerformanceMode.BALANCED, lowEndDevice = false).gateSdrToHdr)
        assertTrue(PerformanceProfile.resolve(PerformanceMode.LOW, lowEndDevice = false).gateSdrToHdr)
    }
}
