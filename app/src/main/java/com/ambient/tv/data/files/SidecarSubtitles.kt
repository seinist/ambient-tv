package com.ambient.tv.data.files

import java.util.Locale

/** A subtitle file found next to a video ("Movie.de.forced.srt", "Subs/Movie.en.srt"). */
data class SidecarSubtitle(
    /** Path relative to the source root. */
    val path: String,
    val name: String,
    /** "srt", "ass", "ssa" or "vtt" — the codec names the players already map. */
    val codec: String,
    /** ISO 639-1 where recognised ("de", "en", "pt-BR"), else null. */
    val languageCode: String?,
    val forced: Boolean,
    val hearingImpaired: Boolean,
)

/**
 * Kodi-style sidecar subtitle matching. Same folder: files that start with the video's base name.
 * A "Subs"/"Subtitles" folder: the same names, or every subtitle in "Subs/<base name>/" (the layout
 * many releases use, e.g. "Subs/Movie/2_English.srt").
 */
object SidecarSubtitles {
    val extensions = setOf("srt", "ass", "ssa", "vtt")
    val folderNames = setOf("subs", "subtitles", "untertitel")
    const val MAX_TRACKS = 12
    /** Larger files are not subtitles (or are image subtitles the bridge cannot pair). */
    const val MAX_BYTES = 8L * 1024 * 1024

    fun isSubtitle(entry: FileEntry): Boolean =
        !entry.directory && FilePaths.extension(entry.name) in extensions && entry.size in 0..MAX_BYTES

    fun baseName(videoName: String): String = videoName.substringBeforeLast('.', videoName)

    /** Subtitles among [siblings] (same folder or a Subs folder) that belong to [videoName]. */
    fun match(videoName: String, siblings: List<FileEntry>, wholeFolder: Boolean = false): List<SidecarSubtitle> {
        val base = baseName(videoName)
        return siblings.filter(::isSubtitle).mapNotNull { entry ->
            val stem = entry.name.substringBeforeLast('.')
            val tail = when {
                stem.equals(base, ignoreCase = true) -> ""
                stem.length > base.length && stem.startsWith(base, ignoreCase = true) &&
                    stem[base.length] in SEPARATORS -> stem.substring(base.length + 1)
                wholeFolder -> stem
                else -> return@mapNotNull null
            }
            val tokens = tail.split('.', '_', ' ', '-', '[', ']', '(', ')').map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotEmpty)
            SidecarSubtitle(
                path = entry.path,
                name = entry.name,
                codec = FilePaths.extension(entry.name),
                languageCode = languageFrom(tail, tokens),
                forced = tokens.any { it == "forced" || it == "foreign" || it == "erzwungen" },
                hearingImpaired = tokens.any { it == "sdh" || it == "cc" || it == "hi" || it == "hoh" },
            )
        }.sortedWith(compareBy<SidecarSubtitle> { it.languageCode == null }.thenBy { it.forced }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .take(MAX_TRACKS)
    }

    /** "pt-BR" first, then the last token that names a language ("Movie.2020.de" is German, not "2020"). */
    internal fun languageFrom(tail: String, tokens: List<String>): String? {
        // Region only in its usual upper-case spelling, so "de-cc" stays German with hearing-impaired marks.
        Regex("""(?:^|[._ -])([a-zA-Z]{2})-([A-Z]{2})(?:$|[._ -])""").find(tail)?.let { match ->
            val language = match.groupValues[1].lowercase(Locale.ROOT)
            val region = match.groupValues[2]
            if (language in ISO2 && region !in setOf("CC", "HI", "SD")) return "$language-$region"
        }
        return tokens.asReversed().firstNotNullOfOrNull(::language)
    }

    private fun language(token: String): String? = when {
        token.length == 2 && token in ISO2 && token !in AMBIGUOUS -> token
        token.length == 3 && ISO3[token] != null -> ISO3[token]
        token.length > 3 -> NAMES[token]
        else -> null
    }

    private val SEPARATORS = setOf('.', '_', ' ', '-')
    private val ISO2: Set<String> = Locale.getISOLanguages().toSet()
    /** Two-letter tokens that are words in release names more often than languages. */
    private val AMBIGUOUS = setOf("hi", "cc", "it", "is", "no", "or", "to", "as", "be", "me", "my", "so", "an", "am")
    private val ISO3: Map<String, String> = buildMap {
        for (code in Locale.getISOLanguages()) {
            val locale = Locale.forLanguageTag(code)
            runCatching { locale.isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { put(it, code) }
        }
        // ISO 639-2/B codes that differ from the /T codes Java reports.
        mapOf("ger" to "de", "fre" to "fr", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el",
            "per" to "fa", "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu",
            "bur" to "my", "geo" to "ka", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms",
            "tib" to "bo", "wel" to "cy").forEach { (k, v) -> put(k, v) }
        remove("sub"); remove("for")
    }
    private val NAMES: Map<String, String> = buildMap {
        for (code in Locale.getISOLanguages()) {
            val locale = Locale.forLanguageTag(code)
            for (display in listOf(Locale.ENGLISH, Locale.GERMAN, locale)) {
                locale.getDisplayLanguage(display).lowercase(Locale.ROOT).takeIf { it.length > 3 && it != code }?.let { putIfAbsent(it, code) }
            }
        }
    }
}

/**
 * Subtitle text as UTF-8. Many SRT files, German ones especially, are Windows-1252; Media3 and VLC
 * read side-loaded text as UTF-8 and would show broken umlauts. BOMs (UTF-8/UTF-16) are honoured,
 * valid UTF-8 stays as is, anything else is read as Windows-1252.
 */
object SubtitleText {
    fun utf8(bytes: ByteArray): ByteArray {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return bytes.copyOfRange(3, bytes.size)
        if (bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())))
            return String(bytes, Charsets.UTF_16).toByteArray(Charsets.UTF_8)
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        if (runCatching { strict.decode(java.nio.ByteBuffer.wrap(bytes)) }.isSuccess) return bytes
        return String(bytes, java.nio.charset.Charset.forName("windows-1252")).toByteArray(Charsets.UTF_8)
    }

    /** Reads a whole (small) file from [media] and serves it converted, from memory. */
    fun convertedMedia(media: SeekableMedia, limit: Long = SidecarSubtitles.MAX_BYTES): SeekableMedia = media.use { source ->
        if (source.size > limit) throw java.io.IOException("Subtitle too large")
        val raw = ByteArray(source.size.toInt()); var done = 0
        val part = ByteArray(64 * 1024)
        while (done < raw.size) {
            val n = source.read(done.toLong(), part, minOf(part.size, raw.size - done))
            if (n <= 0) break
            System.arraycopy(part, 0, raw, done, n); done += n
        }
        val text = utf8(raw.copyOf(done))
        object : SeekableMedia {
            override val size = text.size.toLong()
            override fun read(position: Long, buffer: ByteArray, count: Int): Int {
                if (position >= text.size) return -1
                val n = minOf(count.toLong(), text.size - position).toInt()
                System.arraycopy(text, position.toInt(), buffer, 0, n)
                return n
            }
            override fun close() = Unit
        }
    }
}

/** Parent folder of [path] for listings; SAF document ids at the top level have no '/'. */
internal fun parentFolder(source: FileSource, path: String, root: () -> String): String =
    if ('/' in path) path.substringBeforeLast('/') else if (source.kind == FileKinds.LOCAL) root() else ""
