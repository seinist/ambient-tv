package com.ambient.tv.ui.player.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.ambient.tv.ui.player.buildVlcNerdOverlayModel

class VlcStreamInfoTest {
    class LegacyTrack(@JvmField val codec: Int)
    class ModernTrack(@JvmField val codec: String, @JvmField val fourcc: Int = 0)

    @Test fun `VLC 3 numeric codec is decoded as FourCC`() {
        assertEquals("h264", vlcTrackCodec(LegacyTrack(0x34363268)))
        assertNull(vlcTrackCodec(LegacyTrack(0)))
    }

    @Test fun `VLC 4 codec name takes priority with FourCC fallback`() {
        assertEquals("HEVC", vlcTrackCodec(ModernTrack("HEVC")))
        assertEquals("h264", vlcTrackCodec(ModernTrack(" ", 0x34363268)))
        assertNull(vlcTrackCodec(ModernTrack("")))
    }

    @Test fun `stream labels include audio properties without inventing missing values`() {
        val stats = VlcRuntimeStats(videoWidth = 1920, videoHeight = 1080,
            audioCodec = "aac", audioChannels = 2, audioSampleRate = 48000)
        assertEquals("1920×1080", stats.videoSizeLabel())
        assertEquals("AAC · 2ch · 48000 Hz", stats.audioFormatLabel())
        assertNull(VlcRuntimeStats().videoSizeLabel())
        assertNull(VlcRuntimeStats().audioFormatLabel())
    }

    @Test fun `VLC overlay uses selected stream codec without provider metadata`() {
        val model = buildVlcNerdOverlayModel(VlcPlaybackSnapshot(runtimeStats = VlcRuntimeStats(
            videoWidth = 1920, videoHeight = 1080, videoFps = 25.0, videoCodec = "h264",
            audioCodec = "aac",
        )))
        assertTrue(model.video.any { "25.000 fps" in it.value && "H264" in it.value })
        assertTrue(model.audio.any { it.value == "AAC" })
    }
}
