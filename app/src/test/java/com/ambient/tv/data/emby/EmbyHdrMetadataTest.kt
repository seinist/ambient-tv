package com.ambient.tv.data.emby

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbyHdrMetadataTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `emby dolby vision profile metadata is preserved`() {
        val stream = json.decodeFromString<EmbyMediaStream>(
            """{"Type":"Video","Codec":"hevc","VideoRangeType":"DOVIWithHDR10","VideoDoViTitle":"Dolby Vision Profile 8.1 (HDR10)","DvProfile":8,"DvBlSignalCompatibilityId":1}""",
        )
        assertEquals("Dolby Vision P8.1", stream.inferredVideoDynamicRange())
    }

    @Test
    fun `emby hdr10 plus hlg and hdr10 are identified`() {
        val hdr10Plus = json.decodeFromString<EmbyMediaStream>(
            """{"Type":"Video","VideoRangeType":"HDR10Plus","Hdr10PlusPresentFlag":true,"ColorTransfer":"smpte2084"}""",
        )
        val hlg = json.decodeFromString<EmbyMediaStream>(
            """{"Type":"Video","VideoRange":"HLG","ColorTransfer":"arib-std-b67"}""",
        )
        val hdr10 = json.decodeFromString<EmbyMediaStream>(
            """{"Type":"Video","VideoRange":"HDR","ColorTransfer":"smpte2084","ColorPrimaries":"bt2020"}""",
        )
        assertEquals("HDR10+", hdr10Plus.inferredVideoDynamicRange())
        assertEquals("HLG", hlg.inferredVideoDynamicRange())
        assertEquals("HDR10", hdr10.inferredVideoDynamicRange())
    }
}
