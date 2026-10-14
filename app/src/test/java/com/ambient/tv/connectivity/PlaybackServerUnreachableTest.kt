package com.ambient.tv.connectivity

import com.ambient.tv.ui.common.MediaSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class PlaybackServerUnreachableTest {
    @Test
    fun `classifies local media systems`() {
        assertEquals(MediaSystem.FILES, PlaybackServerUnreachable.mediaSystemForRatingKey("files:src:YQ"))
        assertTrue(PlaybackServerUnreachable.appliesToLocalMedia("12345"))
        assertFalse(PlaybackServerUnreachable.appliesToLocalMedia("youtube:abc"))
        assertFalse(PlaybackServerUnreachable.appliesToLocalMedia("spotify:track:1"))
    }

    @Test
    fun `detects network causes and messages`() {
        assertTrue(PlaybackServerUnreachable.isNetworkish(SocketTimeoutException("timeout"), null))
        assertTrue(PlaybackServerUnreachable.isNetworkish(UnknownHostException("host"), null))
        assertTrue(PlaybackServerUnreachable.isNetworkish(null, "Unexpected end of stream"))
        assertFalse(PlaybackServerUnreachable.isNetworkish(null, "Audio decoder failed"))
    }
}
