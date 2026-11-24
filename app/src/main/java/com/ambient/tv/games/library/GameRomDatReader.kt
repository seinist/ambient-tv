package com.ambient.tv.games.library

import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.util.Locale
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * No-Intro/Redump DAT files: names and checksums only, no game data. Reads the XML form and the
 * clrmamepro text form RetroArch's libretro-database uses.
 */
object GameRomDatReader {
    data class Entry(val crc32: String, val title: String)
    private const val MAX_XML_BYTES = 32L * 1024 * 1024
    private const val MAX_ENTRIES = 150_000

    /**
     * Stream entries; the caller owns the InputStream and a transaction. No DTD/DOCTYPES,
     * remote entities, massive in-memory DOM or ROM bytes are accepted.
     */
    fun parse(input: InputStream, receive: (Entry) -> Unit): Int {
        val limited = object : FilterInputStream(input) {
            var total = 0L
            override fun read(): Int {
                val value = super.read()
                if (value >= 0 && ++total > MAX_XML_BYTES) throw IOException("DAT exceeds size limit")
                return value
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val count = super.read(b, off, len)
                if (count > 0 && (total + count).also { total = it } > MAX_XML_BYTES)
                    throw IOException("DAT exceeds size limit")
                return count
            }
        }
        val buffered = BufferedInputStream(limited)
        return if (isXml(buffered)) parseXml(buffered, receive)
            else parseClrMamePro(InputStreamReader(buffered, Charsets.UTF_8).buffered(), receive)
    }

    /** The first visible character decides: `<` for XML, a word such as `clrmamepro` otherwise. */
    private fun isXml(input: BufferedInputStream): Boolean {
        input.mark(4096)
        try {
            repeat(4096) {
                val value = input.read()
                if (value < 0) return true
                if (value == 0xEF || value == 0xBB || value == 0xBF || value.toChar().isWhitespace()) return@repeat
                return value == '<'.code
            }
            return true
        } finally { input.reset() }
    }

    private fun parseXml(input: InputStream, receive: (Entry) -> Unit): Int {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false) }
        parser.setInput(input, "UTF-8")
        var name: String? = null
        var count = 0
        while (true) {
            val event = parser.nextToken()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.DOCDECL) throw IOException("DTD is not allowed in DAT")
            if (event == XmlPullParser.END_TAG && parser.name in listOf("game", "machine")) name = null
            if (event != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "game", "machine" -> {
                    name = parser.getAttributeValue(null, "name")?.take(160)?.trim()
                }
                "rom" -> {
                    val crc = parser.getAttributeValue(null, "crc")
                        ?.trim()?.uppercase(Locale.ROOT) ?: continue
                    if (!crc.matches(Regex("[0-9A-F]{8}"))) continue
                    val title = name?.takeIf { it.isNotBlank() }
                        ?: parser.getAttributeValue(null, "name")?.substringBeforeLast('.')?.trim()
                        ?: continue
                    if (!validTitle(title)) continue
                    receive(Entry(crc, title))
                    if (++count > MAX_ENTRIES) throw IOException("DAT contains too many entries")
                }
            }
        }
        return count
    }

    private fun validTitle(title: String) = title.isNotBlank() && title.length <= 160 && title.none { it.isISOControl() }

    /**
     * `game ( name "…" rom ( name "….gba" size 1 crc ABCD1234 ) )`. Unknown blocks and keys are
     * skipped; each rom of a game gives one entry under the game's name.
     */
    private fun parseClrMamePro(reader: Reader, receive: (Entry) -> Unit): Int {
        val tokens = ClrMameProTokens(reader)
        val blocks = ArrayDeque<String>()
        var gameName: String? = null
        var romName: String? = null
        var romCrc: String? = null
        var count = 0
        var token = tokens.next()
        while (token != null) {
            when (token) {
                ClrMameProTokens.OPEN -> { blocks.addLast(""); token = tokens.next() }
                ClrMameProTokens.CLOSE -> {
                    val closed = blocks.removeLastOrNull()
                    if (closed == "rom" && blocks.lastOrNull() in GAME_BLOCKS) {
                        val crc = romCrc?.trim()?.uppercase(Locale.ROOT)
                        val title = gameName?.trim()?.take(160)?.takeIf(String::isNotBlank)
                            ?: romName?.substringBeforeLast('.')?.trim()
                        if (crc != null && crc.matches(CRC) && title != null && validTitle(title)) {
                            receive(Entry(crc, title))
                            if (++count > MAX_ENTRIES) throw IOException("DAT contains too many entries")
                        }
                        romName = null
                        romCrc = null
                    } else if (closed in GAME_BLOCKS) gameName = null
                    token = tokens.next()
                }
                else -> {
                    val key = token.text.lowercase(Locale.ROOT)
                    val next = tokens.next()
                    token = when {
                        next == ClrMameProTokens.OPEN -> {
                            blocks.addLast(if (token.quoted) "" else key)
                            if (key in GAME_BLOCKS) gameName = null
                            if (key == "rom") { romName = null; romCrc = null }
                            tokens.next()
                        }
                        next == null || next == ClrMameProTokens.CLOSE || token.quoted -> next
                        else -> {
                            when (blocks.lastOrNull()) {
                                in GAME_BLOCKS -> if (key == "name") gameName = next.text
                                "rom" -> when (key) { "name" -> romName = next.text; "crc" -> romCrc = next.text }
                            }
                            tokens.next()
                        }
                    }
                }
            }
        }
        return count
    }

    private val GAME_BLOCKS = setOf("game", "machine")
    private val CRC = Regex("[0-9A-F]{8}")
}

/** Words, quoted strings and parentheses of a clrmamepro DAT; tokens are bounded in length. */
private class ClrMameProTokens(private val reader: Reader) {
    class Token(val text: String, val quoted: Boolean = false)
    private var pending = -2

    private fun read(): Int = if (pending != -2) pending.also { pending = -2 } else reader.read()

    fun next(): Token? {
        var value = read()
        while (value >= 0 && value.toChar().isWhitespace()) value = read()
        if (value < 0) return null
        return when (val char = value.toChar()) {
            '(' -> OPEN
            ')' -> CLOSE
            '"' -> {
                val text = StringBuilder()
                while (true) {
                    val next = read()
                    if (next < 0 || next == '"'.code) break
                    if (text.length >= MAX_TOKEN) throw IOException("DAT value too long")
                    text.append(next.toChar())
                }
                Token(text.toString(), quoted = true)
            }
            else -> {
                val text = StringBuilder().append(char)
                while (true) {
                    val next = read()
                    if (next < 0) break
                    val c = next.toChar()
                    if (c.isWhitespace() || c == '(' || c == ')' || c == '"') { pending = next; break }
                    if (text.length >= MAX_TOKEN) throw IOException("DAT value too long")
                    text.append(c)
                }
                Token(text.toString())
            }
        }
    }

    companion object {
        val OPEN = Token("(")
        val CLOSE = Token(")")
        private const val MAX_TOKEN = 4096
    }
}
