package com.ambient.tv.youtube

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.ui.player.DisplayRefreshRateMatcher
import com.ambient.tv.ui.player.SupportedDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeDisplayLimitTest {
    private fun m(w: Int, h: Int, hz: Float, id: Int = w + h + hz.toInt()) =
        SupportedDisplayMode(DisplayRefreshRateMatcher.modeKey(w, h, hz), id, w, h, hz)
    private val fhd24 = m(1920, 1080, 23.976f)
    private val fhd60 = m(1920, 1080, 60f)
    private val uhd24 = m(3840, 2160, 23.976f)
    private val uhd30 = m(3840, 2160, 30f)
    private val uhd60 = m(3840, 2160, 60f)
    private val tv = listOf(fhd24, fhd60, uhd24, uhd30, uhd60)
    private val anything: YouTubeDecodeCheck = { _, _, _, _, hdr -> hdr == null }

    private fun v(w: Int, h: Int, fps: Int, url: String) =
        YouTubeAdaptiveFormat("video/webm; codecs=\"vp09.00.51.08\"", "https://g/$url", width = w, height = h, fps = fps, bitrate = h * 1000L)
    private val formats = listOf(v(3840, 2160, 60, "4k60"), v(2560, 1440, 60, "1440p60"), v(1920, 1080, 60, "1080p60"),
        v(1920, 1080, 30, "1080p30"), v(1280, 720, 30, "720p30"))

    @Test fun `automatic display modes use the largest permitted mode`() {
        assertEquals(YouTubeDisplayLimit(2160, 60), youtubeDisplayLimit(tv, fhd60, AppPreferences.DISPLAY_MODE_AUTO_CONTENT))
        assertEquals(YouTubeDisplayLimit(2160, 60), youtubeDisplayLimit(tv, fhd24, AppPreferences.DISPLAY_MODE_AUTO_DISPLAY))
        // The whitelist only allows 1080p: no 4K.
        assertEquals(YouTubeDisplayLimit(1080, 60), youtubeDisplayLimit(tv, uhd60, AppPreferences.DISPLAY_MODE_AUTO_CONTENT, setOf(fhd24.key, fhd60.key)))
        // 4K only up to 30 Hz: 4K at 30 fps.
        assertEquals(YouTubeDisplayLimit(2160, 30), youtubeDisplayLimit(tv, fhd60, AppPreferences.DISPLAY_MODE_AUTO_DISPLAY, setOf(fhd60.key, uhd24.key, uhd30.key)))
    }

    @Test fun `a fixed mode pins its resolution and system keeps the current mode`() {
        assertEquals(YouTubeDisplayLimit(1080, 60), youtubeDisplayLimit(tv, uhd60, fhd24.key))
        assertEquals(YouTubeDisplayLimit(1080, 60), youtubeDisplayLimit(tv, fhd60, "system"))
        assertEquals(YouTubeDisplayLimit(2160, 60), youtubeDisplayLimit(tv, uhd24, "system"))
        // Without rate switching only the current refresh rate counts.
        assertEquals(YouTubeDisplayLimit(2160, 30), youtubeDisplayLimit(tv, uhd24, "system", canSwitchRate = false))
        // An unknown fixed key falls back to the current mode.
        assertEquals(YouTubeDisplayLimit(1080, 60), youtubeDisplayLimit(tv, fhd60, "1x1@1.000"))
        assertNull(youtubeDisplayLimit(emptyList(), null, "system"))
    }

    @Test fun `portrait panels count by their short side`() {
        val portrait = m(1080, 1920, 60f)
        assertEquals(YouTubeDisplayLimit(1080, 60), youtubeDisplayLimit(listOf(portrait), portrait, "system"))
    }

    @Test fun `the display caps presets and frame rates`() {
        val fullHd = YouTubeDisplayLimit(1080, 60)
        // Preset 4K60 on a 1080p display: 1080p60.
        assertEquals(YouTubeVideoPreset(1080, 60, "vp9"), YouTubeDeviceVideo.videoTarget("2160:60:vp9", 0, fullHd, anything))
        // A user cap below the display still applies, one above it does not lift the display limit.
        assertEquals(YouTubeVideoPreset(720, 60, "vp9"), YouTubeDeviceVideo.videoTarget("2160:60:vp9", 720, fullHd, anything))
        assertEquals(YouTubeVideoPreset(1080, 60, "vp9"), YouTubeDeviceVideo.videoTarget("2160:60:vp9", 2160, fullHd, anything))
        // No 50/60 Hz mode: 30 fps.
        assertEquals(YouTubeVideoPreset(2160, 30, "vp9"), YouTubeDeviceVideo.videoTarget("2160:60:vp9", 0, YouTubeDisplayLimit(2160, 30), anything))
        val target = YouTubeDeviceVideo.videoTarget("2160:60:vp9", 0, fullHd, anything)
        assertEquals("https://g/1080p60", youtubePickVideo(formats, YOUTUBE_STREAM_AUTO, target, anything, fullHd)?.url)
    }

    @Test fun `an explicit stream above the display falls back to automatic`() {
        val fullHd = YouTubeDisplayLimit(1080, 60)
        val target = YouTubeDeviceVideo.videoTarget(YOUTUBE_PRESET_AUTO, 0, fullHd, anything)
        val all = youtubeVideoOptions(formats, anything)
        val fourK = all.first { it.height == 2160 }.key
        assertEquals("https://g/1080p60", youtubePickVideo(formats, fourK, target, anything, fullHd)?.url)
        assertEquals("https://g/4k60", youtubePickVideo(formats, fourK, target, anything, null)?.url)
        // The panel lists only what the display shows.
        assertEquals(listOf(1080, 1080, 720), youtubeVideoOptions(formats, anything, fullHd).map { it.height })
        // An explicit 30 fps pick inside the limit is kept.
        val p30 = all.first { it.height == 1080 && it.fps == 30 }.key
        assertEquals("https://g/1080p30", youtubePickVideo(formats, p30, target, anything, fullHd)?.url)
        // 60 fps streams disappear on a 30 Hz-only display.
        assertEquals(listOf(30, 30), youtubeVideoOptions(formats, anything, YouTubeDisplayLimit(1080, 30)).map { it.fps })
    }

    @Test fun `media3 box keeps portrait and cinemascope of the quality step`() {
        val box = youtubeMedia3MaxVideoSize(1080)
        assertEquals(2016, box)
        // Portrait Short 1080×1920 and 1920×800 pass, 2560×1440 and 3840×2160 do not.
        assert(1080 <= box && 1920 <= box)
        assert(2560 > box)
    }

    @Test fun `without rate switching a fixed mode keeps only its own refresh rate`() {
        // Fixed 4K24 with adjustment off: 4K at 30 fps, 60 Hz modes of the same size do not count.
        assertEquals(YouTubeDisplayLimit(2160, 30), youtubeDisplayLimit(tv, fhd60, uhd24.key, canSwitchRate = false))
        // Fixed 4K60 with adjustment off: 60 fps.
        assertEquals(YouTubeDisplayLimit(2160, 60), youtubeDisplayLimit(tv, fhd24, uhd60.key, canSwitchRate = false))
        // Fixed 1080p24 with adjustment on: the 60 Hz 1080p mode is reachable.
        assertEquals(YouTubeDisplayLimit(1080, 60), youtubeDisplayLimit(tv, uhd60, fhd24.key, canSwitchRate = true))
        // Automatic policies switch anyway; the flag does not lower them.
        assertEquals(YouTubeDisplayLimit(2160, 60), youtubeDisplayLimit(tv, fhd24, AppPreferences.DISPLAY_MODE_AUTO_CONTENT, canSwitchRate = false))
    }

    @Test fun `media3 square streams above the quality step are excluded`() {
        val sizes = listOf(1080 to 1080, 1440 to 1440, 2016 to 2016, 1080 to 1920, 1920 to 800)
        // 1080p: 1440² and 2016² are 1440p+ although they fit the 2016 box.
        assertEquals(listOf(0, 3, 4), youtubeMedia3AllowedVideoTracks(sizes, 1080))
        // Everything fits: no override.
        assertNull(youtubeMedia3AllowedVideoTracks(listOf(1080 to 1080, 1920 to 1080), 1080))
        // Nothing fits: the box stays the only limit (never an empty selection).
        assertNull(youtubeMedia3AllowedVideoTracks(listOf(1440 to 1440), 720))
        assertNull(youtubeMedia3AllowedVideoTracks(sizes, 0))
        assertEquals(1440, youtubeQualityHeight(1440, 1440))
        assertEquals(1440, youtubeQualityHeight(2560, 1072))
        assertEquals(1080, youtubeQualityHeight(1080, 1920))
    }
}
