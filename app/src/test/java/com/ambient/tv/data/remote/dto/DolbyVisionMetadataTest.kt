package com.ambient.tv.data.remote.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class DolbyVisionMetadataTest {
    @Test
    fun `plex dovi stream fields identify dolby vision`() {
        val stream = Json.decodeFromString<StreamDto>(
            """{"streamType":1,"codec":"hevc","profile":"main 10","DOVIPresent":true,"DOVIProfile":8,"DOVIBLCompatID":1}""",
        )

        assertEquals("Dolby Vision P8.1", stream.inferredVideoDynamicRange)
    }

    @Test
    fun `plex transfer metadata identifies hdr10 and hlg`() {
        val hdr10 = Json.decodeFromString<StreamDto>(
            """{"streamType":1,"codec":"hevc","colorTrc":"smpte2084","colorPrimaries":"bt2020"}""",
        )
        val hlg = Json.decodeFromString<StreamDto>(
            """{"streamType":1,"codec":"hevc","colorTrc":"arib-std-b67","colorPrimaries":"bt2020"}""",
        )

        assertEquals("HDR10", hdr10.inferredVideoDynamicRange)
        assertEquals("HLG", hlg.inferredVideoDynamicRange)
    }
}
