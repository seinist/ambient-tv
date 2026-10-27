package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class HdrOutputPolicyTest {
    @Test fun nativeSurfaceOnlyWhenTheDisplaySupportsTheHdrTransfer() {
        assertTrue(nativeHdrSurfacePreferred(HdrStandard.HDR10, setOf(HdrStandard.HDR10)))
        assertTrue(nativeHdrSurfacePreferred(HdrStandard.HDR10_PLUS, setOf(HdrStandard.HDR10)))
        assertFalse(nativeHdrSurfacePreferred(HdrStandard.HLG, setOf(HdrStandard.HDR10)))
        assertFalse(nativeHdrSurfacePreferred(null, setOf(HdrStandard.HDR10)))
    }
    @Test fun plexDolbyVisionLabelKeepsTheHdrBaseLayerOnANonDvDisplay() {
        assertEquals(HdrStandard.HDR10, dolbyVisionBaseLayer("Dolby Vision P8.1"))
        assertEquals(HdrStandard.HLG, dolbyVisionBaseLayer("Dolby Vision P8.4"))
        assertEquals(HdrStandard.HDR10, dolbyVisionBaseLayer("Dolby Vision P7"))
        assertNull(dolbyVisionBaseLayer("Dolby Vision P5"))
        assertTrue(nativeHdrSurfacePreferred(HdrStandard.DOLBY_VISION, setOf(HdrStandard.HDR10), HdrStandard.HDR10))
        assertFalse(nativeHdrSurfacePreferred(HdrStandard.DOLBY_VISION, setOf(HdrStandard.HDR10)))
    }
    private val hdr10Tv = setOf(HdrStandard.HDR10, HdrStandard.HLG)

    @Test
    fun `dolby vision profile is read from codec strings`() {
        assertEquals(8, dolbyVisionProfile("dvhe.08.06"))
        assertEquals(5, dolbyVisionProfile("hvc1.2.4.L153, dvh1.05.06"))
        assertEquals(10, dolbyVisionProfile("dav1.10.09"))
        assertNull(dolbyVisionProfile("hvc1.2.4.L153"))
        assertNull(dolbyVisionProfile(null))
    }

    @Test
    fun `native formats pass through`() {
        assertEquals(HdrStandard.HDR10, hdrOutputStandard(HdrStandard.HDR10, null, null, hdr10Tv))
        assertNull(hdrOutputStandard(null, null, null, hdr10Tv))
    }

    @Test
    fun `fallback formats play as their base layer`() {
        assertEquals(HdrStandard.HDR10, hdrOutputStandard(HdrStandard.HDR10_PLUS, null, null, hdr10Tv))
        assertEquals(HdrStandard.HDR10, hdrOutputStandard(HdrStandard.DOLBY_VISION, 8, null, hdr10Tv))
        assertEquals(HdrStandard.HLG, hdrOutputStandard(HdrStandard.DOLBY_VISION, 8, HdrStandard.HLG, hdr10Tv))
        assertEquals(HdrStandard.HDR10, hdrOutputStandard(HdrStandard.DOLBY_VISION, 7, null, hdr10Tv))
        assertEquals(HdrStandard.HDR10, hdrOutputStandard(HdrStandard.DOLBY_VISION, null, HdrStandard.HDR10, hdr10Tv))
    }

    @Test
    fun `profile 5 and SDR displays need tone mapping`() {
        assertNull(hdrOutputStandard(HdrStandard.DOLBY_VISION, 5, null, hdr10Tv))
        assertNull(hdrOutputStandard(HdrStandard.DOLBY_VISION, null, null, hdr10Tv))
        assertNull(hdrOutputStandard(HdrStandard.HDR10, null, null, emptySet()))
        assertNull(hdrOutputStandard(HdrStandard.HLG, null, null, setOf(HdrStandard.HDR10)))
        assertEquals(HdrStandard.DOLBY_VISION,
            hdrOutputStandard(HdrStandard.DOLBY_VISION, 5, null, hdr10Tv + HdrStandard.DOLBY_VISION))
    }

    @Test
    fun `server labels name the base layer`() {
        assertEquals(HdrStandard.HDR10, dolbyVisionBaseLayer("Dolby Vision/HDR10"))
        assertEquals(HdrStandard.HLG, dolbyVisionBaseLayer("DoVi/HLG"))
        assertNull(dolbyVisionBaseLayer("Dolby Vision"))
    }
}
