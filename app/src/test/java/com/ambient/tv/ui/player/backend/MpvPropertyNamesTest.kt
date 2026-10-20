package com.ambient.tv.ui.player.backend

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * mpv silently answers "unavailable" for a misspelt property, so names read by the app are
 * checked against the pinned mpv (player/command.c: get_img_par / track sub-properties).
 */
class MpvPropertyNamesTest {
    private val imageParams = setOf(
        "pixelformat", "hw-pixelformat", "average-bpp", "w", "h", "dw", "dh", "crop-x", "crop-y", "crop-w", "crop-h",
        "aspect", "aspect-name", "par", "sar", "sar-name", "colormatrix", "colorlevels", "primaries", "gamma",
        "sig-peak", "light", "chroma-location", "stereo-in", "rotate", "alpha", "min-luma", "max-luma", "max-cll",
        "max-fall", "scene-max-r", "scene-max-g", "scene-max-b", "scene-avg", "max-pq-y", "avg-pq-y",
        "prim-red-x", "prim-red-y", "prim-green-x", "prim-green-y", "prim-blue-x", "prim-blue-y",
        "prim-white-x", "prim-white-y",
    )
    private val trackFields = setOf(
        "id", "type", "src-id", "title", "lang", "audio-channels", "image", "albumart", "default", "forced",
        "dependent", "visual-impaired", "hearing-impaired", "original", "commentary", "external", "selected",
        "main-selection", "external-filename", "ff-index", "hls-bitrate", "program-id", "program-ids", "decoder",
        "decoder-desc", "codec", "codec-desc", "codec-profile", "demux-w", "demux-h", "demux-crop-x", "demux-crop-y",
        "demux-crop-w", "demux-crop-h", "demux-channel-count", "demux-channels", "demux-samplerate", "demux-fps",
        "demux-bitrate", "demux-rotation", "demux-par", "demux-duration", "format-name", "replaygain-track-peak",
        "replaygain-track-gain", "replaygain-album-peak", "replaygain-album-gain", "dolby-vision-profile",
        "dolby-vision-level", "metadata",
    )
    private val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test fun imageParamPropertiesExistInMpv() {
        val pattern = Regex(""""(video-params|video-dec-params|video-out-params|video-target-params)/([a-z0-9-]+)""")
        val unknown = sources.flatMap { file ->
            pattern.findAll(file.readText()).map { it.groupValues[2] }.filterNot { it in imageParams }.map { "${file.name}: $it" }.toList()
        }
        assertTrue(sources.isNotEmpty())
        assertEquals(emptyList<String>(), unknown)
    }

    @Test fun trackPropertiesExistInMpv() {
        val pattern = Regex(""""(?:current-tracks/(?:video|audio|sub)|track-list/\d+)/([a-z0-9-]+)""")
        val unknown = sources.flatMap { file ->
            pattern.findAll(file.readText()).map { it.groupValues[1] }.filterNot { it in trackFields }.map { "${file.name}: $it" }.toList()
        }
        assertEquals(emptyList<String>(), unknown)
    }

    @Test fun hdrRoutingReadsTheRealNames() {
        assertEquals("video-params/colorlevels", MPV_PROP_COLORLEVELS)
        assertEquals("video-dec-params/colorlevels", MPV_PROP_DEC_COLORLEVELS)
        assertEquals("current-tracks/video/dolby-vision-profile", MPV_PROP_DOLBY_VISION_PROFILE)
        assertEquals("current-tracks/video/id", MPV_PROP_VIDEO_TRACK_ID)
        val engine = File("src/main/java/com/ambient/tv/ui/player/backend/MpvPlaybackEngine.kt").readText()
        assertFalse(engine.contains("\"video-params/levels\""))
        assertTrue(engine.contains("getPropertyString(MPV_PROP_COLORLEVELS)"))
        // The HDR CAS curve follows the range mpv reports under that name.
        assertNotEquals(mpvCasTransferProfile("pq", "limited"), mpvCasTransferProfile("pq", "full"))
    }
}
