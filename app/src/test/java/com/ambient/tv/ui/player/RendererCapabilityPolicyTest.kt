package com.ambient.tv.ui.player

import com.ambient.tv.domain.playback.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun `profile 7 native choices are unavailable while automatic and media3 remain usable`() {
        val availability = RendererAvailability(
            RendererCapabilityPolicy.isDolbyVision("hevc", "Dolby Vision P7", "main 10"),
            mpvAvailable = true,
        )
        assertEquals(setOf("vlc", "mpv"), availability.disabledKeys)
        assertTrue(availability.canSelect("auto"))
        assertTrue(availability.canSelect("media3"))
        assertFalse(availability.canSelect("vlc"))
        assertFalse(availability.canSelect("mpv"))
    }

    @Test fun `HDR10 does not inherit the Dolby Vision renderer restriction`() {
        val availability = RendererAvailability(
            RendererCapabilityPolicy.isDolbyVision("hevc", "HDR10", "main 10"),
            mpvAvailable = true,
        )
        assertTrue(availability.canSelect("vlc"))
        assertTrue(availability.canSelect("mpv"))
        assertFalse(availability.canSelect("unknown"))
    }

    @Test fun `unbundled mpv stays unavailable without restricting vlc`() {
        val availability = RendererAvailability(dolbyVision = false, mpvAvailable = false)
        assertEquals(setOf("mpv"), availability.disabledKeys)
        assertTrue(availability.canSelect("vlc"))
    }

    @Test fun `DV MIME and AV1 codec use the same protection as HEVC Dolby Vision`() {
        listOf("video/dolby-vision", "dav1.10.09").forEach { codec ->
            assertEquals("media3", RendererCapabilityPolicy.effectiveBackend("vlc", codec, null, null))
            assertEquals("media3", RendererCapabilityPolicy.effectiveBackend("mpv", codec, null, null))
        }
    }

    @Test fun `only full video conversion replaces original Dolby Vision descriptors`() {
        PlaybackMode.entries.forEach { mode ->
            val videoTranscoded = mode == PlaybackMode.TRANSCODE
            listOf("vlc", "mpv").forEach { requested ->
                assertEquals(mode.name, if (videoTranscoded) requested else "media3",
                    RendererCapabilityPolicy.effectiveBackend(requested, "dvhe", "Dolby Vision", "dvhe.07.06",
                        videoTranscoded = videoTranscoded))
            }
            assertEquals(mode.name, !videoTranscoded,
                RendererCapabilityPolicy.isDolbyVision("dvhe", "Dolby Vision", "dvhe.07.06",
                    videoTranscoded = videoTranscoded))
        }
    }

    @Test fun `actual Dolby Vision still blocks native renderers after a requested transcode`() {
        listOf("vlc", "mpv").forEach { requested ->
            assertEquals("media3", RendererCapabilityPolicy.effectiveBackend(requested, "h264", "SDR", null,
                detectedDolbyVision = true, videoTranscoded = true))
        }
    }
}
