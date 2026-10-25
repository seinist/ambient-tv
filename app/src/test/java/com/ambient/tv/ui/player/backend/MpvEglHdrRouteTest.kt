package com.ambient.tv.ui.player.backend

import com.ambient.tv.ui.player.HdrStandard
import com.ambient.tv.ui.player.disabledPlayerVideoOutputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kodi-style GLES HDR: EGL BT2020_PQ surface when Vulkan offers no HDR10 swapchain. */
class MpvEglHdrRouteTest {
    private val vulkan12 = (1L shl 22) or (2L shl 12)
    private val vulkanHdr = VideoAccelerationCapabilities(
        checked = true,
        device = VulkanDeviceCapabilities(render = true, device = "gpu", version = vulkan12, hostQueryReset = true,
            surfaceColorSpaces = listOf(0, 1000104008)),
        mpvOutputBuilt = true,
    )
    private val noVulkan = VideoAccelerationCapabilities(checked = true)
    private val display = setOf(HdrStandard.HDR10, HdrStandard.HLG)
    private fun decide(caps: VideoAccelerationCapabilities, source: HdrStandard = HdrStandard.HDR10,
        failure: MpvHdrGpuReason? = null, reduced: Boolean = false) =
        mpvHdrGpuDecision(true, "auto", source, display, caps, reduced, failure)

    @Test fun extensionParserNeedsBothColourSpaceExtensions() {
        assertTrue(eglHdr10PqFromExtensions("EGL_KHR_image EGL_KHR_gl_colorspace EGL_EXT_gl_colorspace_bt2020_pq EGL_ANDROID_x"))
        assertFalse(eglHdr10PqFromExtensions("EGL_KHR_gl_colorspace EGL_EXT_gl_colorspace_bt2020_linear"))
        assertFalse(eglHdr10PqFromExtensions("EGL_EXT_gl_colorspace_bt2020_pq"))
        // Prefix matches are not extensions.
        assertFalse(eglHdr10PqFromExtensions("EGL_KHR_gl_colorspace EGL_EXT_gl_colorspace_bt2020_pq_x"))
        assertFalse(eglHdr10PqFromExtensions(null))
        assertFalse(eglHdr10PqFromExtensions(""))
    }

    @Test fun withoutVulkanTheEglPqSurfaceCarriesThePassthrough() {
        assertEquals(MpvHdrGpuReason.VULKAN_UNAVAILABLE, decide(noVulkan).reason)
        val gl = decide(noVulkan.copy(eglHdr10Pq = true))
        assertEquals(MpvHdrGpuDecision.GPU_OPENGL, gl)
        assertEquals("gpu", mpvHdrGpuOutputMode(gl, "direct", null))
        // A Vulkan swapchain without HDR10 also takes the EGL route.
        val noHdrSwapchain = vulkanHdr.copy(device = vulkanHdr.device.copy(surfaceColorSpaces = listOf(0)), eglHdr10Pq = true)
        assertTrue(decide(noHdrSwapchain).openGl)
        // An unfinished probe never claims EGL support.
        assertEquals(MpvHdrGpuReason.VULKAN_UNAVAILABLE, decide(noVulkan.copy(checked = false, eglHdr10Pq = true)).reason)
    }

    @Test fun vulkanHdr10StaysPreferredAndOtherGatesStillApply() {
        assertEquals(MpvHdrGpuDecision.GPU, decide(vulkanHdr.copy(eglHdr10Pq = true)))
        assertEquals("vulkan", mpvHdrGpuOutputMode(decide(vulkanHdr), "direct", null))
        val gl = noVulkan.copy(eglHdr10Pq = true)
        assertEquals(MpvHdrGpuReason.DOLBY_VISION, decide(gl, HdrStandard.DOLBY_VISION).reason)
        assertEquals(MpvHdrGpuReason.REDUCED_EFFECTS, decide(gl, reduced = true).reason)
        val failed = decide(gl, failure = MpvHdrGpuReason.OPENGL_FALLBACK)
        assertEquals(MpvHdrGpuReason.OPENGL_FALLBACK, failed.reason)
        assertEquals("direct", mpvHdrGpuOutputMode(failed, "gpu", MpvHdrGpuReason.OPENGL_FALLBACK))
        assertNull(mpvHdrGpuDeviceReason(gl, display))
    }

    @Test fun glRoutePlansCopyBackOpenGlWithoutZeroCopy() {
        val plan = planMpvAcceleration("gpu", "auto", noVulkan.copy(eglHdr10Pq = true), hdrPassthrough = true, zeroCopy = true)
        assertEquals("gpu", plan.output)
        assertEquals("mediacodec", plan.decoder)
        assertFalse(plan.zeroCopy)
        assertEquals("mediacodec-copy", plan.hwdec)
        assertEquals("software", planMpvAcceleration("gpu", "software", noVulkan, hdrPassthrough = true).decoder)
        // Vulkan passthrough unchanged.
        assertEquals("vulkan", planMpvAcceleration("vulkan", "auto", vulkanHdr, hdrPassthrough = true).output)
    }

    @Test fun glRouteOptionsRenderPqIntoTenBits() {
        assertEquals("pq", MPV_HDR_OPENGL_OPTIONS["target-trc"])
        assertEquals("bt.2020", MPV_HDR_OPENGL_OPTIONS["target-prim"])
        assertEquals("rgb10_a2", MPV_HDR_OPENGL_OPTIONS["egl-output-format"])
        // Failure of either GPU route reopens on the direct surface, never on a plain SDR OpenGL.
        assertTrue(mpvHdrPassthroughBlocksGlFallback(hdrGpuPassthrough = true, gpuOutput = true, released = false))
    }

    @Test fun openGlStaysSelectableOnTheEglRoute() {
        assertEquals(setOf("vulkan"), disabledPlayerVideoOutputs("mpv", noVulkan.copy(eglHdr10Pq = true), hdrGpu = false))
        assertEquals(setOf("gpu"), disabledPlayerVideoOutputs("mpv", vulkanHdr, hdrGpu = true))
    }
}
