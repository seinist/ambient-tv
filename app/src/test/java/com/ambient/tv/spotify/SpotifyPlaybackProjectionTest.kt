package com.ambient.tv.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPlaybackProjectionTest {
    @Test
    fun `web controls enrich engine state without replacing native track session`() {
        val current = SpotifyTrack(id = "track", name = "Current")
        val engine = SpotifyPlaybackState(
            available = true,
            connected = true,
            playing = true,
            positionMs = 100,
            volume = 100,
            shuffle = false,
            repeatMode = 0,
            current = current,
            queue = listOf(SpotifyTrack(id = "native-next", name = "Native next")),
        )

        val result = projectSpotifyPlaybackState(
            engine,
            SpotifyWebPlaybackOverlay(
                queue = listOf(SpotifyTrack(id = "web-next", name = "Web next")),
                positionMs = 500,
                volume = 35,
                shuffle = true,
                repeatMode = 2,
                error = "web failure",
            ),
        )

        assertSame(current, result.current)
        assertTrue(result.available)
        assertTrue(result.connected)
        assertTrue(result.playing)
        assertEquals(500L, result.positionMs)
        assertEquals(35, result.volume)
        assertTrue(result.shuffle)
        assertEquals(2, result.repeatMode)
        assertEquals("web-next", result.queue.single().id)
        assertEquals("web failure", result.error)
    }

    @Test
    fun `empty web overlay preserves native engine state exactly`() {
        val engine = SpotifyPlaybackState(
            available = true,
            connected = true,
            playing = false,
            positionMs = 12_345,
            volume = 70,
            shuffle = true,
            repeatMode = 1,
            current = SpotifyTrack(id = "track", name = "Current"),
            queue = listOf(SpotifyTrack(id = "native-next", name = "Native next")),
            error = "native failure",
        )

        val result = projectSpotifyPlaybackState(engine, SpotifyWebPlaybackOverlay())

        assertEquals(engine, result)
        assertFalse(result.playing)
    }
}
