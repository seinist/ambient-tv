package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPanelArtworkResolverTest {
    @Test
    fun `plex transcode is resized to compact panel target`() {
        val input = "http://plex.local:32400/photo/:/transcode?width=3840&height=2160&url=%2Flibrary%2Fmetadata%2F1%2Fart%2F2&X-Plex-Token=x"
        val result = PlaybackPanelArtworkResolver.resizeKnownProviderUrl(input, 960, 540)
        assertTrue(result.contains("width=960"))
        assertTrue(result.contains("height=540"))
        assertTrue(result.contains("X-Plex-Token=x"))
    }

    @Test
    fun `jellyfin image keeps auth and replaces max width`() {
        val input = "http://jelly.local:8096/Items/abc/Images/Backdrop/0?tag=t&maxWidth=3840&quality=95&ApiKey=secret"
        val result = PlaybackPanelArtworkResolver.resizeKnownProviderUrl(input, 1280, 720)
        assertTrue(result.contains("maxWidth=1280"))
        assertTrue(result.contains("ApiKey=secret"))
        assertTrue(result.contains("quality=95"))
    }

    @Test
    fun `emby image keeps auth and replaces max width`() {
        val input = "http://emby.local:8096/Items/abc/Images/Backdrop/0?tag=t&maxWidth=3840&quality=95&api_key=secret"
        val result = PlaybackPanelArtworkResolver.resizeKnownProviderUrl(input, 1280, 720)
        assertTrue(result.contains("maxWidth=1280"))
        assertTrue(result.contains("api_key=secret"))
        assertTrue(result.contains("quality=95"))
    }

    @Test
    fun `resolver prefers backdrop and keeps thumbnail as fallback`() {
        val result = PlaybackPanelArtworkResolver.resolve(
            backdropUrl = "https://example.test/backdrop.jpg",
            thumbnailUrl = "https://example.test/thumb.jpg",
            highQualityAssets = false,
        )
        assertEquals("https://example.test/backdrop.jpg", result.primaryUrl)
        assertEquals(listOf("https://example.test/thumb.jpg"), result.fallbackUrls)
    }
}
