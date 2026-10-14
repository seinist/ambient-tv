package com.ambient.tv.youtube

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeStreamChoiceTest {
    private val kinds = YouTubeAudioKindNames("Original", "synchronisiert", "automatisch synchronisiert", "Audiodeskription")

    private fun v(h: Int, fps: Int, codec: String, url: String, bitrate: Long = h * 1000L) =
        YouTubeAdaptiveFormat("video/${if (codec == "avc1") "mp4" else "webm"}; codecs=\"$codec\"", url, height = h, fps = fps, bitrate = bitrate)
    private val formats = listOf(
        v(2160, 60, "vp09.00.51.08", "https://g/2160p60vp9"),
        v(2160, 60, "vp09.02.51.10", "https://g/2160p60hdr"),
        v(1440, 60, "vp09.00.50.08", "https://g/1440p60vp9"),
        v(1080, 60, "avc1.64002a", "https://g/1080p60avc"),
        v(1080, 60, "vp09.00.41.08", "https://g/1080p60vp9"),
        v(1080, 30, "avc1.640028", "https://g/1080p30avc"),
        v(720, 30, "av01.0.05M.08", "https://g/720av1"),
        YouTubeAdaptiveFormat("audio/mp4; codecs=\"mp4a.40.2\"", "https://g/en", bitrate = 128_000, xtags = "acont=original:lang=en", audioTrackId = "en.4"),
        YouTubeAdaptiveFormat("audio/webm; codecs=\"opus\"", "https://g/en-opus", bitrate = 160_000, xtags = "acont=original:lang=en", audioTrackId = "en.4"),
        YouTubeAdaptiveFormat("audio/mp4; codecs=\"mp4a.40.2\"", "https://g/de", bitrate = 128_000, xtags = "acont=dubbed:lang=de", audioTrackId = "de.3"),
    )
    /** A 1080p box: H.264 up to 1080p60, no VP9 or AV1 in hardware. */
    private val fullHdBox: YouTubeDecodeCheck = { family, _, h, fps, hdr -> hdr == null && family == "avc" && h <= 1080 && fps <= 60 }
    /** A 4K TV: VP9 up to 2160p60, H.264 up to 1080p. */
    private val uhdTv: YouTubeDecodeCheck = { family, _, h, _, hdr -> hdr == null && ((family == "vp9" && h <= 2160) || (family == "avc" && h <= 1080)) }
    /** The same TV with HDR10 on and a VP9 Profile 2 HDR decoder. */
    private val hdrTv: YouTubeDecodeCheck = { family, w, h, fps, hdr -> (hdr == null || hdr == "hdr10") && (family == "vp9" && h <= 2160 || uhdTv(family, w, h, fps, null)) }

    @Test fun `automatic follows the display`() {
        assertEquals(1080, youtubeAutoMaxHeight(1080))
        assertEquals(2160, youtubeAutoMaxHeight(2160))
        assertEquals(1440, youtubeAutoMaxHeight(1440))
        assertEquals(1080, youtubeAutoMaxHeight(1200))
        assertEquals(720, youtubeAutoMaxHeight(480))
        assertEquals(1080, youtubeAutoMaxHeight(1036))
    }

    @Test fun `automatic picks the best stream the device decodes up to the cap`() {
        assertEquals("https://g/2160p60vp9", youtubePickVideo(formats, YOUTUBE_STREAM_AUTO, 2160, uhdTv)?.url)
        assertEquals("https://g/1080p60avc", youtubePickVideo(formats, YOUTUBE_STREAM_AUTO, 1080, uhdTv)?.url)
        // The 1080p box never gets VP9, HDR or AV1, even if the cap allowed more.
        assertEquals("https://g/1080p60avc", youtubePickVideo(formats, YOUTUBE_STREAM_AUTO, 2160, fullHdBox)?.url)
    }

    @Test fun `the panel lists playable qualities and a choice is honoured`() {
        val options = youtubeVideoOptions(formats, uhdTv)
        assertEquals(listOf("2160p60 · VP9", "1440p60 · VP9", "1080p60 · H.264", "1080p60 · VP9", "1080p · H.264"), options.map { it.label })
        val chosen = options.first { it.label == "1080p · H.264" }
        assertEquals("https://g/1080p30avc", youtubePickVideo(formats, chosen.key, 2160, uhdTv)?.url)
        // A key from another video falls back to automatic.
        assertEquals("https://g/2160p60vp9", youtubePickVideo(formats, "v:999:0:vp9", 2160, uhdTv)?.url)
    }

    @Test fun `automatic keeps original even when German dub is listed first`() {
        val germanFirst = formats.sortedBy { if (it.audioTrackId == "de.3") 0 else 1 }
        assertEquals("https://g/en", youtubePickAudio(germanFirst, YOUTUBE_STREAM_AUTO)?.url)
        assertEquals("https://g/de", youtubePickAudio(germanFirst, "a:de.3")?.url)
    }

    @Test fun `audio languages with the original first`() {
        val options = youtubeAudioOptions(formats)
        assertEquals(listOf("a:en.4", "a:de.3"), options.map { it.key })
        assertEquals("English (Original)", youtubeAudioLabel(options[0], Locale.ENGLISH, kinds, "?"))
        assertEquals("Deutsch (synchronisiert)", youtubeAudioLabel(options[1], Locale.GERMAN, kinds, "?"))
        assertEquals("https://g/en", youtubePickAudio(formats, YOUTUBE_STREAM_AUTO)?.url)
        assertEquals("https://g/de", youtubePickAudio(formats, "a:de.3")?.url)
        assertNull(youtubePickAudio(formats.filter { it.mimeType!!.startsWith("video") }, YOUTUBE_STREAM_AUTO))
        // Language from YouTube's colon-separated xtags when no track id is given.
        val tagged = YouTubeAdaptiveFormat("audio/mp4; codecs=\"mp4a.40.2\"", "https://g/fr", xtags = "acont=dubbed-auto:lang=fr-FR")
        assertEquals("fr-FR", youtubeAudioOptions(listOf(tagged)).single().language)
    }

    @Test fun `SmartTube track names without xtags still name the language and the original`() {
        // SABR-derived formats: no xtags, the language is SmartTube's "lang (acont)" with a U+2010 hyphen.
        val sabr = listOf(
            YouTubeAdaptiveFormat("audio/mp4; codecs=\"mp4a.40.2\"", "https://g/de", audioTrackId = "de-DE.3", language = "de\u2010DE (dubbed-auto)"),
            YouTubeAdaptiveFormat("audio/mp4; codecs=\"mp4a.40.2\"", "https://g/en", audioTrackId = "en-US.4", language = "en\u2010US (original)"),
        )
        val options = youtubeAudioOptions(sabr)
        assertEquals(listOf("en-US", "de-DE"), options.map { it.language })
        assertEquals(listOf(true, false), options.map { it.original })
        assertEquals(listOf("Englisch (Original)", "Deutsch (automatisch synchronisiert)"), options.map { youtubeAudioLabel(it, Locale.GERMAN, kinds, "?") })
        assertEquals("https://g/en", youtubePickAudio(sabr, YOUTUBE_STREAM_AUTO)?.url)
        // Media3 lower-cases the MPD's lang attribute.
        assertEquals("de-de" to "dubbed", youtubeParseAudioTrack("de\u2010de (dubbed)"))
        assertEquals("ja" to null, youtubeParseAudioTrack("ja"))
        assertNull(youtubeParseAudioTrack("Audio 1"))
    }

    @Test fun `hdr kind comes from the codec string`() {
        assertEquals("hdr10", youtubeHdrKind("video/webm; codecs=\"vp09.02.51.10.01.09.16.09.00\""))
        assertEquals("hlg", youtubeHdrKind("video/webm; codecs=\"vp09.02.51.10.01.09.18.09.00\""))
        assertEquals("hdr10", youtubeHdrKind("video/webm; codecs=\"vp09.02.51.10\""))
        assertEquals("hdr10", youtubeHdrKind("video/mp4; codecs=\"av01.0.13M.10.0.110.09.16.09.0\""))
        assertNull(youtubeHdrKind("video/webm; codecs=\"vp09.00.51.08\""))
        assertNull(youtubeHdrKind("video/mp4; codecs=\"av01.0.05M.08\""))
        assertNull(youtubeHdrKind("video/mp4; codecs=\"avc1.640028\""))
    }

    @Test fun `hdr only where display and decoder allow it, preferred there`() {
        // SDR devices never see the HDR stream.
        assertEquals(false, youtubeVideoOptions(formats, uhdTv).any { it.hdr != null })
        // HDR TV: offered with its label and preferred over SDR at the same size and frame rate.
        val options = youtubeVideoOptions(formats, hdrTv)
        assertEquals("2160p60 · VP9 · HDR10", options.first().label)
        assertEquals("https://g/2160p60hdr", youtubePickVideo(formats, YOUTUBE_STREAM_AUTO, 2160, hdrTv)?.url)
        // The SDR variant of the same quality stays selectable.
        assertEquals("https://g/2160p60vp9", youtubePickVideo(formats, options[1].key, 2160, hdrTv)?.url)
        // Default check knows nothing about the device: SDR only.
        assertEquals("https://g/2160p60vp9", youtubePickVideo(formats, YOUTUBE_STREAM_AUTO, 2160, youtubeDecodeAnything)?.url)
    }

    @Test fun `vertical shorts are capped by their short side`() {
        val shorts = listOf(
            YouTubeAdaptiveFormat("video/mp4; codecs=\"avc1.640028\"", "https://g/s1080", width = 1080, height = 1920, fps = 30),
            YouTubeAdaptiveFormat("video/mp4; codecs=\"avc1.64001f\"", "https://g/s720", width = 720, height = 1280, fps = 30),
        )
        val portraitOk: YouTubeDecodeCheck = { family, _, _, _, hdr -> family == "avc" && hdr == null }
        assertEquals("https://g/s1080", youtubePickVideo(shorts, YOUTUBE_STREAM_AUTO, 1080, portraitOk)?.url)
        assertEquals(listOf("1080p · H.264", "720p · H.264"), youtubeVideoOptions(shorts, portraitOk).map { it.label })
    }
}
