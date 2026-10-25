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
    /** Device EGL PQ, patched libmpv and a readable surface dataspace. */
    private val glRoute = noVulkan.copy(eglHdr10Pq = true, mpvEglHdrSurface = true, surfaceDataSpaceReadable = true)
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
        val gl = decide(glRoute)
        assertEquals(MpvHdrGpuDecision.GPU_OPENGL, gl)
        assertEquals("gpu", mpvHdrGpuOutputMode(gl, "direct", null))
        // A Vulkan swapchain without HDR10 also takes the EGL route.
        val noHdrSwapchain = vulkanHdr.copy(device = vulkanHdr.device.copy(surfaceColorSpaces = listOf(0)), eglHdr10Pq = true,
            mpvEglHdrSurface = true, surfaceDataSpaceReadable = true)
        assertTrue(decide(noHdrSwapchain).openGl)
        // An unfinished probe never claims EGL support.
        assertEquals(MpvHdrGpuReason.VULKAN_UNAVAILABLE, decide(glRoute.copy(checked = false)).reason)
    }

    @Test fun vulkanHdr10StaysPreferredAndOtherGatesStillApply() {
        assertEquals(MpvHdrGpuDecision.GPU, decide(vulkanHdr.copy(eglHdr10Pq = true)))
        assertEquals("vulkan", mpvHdrGpuOutputMode(decide(vulkanHdr), "direct", null))
        val gl = glRoute
        assertEquals(MpvHdrGpuReason.DOLBY_VISION, decide(gl, HdrStandard.DOLBY_VISION).reason)
        assertEquals(MpvHdrGpuReason.REDUCED_EFFECTS, decide(gl, reduced = true).reason)
        val failed = decide(gl, failure = MpvHdrGpuReason.OPENGL_FALLBACK)
        assertEquals(MpvHdrGpuReason.OPENGL_FALLBACK, failed.reason)
        assertEquals("direct", mpvHdrGpuOutputMode(failed, "gpu", MpvHdrGpuReason.OPENGL_FALLBACK))
        assertNull(mpvHdrGpuDeviceReason(gl, display))
    }

    @Test fun glRoutePlansCopyBackOpenGlWithoutZeroCopy() {
        val plan = planMpvAcceleration("gpu", "auto", glRoute, hdrPassthrough = true, zeroCopy = true)
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
        assertEquals(setOf("vulkan"), disabledPlayerVideoOutputs("mpv", glRoute, hdrGpu = false))
        assertEquals(setOf("gpu"), disabledPlayerVideoOutputs("mpv", vulkanHdr, hdrGpu = true))
    }

    @Test fun eglRouteNeedsThePatchedLibmpvAndAReadableDataspace() {
        // EGL alone (older libmpv without the surface patch) keeps HDR on the direct surface.
        assertEquals(MpvHdrGpuReason.VULKAN_UNAVAILABLE, decide(glRoute.copy(mpvEglHdrSurface = false)).reason)
        assertFalse(decide(glRoute.copy(mpvEglHdrSurface = false)).gpu)
        // Without a readable dataspace PQ could never be confirmed: no EGL route either.
        assertEquals(MpvHdrGpuReason.VULKAN_UNAVAILABLE, decide(glRoute.copy(surfaceDataSpaceReadable = false)).reason)
        assertTrue(glRoute.mpvEglHdrRoute)
        assertFalse(glRoute.copy(eglHdr10Pq = false).mpvEglHdrRoute)
    }

    private val pqSurface = (6 shl 16) or (7 shl 22) or (1 shl 27)
    private fun glFacts(pixelFormat: String?, dataSpace: Int, readable: Boolean = true) =
        MpvHdrPassthroughFacts(true, true, "pq", "pq", "bt.2020", pixelFormat, dataSpace, null,
            openGl = true, dataSpaceReadable = readable)

    @Test fun eglRouteIsConfirmedByTheSurfaceDataspaceNotTheFboFormat() {
        // mpv's FBO may be rgba16f/rgba8 on vo_gpu: the PQ dataspace alone confirms the surface.
        val monitor = MpvHdrPassthroughMonitor()
        assertNull(monitor.update(glFacts("rgba8", pqSurface), 0L))
        assertTrue(monitor.confirmed)
        // A 10-bit FBO without the PQ dataspace confirms nothing.
        val unmarked = MpvHdrPassthroughMonitor()
        assertNull(unmarked.update(glFacts("rgb10a2", 0), 0L))
        assertFalse(unmarked.confirmed)
        assertEquals(MpvHdrGpuReason.NOT_CONFIRMED, unmarked.update(glFacts("rgb10a2", 0), 3_000L))
        // Unreadable dataspace: an honest failure at once, never a confirmation.
        val blind = MpvHdrPassthroughMonitor()
        assertEquals(MpvHdrGpuReason.SURFACE_UNVERIFIABLE, blind.update(glFacts("rgb10a2", pqSurface, readable = false), 0L))
        assertFalse(blind.confirmed)
        // Vulkan keeps requiring a 10-bit target format.
        val vulkan = MpvHdrPassthroughMonitor()
        assertNull(vulkan.update(glFacts("rgba8", pqSurface).copy(openGl = false), 0L))
        assertFalse(vulkan.confirmed)
    }
}
