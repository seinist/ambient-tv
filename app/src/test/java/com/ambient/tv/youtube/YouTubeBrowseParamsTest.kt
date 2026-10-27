package com.ambient.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeBrowseParamsTest {
    @Test
    fun `subscribed-channel tab params carry the channel id`() {
        assertEquals("UCbfYPyITQ-7l4upoX8nvctg", youtubeChannelIdInBrowseParams("igMaChhVQ2JmWVB5SVRRLTdsNHVwb1g4bnZjdGc%3D"))
    }

    @Test
    fun `params without a channel id give null`() {
        assertNull(youtubeChannelIdInBrowseParams("EgJAAQ%3D%3D"))
        assertNull(youtubeChannelIdInBrowseParams("not base64!"))
    }
}
