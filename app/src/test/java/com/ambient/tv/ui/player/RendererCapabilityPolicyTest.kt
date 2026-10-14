package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererCapabilityPolicyTest {
    @Test
    fun `dolby vision uses media3 instead of native renderers`() {
        assertEquals("media3", RendererCapabilityPolicy.effectiveBackend("mpv", "hevc", "Dolby Vision", "main 10"))
        assertEquals("media3", RendererCapabilityPolicy.effectiveBackend("vlc", "hevc", null, "dvhe 08.06"))
    }

    @Test
    fun `dolby vision keeps media3 and auto choices`() {
        assertEquals("media3", RendererCapabilityPolicy.effectiveBackend("media3", "dovi", null, null))
        assertEquals("auto", RendererCapabilityPolicy.effectiveBackend("auto", "hevc", "DolbyVision", null))
    }

    @Test
    fun `runtime decoder format overrides incomplete library metadata`() {
        assertEquals(
            "media3",
            RendererCapabilityPolicy.effectiveBackend(
                requested = "mpv",
                videoCodec = "hevc",
                dynamicRange = null,
                videoProfile = "main 10",
                detectedDolbyVision = true,
            ),
        )
    }

    @Test
    fun `hdr10 remains available to every renderer`() {
        assertEquals("mpv", RendererCapabilityPolicy.effectiveBackend("mpv", "hevc", "HDR10", "main 10"))
        assertEquals("vlc", RendererCapabilityPolicy.effectiveBackend("vlc", "hevc", "HDR", "main 10"))
    }
    @Test
    fun `dolby vision aliases must also protect compatibility fallbacks`() {
        listOf("dvh1.05.06", "dvav", "dva1").forEach { codec ->
            assertEquals("media3", RendererCapabilityPolicy.effectiveBackend("vlc", codec, null, null))
        }
        assertEquals("media3", RendererCapabilityPolicy.effectiveBackend("mpv", "hevc", "DOLBY_VISION", null))
        assertEquals("media3", RendererCapabilityPolicy.effectiveBackend("mpv", "hevc", "DV", null))
    }
}
