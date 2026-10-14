package com.liskovsoft.youtubeapi.videoinfo.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoUrlHolderTest {
    @Test
    fun `malformed trailing percent is treated as a literal query value`() {
        val holder = VideoUrlHolder("https://example.test/videoplayback?n=throttle%")

        assertEquals("throttle%", holder.getNParam())
        assertTrue(holder.getUrl().orEmpty().contains("n=throttle%25"))
    }

    @Test
    fun `valid percent escape remains valid`() {
        val holder = VideoUrlHolder("https://example.test/videoplayback?n=one%2Ftwo")

        assertEquals("one/two", holder.getNParam())
        assertTrue(holder.getUrl().orEmpty().contains("n=one%2Ftwo"))
    }
}
