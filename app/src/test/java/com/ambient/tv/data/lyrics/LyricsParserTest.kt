package com.ambient.tv.data.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParserTest {
    @Test fun lrcWithSeveralStampsTagsAndWordTimes() {
        val lyrics = parseLyricsText(
            """
            [ar:Artist]
            [ti:Song]
            [00:12.50]First line
            [00:20.00][01:05.3]Chorus
            [00:30.123]<00:30.20>Word <00:31.00>by word
            """.trimIndent(), "test",
        )!!
        assertTrue(lyrics.synced)
        assertEquals(listOf(12_500L, 20_000L, 30_123L, 65_300L), lyrics.lines.map { it.startMs })
        assertEquals(listOf("First line", "Chorus", "Word by word", "Chorus"), lyrics.lines.map { it.text })
    }

    @Test fun lrcOffsetShowsLinesEarlier() {
        val lyrics = parseLyricsText("[offset:+500]\n[00:01.00]A\n[00:00.20]B", "test")!!
        assertEquals(listOf(0L, 500L), lyrics.lines.map { it.startMs })
    }

    @Test fun plainTextStaysUntimed() {
        val lyrics = parseLyricsText("\n\nLine one\n\nLine two\n", "test")!!
        assertFalse(lyrics.synced)
        assertEquals(listOf("Line one", "", "Line two"), lyrics.lines.map { it.text })
        assertNull(parseLyricsText("  \n ", "test"))
    }

    @Test fun plexXmlAndJson() {
        val xml = """<MediaContainer><Lyrics><Line startOffset="1000"><Span startOffset="1000" text="Hello &amp; bye"/></Line><Line startOffset="3000"><Span text="Two"/></Line></Lyrics></MediaContainer>"""
        val fromXml = parsePlexLyrics(xml)!!
        assertEquals(listOf(1000L, 3000L), fromXml.lines.map { it.startMs })
        assertEquals("Hello & bye", fromXml.lines.first().text)
        val json = """{"MediaContainer":{"Lyrics":[{"Line":[{"startOffset":500,"Span":[{"text":"A"},{"text":"B"}]},{"Span":[{"text":"C"}]}]}]}}"""
        val fromJson = parsePlexLyrics(json)!!
        assertFalse(fromJson.synced)
        assertEquals(listOf("AB", "C"), fromJson.lines.map { it.text })
    }

    @Test fun jellyfinTicksAndLrclibPreference() {
        val jf = Json.parseToJsonElement("""{"Lyrics":[{"Text":"A","Start":10000000},{"Text":"B","Start":25000000}]}""") as JsonObject
        assertEquals(listOf(1000L, 2500L), jellyfinLyrics(jf)!!.lines.map { it.startMs })
        val lrclib = Json.parseToJsonElement("""{"syncedLyrics":"[00:01.00]Sung","plainLyrics":"Sung"}""") as JsonObject
        assertTrue(lrclibLyrics(lrclib)!!.synced)
        val instrumental = Json.parseToJsonElement("""{"instrumental":true,"syncedLyrics":null,"plainLyrics":null}""") as JsonObject
        assertNull(lrclibLyrics(instrumental))
    }

    @Test fun activeLineFollowsPosition() {
        val lines = listOf(LyricLine(1000, "a"), LyricLine(2000, "b"), LyricLine(3000, "c"))
        assertEquals(-1, activeLyricIndex(lines, 500))
        assertEquals(0, activeLyricIndex(lines, 1000))
        assertEquals(1, activeLyricIndex(lines, 2999))
        assertEquals(2, activeLyricIndex(lines, 99_000))
    }
}
