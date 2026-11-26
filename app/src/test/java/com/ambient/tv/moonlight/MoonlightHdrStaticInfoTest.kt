package com.ambient.tv.moonlight

import org.junit.Assert.*
import org.junit.Test

class MoonlightHdrStaticInfoTest {
    @Test fun convertsServerMasteringMetadataIntoAndroidFormat() {
        val upstream = ByteArray(26) { it.toByte() }
        val result = requireNotNull(moonlightHdrStaticInfo(upstream))
        assertEquals(25, result.remaining())
        assertEquals(0, result.get().toInt())
        assertEquals(0, result.get().toInt())
        assertEquals(1, result.get().toInt())
        assertEquals(0x0302, result.short.toInt())
        assertEquals(25, result.capacity())
        val legacy = requireNotNull(moonlightHdrStaticInfo(upstream.copyOf(24)))
        assertArrayEquals(legacy.array(), result.array())
    }
    @Test fun rejectsMalformedHdrMetadata() {
        assertNull(moonlightHdrStaticInfo(null))
        assertNull(moonlightHdrStaticInfo(byteArrayOf()))
        assertNull(moonlightHdrStaticInfo(ByteArray(23)))
        assertNull(moonlightHdrStaticInfo(ByteArray(25)))
        assertNull(moonlightHdrStaticInfo(ByteArray(27)))
    }
}
