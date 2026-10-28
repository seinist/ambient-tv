package com.ambient.tv.data.lyrics

/** One line of a song's lyrics; [startMs] is null for lyrics without timing. */
data class LyricLine(val startMs: Long?, val text: String)

data class Lyrics(val lines: List<LyricLine>, val source: String) {
    /** Every line has a time: the current line follows playback. */
    val synced: Boolean get() = lines.isNotEmpty() && lines.all { it.startMs != null }
}

private val LEADING_TIME = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
private val ID_TAG = Regex("""^\[([a-zA-Z#]+):(.*)]$""")
private val WORD_TIME = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

/**
 * LRC (with several stamps per line, `[offset:]` and enhanced word stamps), plain text, or the
 * XML Plex serves for its lyric streams. Null when nothing readable is left.
 */
fun parseLyricsText(raw: String, source: String): Lyrics? {
    val text = raw.removePrefix("﻿")
    if (text.trimStart().startsWith("<")) return parsePlexLyricsXml(text, source)
    var offsetMs = 0L
    val timed = mutableListOf<LyricLine>()
    val plain = mutableListOf<String>()
    text.lineSequence().forEach { original ->
        var line = original.trim()
        val stamps = mutableListOf<Long>()
        while (true) {
            val m = LEADING_TIME.find(line) ?: break
            val (min, sec, frac) = m.destructured
            stamps += min.toLong() * 60_000 + sec.toLong() * 1_000 + (frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0L)
            line = line.substring(m.range.last + 1)
        }
        if (stamps.isEmpty()) {
            val tag = ID_TAG.matchEntire(line)
            if (tag != null) {
                if (tag.groupValues[1].equals("offset", ignoreCase = true)) offsetMs = tag.groupValues[2].trim().toLongOrNull() ?: 0L
                return@forEach
            }
            plain += line
        } else {
            val words = line.replace(WORD_TIME, "").replace(Regex("\\s+"), " ").trim()
            stamps.forEach { timed += LyricLine(it, words) }
        }
    }
    if (timed.isNotEmpty()) {
        // A positive offset shows the lines earlier.
        val lines = timed.map { it.copy(startMs = (it.startMs!! - offsetMs).coerceAtLeast(0L)) }.sortedBy { it.startMs }
        return Lyrics(lines, source).takeIf { lines.any { it.text.isNotBlank() } }
    }
    val lines = plain.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
    return lines.takeIf { it.isNotEmpty() }?.let { Lyrics(it.map { line -> LyricLine(null, line) }, source) }
}

private val XML_LINE = Regex("""<Line\b([^>]*?)(?:/>|>(.*?)</Line>)""", RegexOption.DOT_MATCHES_ALL)
private val XML_SPAN_TEXT = Regex("""<Span\b[^>]*?\btext="([^"]*)"""")
private val XML_START = Regex("""\bstartOffset="(\d+)"""")

/** Plex lyric XML: `<Line startOffset="…"><Span text="…"/></Line>`; untimed lines have no offset. */
internal fun parsePlexLyricsXml(xml: String, source: String): Lyrics? {
    val lines = XML_LINE.findAll(xml).map { m ->
        val start = XML_START.find(m.groupValues[1])?.groupValues?.get(1)?.toLongOrNull()
        val text = XML_SPAN_TEXT.findAll(m.groupValues[2]).joinToString("") { unescapeXml(it.groupValues[1]) }.trim()
        LyricLine(start, text)
    }.toList()
    if (lines.none { it.text.isNotBlank() }) return null
    // Mixed timing is treated as untimed so the view never jumps around.
    val synced = lines.all { it.startMs != null }
    return Lyrics(if (synced) lines.sortedBy { it.startMs } else lines.map { it.copy(startMs = null) }, source)
}

private fun unescapeXml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
    .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")

/** A Plex lyric stream: the file itself (LRC/text), or timed lines as XML or JSON. */
fun parsePlexLyrics(raw: String): Lyrics? {
    val text = raw.trimStart().removePrefix("\uFEFF")
    if (text.startsWith("{")) return runCatching { parsePlexLyricsJson(text) }.getOrNull()
    return parseLyricsText(text, "Plex")
}

/** `MediaContainer.Lyrics[].Line[]` with `startOffset` and `Span[].text`. */
internal fun parsePlexLyricsJson(raw: String): Lyrics? {
    val root = kotlinx.serialization.json.Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject ?: return null
    fun kotlinx.serialization.json.JsonElement?.array() = this as? kotlinx.serialization.json.JsonArray
    fun kotlinx.serialization.json.JsonElement?.obj() = this as? kotlinx.serialization.json.JsonObject
    fun kotlinx.serialization.json.JsonElement?.string() = (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
    val container = root["MediaContainer"].obj() ?: root
    val lines = container["Lyrics"].array().orEmpty().flatMap { it.obj()?.get("Line").array().orEmpty() }.map { line ->
        val o = line.obj()
        val start = o?.get("startOffset").string()?.toLongOrNull()
        val text = o?.get("Span").array().orEmpty().joinToString("") { it.obj()?.get("text").string().orEmpty() }.trim()
        LyricLine(start, text)
    }
    if (lines.none { it.text.isNotBlank() }) return null
    val synced = lines.all { it.startMs != null }
    return Lyrics(if (synced) lines.sortedBy { it.startMs } else lines.map { it.copy(startMs = null) }, "Plex")
}

/** The line sung at [positionMs]: the last one that started; -1 before the first. */
fun activeLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    var low = 0
    var high = lines.lastIndex
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if ((lines[mid].startMs ?: Long.MAX_VALUE) <= positionMs) { found = mid; low = mid + 1 } else high = mid - 1
    }
    return found
}
