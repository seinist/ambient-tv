package com.ambient.tv.games.library

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** User-supplied No-Intro/Redump style XML DAT file. No copyrighted game data bundled. */
object GameRomDatReader {
    data class Entry(val crc32: String, val title: String)
    private const val MAX_XML_BYTES = 32L * 1024 * 1024
    private const val MAX_ENTRIES = 150_000

    /**
     * Stream entries; the caller owns the InputStream and a transaction. No DTD/DOCTYPES,
     * remote entities, massive in-memory DOM or ROM bytes are accepted.
     */
    fun parse(input: InputStream, receive: (Entry) -> Unit): Int {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false) }
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
        parser.setInput(limited, "UTF-8")
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
                    if (title.isBlank() || title.length > 160 || title.any { it.isISOControl() }) continue
                    receive(Entry(crc, title))
                    if (++count > MAX_ENTRIES) throw IOException("DAT contains too many entries")
                }
            }
        }
        return count
    }
}
