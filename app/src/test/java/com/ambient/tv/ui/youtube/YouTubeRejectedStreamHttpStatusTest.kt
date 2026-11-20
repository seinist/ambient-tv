package com.ambient.tv.ui.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeRejectedStreamHttpStatusTest {
    @Test fun `recognises HTTP forbidden and gone without matching unrelated numbers`() {
        assertEquals(403, youtubeRejectedStreamHttpStatus("ffmpeg: HTTP error 403 Forbidden"))
        assertEquals(410, youtubeRejectedStreamHttpStatus("HTTP 410 Gone"))
        assertNull(youtubeRejectedStreamHttpStatus("format: 1403 unsupported"))
        assertNull(youtubeRejectedStreamHttpStatus("HTTP 4030"))
        assertNull(youtubeRejectedStreamHttpStatus("codec not supported"))
        assertNull(youtubeRejectedStreamHttpStatus(null))
    }
}
