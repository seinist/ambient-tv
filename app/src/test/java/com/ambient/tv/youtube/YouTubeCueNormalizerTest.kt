@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.youtube

import android.text.Layout
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class YouTubeCueNormalizerTest {
    private val vtt = Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build()

    private fun parse(body: String): List<Cue> {
        val parser = YouTubeSubtitleParserFactory().create(vtt)
        val out = mutableListOf<CuesWithTiming>()
        val bytes = body.toByteArray()
        parser.parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues()) { out += it }
        return out.flatMap { it.cues }
    }

    @Test
    fun automaticCaptionCueIsCentered() {
        val cue = parse("WEBVTT\nKind: captions\nLanguage: en\n\n00:00:00.160 --> 00:00:02.869 align:start position:0%\nhello world\n").single()
        assertEquals(Layout.Alignment.ALIGN_CENTER, cue.textAlignment)
        assertEquals(0.5f, cue.position)
        assertEquals(Cue.ANCHOR_TYPE_MIDDLE, cue.positionAnchor)
        assertEquals(Cue.DIMEN_UNSET, cue.size)
    }

    @Test
    fun manualCaptionCueIsUnchanged() {
        val cue = parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nmanual\n").single()
        assertEquals(Layout.Alignment.ALIGN_CENTER, cue.textAlignment)
        assertEquals(0.5f, cue.position)
    }

    @Test
    fun otherPositionsAreKept() {
        val end = parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000 align:end\nright\n").single()
        assertEquals(Layout.Alignment.ALIGN_OPPOSITE, end.textAlignment)
        val inset = parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000 align:start position:80%\ninset\n").single()
        assertEquals(Layout.Alignment.ALIGN_NORMAL, inset.textAlignment)
        assertEquals(0.8f, inset.position, 0.0001f)
    }

    @Test
    fun lineIsKept() {
        val cue = parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000 align:start position:0% line:10%\ntop\n").single()
        assertEquals(Layout.Alignment.ALIGN_CENTER, cue.textAlignment)
        assertEquals(0.1f, cue.line, 0.0001f)
        assertEquals(Cue.LINE_TYPE_FRACTION, cue.lineType)
    }

    @Test
    fun cuesWithoutLeftEdgeAreReturnedAsIs() {
        val cue = Cue.Builder().setText("x").setTextAlignment(Layout.Alignment.ALIGN_CENTER).build()
        val cues = CuesWithTiming(listOf(cue), 0L, 1_000L)
        assertSame(cues, YouTubeCueNormalizer.normalize(cues))
        assertTrue(!YouTubeCueNormalizer.isLeftEdge(cue))
    }
}
