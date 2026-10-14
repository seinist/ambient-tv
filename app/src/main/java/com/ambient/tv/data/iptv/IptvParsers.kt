package com.ambient.tv.data.iptv

import java.io.Reader
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One entry of an M3U playlist. */
data class IptvChannel(
    val uuid: String,
    val name: String,
    val url: String,
    val number: Int?,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    val group: String?,
    val radio: Boolean,
    val headers: Map<String, String>,
)

data class IptvPlaylist(val channels: List<IptvChannel>, val epgUrls: List<String>)

/**
 * Extended M3U as IPTV providers and tools write it: `#EXTINF` attributes (tvg-id, tvg-name,
 * tvg-logo, tvg-chno, group-title, radio), `#EXTGRP`, `#EXTVLCOPT` user agent/referrer and the
 * header's url-tvg / x-tvg-url guide links. Only http(s), rtsp, rtmp and udp/rtp streams are kept.
 */
object M3uParser {
    const val MAX_CHANNELS = 5000
    private val attribute = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val schemes = setOf("http", "https", "rtsp", "rtmp", "rtp", "udp")

    fun parse(text: String): IptvPlaylist {
        val lines = text.removePrefix("\uFEFF").lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val epg = mutableListOf<String>()
        val channels = mutableListOf<IptvChannel>()
        var info: String? = null
        var group: String? = null
        val options = mutableMapOf<String, String>()
        for (line in lines) {
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> attributes(line).let { attrs ->
                    listOfNotNull(attrs["url-tvg"], attrs["x-tvg-url"]).flatMap { it.split(',') }.map(String::trim)
                        .filter { it.startsWith("http://") || it.startsWith("https://") }.forEach(epg::add)
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> { info = line; group = null; options.clear() }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> group = line.substringAfter(':').trim().ifEmpty { null }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substringAfter(':')
                    val key = option.substringBefore('=').trim().lowercase(Locale.ROOT)
                    val value = option.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> options["User-Agent"] = value
                        "http-referrer", "http-referer" -> options["Referer"] = value
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val scheme = line.substringBefore("://", "").lowercase(Locale.ROOT)
                    if (scheme in schemes && channels.size < MAX_CHANNELS) {
                        channels += channel(info, line, group, options.toMap(), channels.size)
                    }
                    info = null; group = null; options.clear()
                }
            }
        }
        return IptvPlaylist(channels, epg.distinct())
    }

    /** key="value" pairs; quoted values may contain commas ("tvg-name="A, B"", "url-tvg="a.xml,b.xml""). */
    private fun attributes(line: String): Map<String, String> =
        attribute.findAll(line).associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2].trim() }

    private fun channel(info: String?, url: String, extGroup: String?, headers: Map<String, String>, index: Int): IptvChannel {
        val attrs = info?.let(::attributes).orEmpty()
        // The display name follows the last comma that is not inside a quoted attribute.
        val name = info?.let { line ->
            var quoted = false; var split = -1
            line.forEachIndexed { i, c -> if (c == '"') quoted = !quoted else if (c == ',' && !quoted) split = i }
            if (split >= 0) line.substring(split + 1).trim() else null
        }?.takeIf(String::isNotEmpty) ?: attrs["tvg-name"]?.takeIf(String::isNotEmpty) ?: url.substringAfterLast('/').substringBefore('?')
        val tvgId = attrs["tvg-id"]?.takeIf(String::isNotEmpty)
        return IptvChannel(
            uuid = "iptv-" + digest(tvgId ?: url).take(16),
            name = name.take(200),
            url = url,
            number = attrs["tvg-chno"]?.toIntOrNull() ?: attrs["channel-number"]?.toIntOrNull(),
            tvgId = tvgId,
            tvgName = attrs["tvg-name"]?.takeIf(String::isNotEmpty),
            logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
            group = (attrs["group-title"] ?: extGroup)?.takeIf(String::isNotEmpty)?.split(';')?.firstOrNull()?.trim(),
            radio = attrs["radio"].equals("true", ignoreCase = true),
            headers = headers,
        ).let { if (it.number == null) it.copy(number = index + 1) else it }
    }

    internal fun digest(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

data class XmltvProgramme(
    val channelId: String,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val startEpochSeconds: Long,
    val stopEpochSeconds: Long,
    val icon: String?,
)

/** Channels as the guide names them: id plus display names, for matching playlist entries. */
data class XmltvChannel(val id: String, val names: List<String>, val icon: String?)

data class XmltvGuide(val channels: List<XmltvChannel>, val programmes: List<XmltvProgramme>)

/**
 * Streaming XMLTV reader. Guides can be hundreds of megabytes, so only programmes that overlap
 * [fromEpoch]..[toEpoch] and belong to an [accept]ed channel are kept, at most [maxProgrammes].
 */
object XmltvParser {
    private val timeFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss[ Z]", Locale.ROOT)

    /** [onChannel] sees each guide channel as it ends; XMLTV lists channels before programmes, so [accept] can use them. */
    fun parse(
        reader: Reader, fromEpoch: Long, toEpoch: Long, accept: (String) -> Boolean = { true }, maxProgrammes: Int = 100_000,
        onChannel: (XmltvChannel) -> Unit = {},
    ): XmltvGuide {
        val xml = MiniXmlReader(reader)
        val channels = mutableListOf<XmltvChannel>()
        val programmes = mutableListOf<XmltvProgramme>()
        var channel: Triple<String, MutableList<String>, String?>? = null
        var programme: MutableMap<String, String?>? = null
        var keep = false
        var field: String? = null
        val text = StringBuilder()
        while (true) {
            val event = xml.next() ?: break
            when (event) {
                is MiniXmlReader.Event.Start -> when (event.name) {
                    "channel" -> channel = Triple(event.attributes["id"].orEmpty(), mutableListOf(), null)
                    "display-name" -> if (channel != null) { field = "display-name"; text.clear() }
                    "programme" -> {
                        val id = event.attributes["channel"].orEmpty()
                        val start = time(event.attributes["start"])
                        val stop = time(event.attributes["stop"]) ?: start?.plus(3600)
                        keep = id.isNotEmpty() && start != null && stop != null && stop > fromEpoch && start < toEpoch && accept(id)
                        programme = if (keep) mutableMapOf("channel" to id, "start" to start.toString(), "stop" to stop.toString()) else null
                    }
                    "title", "sub-title", "desc" -> if (keep && programme?.get(event.name) == null) { field = event.name; text.clear() }
                    "icon" -> {
                        val src = event.attributes["src"]?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                        if (keep && programme != null) programme["icon"] = programme["icon"] ?: src
                        else if (channel != null && channel.third == null) channel = channel.copy(third = src)
                    }
                }
                is MiniXmlReader.Event.Text -> if (field != null && text.length < 4000) text.append(event.text)
                is MiniXmlReader.Event.End -> when (event.name) {
                    "display-name" -> { channel?.second?.add(text.toString().trim()); field = null }
                    "title", "sub-title", "desc" -> { if (field == event.name) programme?.set(event.name, text.toString().trim()); field = null }
                    "channel" -> {
                        channel?.let { (id, names, icon) -> if (id.isNotEmpty()) XmltvChannel(id, names.filter(String::isNotEmpty), icon).also { channels += it; onChannel(it) } }
                        channel = null
                    }
                    "programme" -> {
                        programme?.let { p ->
                            val title = p["title"]?.takeIf(String::isNotEmpty)
                            if (title != null && programmes.size < maxProgrammes) programmes += XmltvProgramme(
                                p.getValue("channel")!!, title, p["sub-title"]?.takeIf(String::isNotEmpty), p["desc"]?.takeIf(String::isNotEmpty),
                                p.getValue("start")!!.toLong(), p.getValue("stop")!!.toLong(), p["icon"],
                            )
                        }
                        programme = null; keep = false
                    }
                }
            }
        }
        return XmltvGuide(channels, programmes)
    }

    /** "20261014060000 +0200"; without an offset the time is UTC, as the format specifies. */
    internal fun time(value: String?): Long? {
        val raw = value?.trim()?.takeIf { it.length >= 12 } ?: return null
        val digits = raw.substringBefore(' ').padEnd(14, '0').take(14)
        val offset = raw.substringAfter(' ', "").trim().takeIf { it.matches(Regex("[+-]\\d{4}")) } ?: "+0000"
        return runCatching { OffsetDateTime.parse("$digits $offset", timeFormat).toEpochSecond() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(digits, DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).toEpochSecond(ZoneOffset.UTC) }.getOrNull()
    }
}

/** Playlist channels to guide channels: tvg-id first, then the channel or tvg name. */
internal class IptvGuideMatcher(channels: List<IptvChannel>) {
    private val byId = channels.filter { it.tvgId != null }.associateBy { it.tvgId!!.lowercase(Locale.ROOT) }
    private val byName = channels.flatMap { c -> listOfNotNull(c.tvgName, c.name).map { normalize(it) to c } }.toMap()

    fun match(guide: XmltvChannel?, id: String): IptvChannel? =
        byId[id.lowercase(Locale.ROOT)] ?: guide?.names?.firstNotNullOfOrNull { byName[normalize(it)] } ?: byName[normalize(id)]

    companion object {
        fun normalize(name: String): String = name.lowercase(Locale.ROOT)
            .replace(Regex("""\b(hd|uhd|fhd|sd|4k)\b"""), "").replace(Regex("[^\\p{L}\\p{N}]"), "")
    }
}

/** Minimal streaming XML tokenizer: no DTDs, no entity definitions (only the five built-ins and char refs). */
internal class MiniXmlReader(reader: Reader) {
    sealed interface Event {
        data class Start(val name: String, val attributes: Map<String, String>) : Event
        data class End(val name: String) : Event
        data class Text(val text: String) : Event
    }

    private val input = java.io.PushbackReader(if (reader is java.io.BufferedReader) reader else java.io.BufferedReader(reader, 64 * 1024), 16)
    private val pending = ArrayDeque<Event>()

    private fun peek(): Int { val c = input.read(); if (c >= 0) input.unread(c); return c }

    /** True (and consumed) when the next characters are exactly [text]; otherwise nothing is consumed. */
    private fun consume(text: String): Boolean {
        val read = StringBuilder()
        for (expected in text) {
            val c = input.read()
            if (c < 0 || c.toChar() != expected) {
                if (c >= 0) input.unread(c)
                for (i in read.indices.reversed()) input.unread(read[i].code)
                return false
            }
            read.append(c.toChar())
        }
        return true
    }

    fun next(): Event? {
        pending.removeFirstOrNull()?.let { return it }
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (c != '<'.code) {
                val text = StringBuilder().append(c.toChar())
                while (true) {
                    val n = peek(); if (n < 0 || n == '<'.code || text.length > 64_000) break
                    text.append(input.read().toChar())
                }
                val decoded = decode(text.toString())
                if (decoded.isNotBlank()) return Event.Text(decoded)
                continue
            }
            when {
                consume("?") -> skipUntil("?>")
                consume("!--") -> skipUntil("-->")
                consume("![CDATA[") -> return Event.Text(readUntil("]]>"))
                consume("!") -> skipDeclaration()
                consume("/") -> return Event.End(localName(readUntil(">").trim()))
                else -> {
                    val raw = readTag()
                    val selfClosing = raw.endsWith("/")
                    val body = raw.removeSuffix("/").trim()
                    val split = body.indexOfFirst { it.isWhitespace() }.let { if (it < 0) body.length else it }
                    val name = localName(body.substring(0, split))
                    val attributes = ATTRIBUTE.findAll(body.substring(split))
                        .associate { localName(it.groupValues[1]) to decode(it.groupValues[3].ifEmpty { it.groupValues[4] }) }
                    if (selfClosing) pending.addLast(Event.End(name))
                    return Event.Start(name, attributes)
                }
            }
        }
    }

    private fun localName(name: String) = name.substringAfter(':').lowercase(Locale.ROOT)

    private fun readTag(): String {
        val out = StringBuilder(); var quote = 0
        while (true) {
            val c = input.read(); if (c < 0) break
            if (quote != 0) { if (c == quote) quote = 0 } else if (c == '"'.code || c == '\''.code) quote = c else if (c == '>'.code) break
            out.append(c.toChar())
            if (out.length > 64_000) throw java.io.IOException("Tag too long")
        }
        return out.toString()
    }

    private fun readUntil(end: String): String {
        val out = StringBuilder()
        while (true) {
            val c = input.read(); if (c < 0) break
            out.append(c.toChar())
            if (out.endsWith(end)) return out.substring(0, out.length - end.length)
            if (out.length > 1_000_000) throw java.io.IOException("Section too long")
        }
        return out.toString()
    }

    private fun skipUntil(end: String) {
        val seen = StringBuilder()
        while (true) {
            val c = input.read(); if (c < 0) return
            seen.append(c.toChar())
            if (seen.length > end.length) seen.deleteCharAt(0)
            if (seen.toString() == end) return
        }
    }

    /** <!DOCTYPE …> including an internal subset in [ … ]; its entities are never expanded. */
    private fun skipDeclaration() {
        var depth = 0; var quote = 0
        while (true) {
            val c = input.read(); if (c < 0) return
            when {
                quote != 0 -> if (c == quote) quote = 0
                c == '"'.code || c == '\''.code -> quote = c
                c == '['.code -> depth++
                c == ']'.code -> depth--
                c == '>'.code && depth <= 0 -> return
            }
        }
    }

    private companion object {
        val ATTRIBUTE = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*("([^"]*)"|'([^']*)')""")
        val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);""")
        fun decode(text: String): String = if ('&' !in text) text else ENTITY.replace(text) { match ->
            when (val e = match.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> runCatching {
                    val code = if (e.startsWith("#x")) e.substring(2).toInt(16) else e.substring(1).toInt()
                    String(Character.toChars(code))
                }.getOrDefault(match.value)
            }
        }
    }
}
