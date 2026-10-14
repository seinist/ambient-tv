package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class SdrHdrShaderTest {
    private fun data(standard: Int = 1, transfer: Int = 3, range: Int = 2) =
        (standard shl 16) or (transfer shl 22) or (range shl 27)

    @Test fun onlyExplicit709SdrIsAccepted() {
        for (range in 1..2) for (transfer in 2..6) {
            assertNotNull(sdrHdrTransfer(data(transfer = transfer, range = range)))
        }
        assertEquals(1, sdrHdrTransfer(data(transfer = 2)))
        assertEquals(2, sdrHdrTransfer(data(transfer = 3)))
    }

    @Test fun unknownPqHlgLinear601AndWideColorNeverConverted() {
        assertNull(sdrHdrTransfer(0))
        assertNull(sdrHdrTransfer(-1))
        for (transfer in listOf(0, 1, 7, 8, 9, 31)) assertNull(sdrHdrTransfer(data(transfer = transfer)))
        for (standard in 0..12) if (standard != 1) assertNull(sdrHdrTransfer(data(standard = standard)))
        for (range in listOf(0, 3, 4, 7)) assertNull(sdrHdrTransfer(data(range = range)))
    }

    @Test fun conservativeGpuBudgetRejectsUnknownRates4kAndHighFrameRates() {
        assertTrue(sdrHdrSizeAllowed(1920, 1080, 60f))
        assertTrue(sdrHdrSizeAllowed(1080, 1920, 23.976f))
        assertFalse(sdrHdrSizeAllowed(3840, 2160, 24f))
        assertFalse(sdrHdrSizeAllowed(1920, 1920, 30f))
        assertFalse(sdrHdrSizeAllowed(0, 1080, 30f))
        for (fps in listOf(0f, -1f, 60.1f, 120f, Float.NaN, Float.POSITIVE_INFINITY))
            assertFalse(sdrHdrSizeAllowed(1280, 720, fps))
    }

    @Test fun shadersUseExternalRgbLinearLightPrimariesAndAbsolutePq() {
        assertTrue(SdrHdrShader.fragment.contains("samplerExternalOES"))
        assertTrue(SdrHdrShader.fragment.contains("linearize(s.r)"))
        assertTrue(SdrHdrShader.fragment.contains("0.627404"))
        assertTrue(SdrHdrShader.fragment.contains("10000.0"))
        assertTrue(SdrHdrShader.vertex.contains("textureMatrix *"))
        assertFalse(SdrHdrShader.fragment.contains("16.0 / 255.0")) // external sampling already expands video range
    }

    @Test
    fun uhdBudgetIsOptInAndKeepsTheFrameRateLimit() {
        // Default budget is unchanged for video and screensavers.
        assertFalse(sdrHdrSizeAllowed(3840, 2160, 60f))
        assertTrue(sdrHdrSizeAllowed(3840, 2160, 60f, SdrHdrSizeBudget.UHD))
        assertTrue(sdrHdrSizeAllowed(2560, 1440, 30f, SdrHdrSizeBudget.UHD))
        assertTrue(sdrHdrSizeAllowed(2160, 3840, 60f, SdrHdrSizeBudget.UHD))
        assertFalse(sdrHdrSizeAllowed(3840, 2160, 120f, SdrHdrSizeBudget.UHD))
        assertFalse(sdrHdrSizeAllowed(4096, 2160, 60f, SdrHdrSizeBudget.UHD))
        assertFalse(sdrHdrSizeAllowed(0, 2160, 60f, SdrHdrSizeBudget.UHD))
    }

    @Test
    fun `only a clock based buffer timestamp sets the present time`() {
        val now = 5_000_000_000_000L
        assertEquals(now + 40_000_000L, sdrHdrPresentationTimeNs(now + 40_000_000L, now))
        assertEquals(now - 20_000_000L, sdrHdrPresentationTimeNs(now - 20_000_000L, now))
        // Media time (seconds into the film) or a stale/far-future stamp shows the frame at once.
        assertEquals(null, sdrHdrPresentationTimeNs(3_600_000_000_000L, now))
        assertEquals(null, sdrHdrPresentationTimeNs(now + 2_000_000_000L, now))
    }

    @Test
    fun `BT 709 video is linearised with the BT 1886 display gamma`() {
        assertTrue("if (transfer == 2) return pow(v, 2.4);" in SdrHdrShader.fragment)
    }

    @Test
    fun `static HDR metadata follows the requested peak and the driver extensions`() {
        val both = setOf("EGL_EXT_surface_SMPTE2086_metadata", "EGL_EXT_surface_CTA861_3_metadata")
        val metadata = sdrHdrStaticMetadata(400f, both).toMap()
        assertEquals(400 * 50_000, metadata[0x3349])
        assertEquals(400 * 50_000, metadata[0x3360])
        assertEquals(400 * 50_000, metadata[0x3361])
        assertEquals(32_000, metadata[0x3341])
        assertTrue(sdrHdrStaticMetadata(400f, emptySet()).isEmpty())
        assertEquals(setOf(0x3360, 0x3361), sdrHdrStaticMetadata(150f, setOf("EGL_EXT_surface_CTA861_3_metadata")).toMap().keys)
        assertEquals(150 * 50_000, sdrHdrStaticMetadata(150f, setOf("EGL_EXT_surface_CTA861_3_metadata")).toMap()[0x3361])
        assertTrue(sdrHdrStaticMetadata(Float.NaN, both).isEmpty())
    }
}
