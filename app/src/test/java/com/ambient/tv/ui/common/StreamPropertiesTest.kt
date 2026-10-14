package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamPropertiesTest {
    @Test
    fun `hdr labels remain precise for technical badges`() {
        assertEquals("DOLBY VISION P8.1", formatHdrMetadata("Dolby Vision P8.1", "main 10"))
        assertEquals("HDR10+", formatHdrMetadata("HDR10Plus"))
        assertEquals("HDR10", formatHdrMetadata("smpte2084 bt2020"))
        assertEquals("HLG", formatHdrMetadata("arib-std-b67"))
        assertNull(formatHdrMetadata(null, "main 10"))
    }

    @Test
    fun `resolution reads the same from every provider`() {
        assertEquals("4K", formatResolution("4k"))
        assertEquals("4K", formatResolution("2160p"))
        assertEquals("1080p", formatResolution("1080"))
        assertEquals("1080p", formatResolution("1080p"))
        assertEquals("SD", formatResolution("sd"))
        assertNull(formatResolution(null))
    }
}
