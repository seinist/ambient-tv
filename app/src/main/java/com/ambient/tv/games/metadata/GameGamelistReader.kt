package com.ambient.tv.games.metadata

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class GameGamelistEntry(
    val path: String,
    val metadata: GameMetadata,
    val cover: String?,
    val backdrop: String?,
)

/** Streaming EmulationStation/Batocera/ES-DE text import; XML cannot fetch other resources. */
object GameGamelistReader {
    const val MAX_BYTES = 8L * 1024 * 1024
    private val fields = setOf("path", "name", "desc", "releasedate", "genre", "developer", "publisher",
        "players", "region", "image", "thumbnail", "fanart", "screenshot")

    suspend fun parse(input: InputStream, receive: suspend (GameGamelistEntry) -> Unit): Int {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false) }
        parser.setInput(object : FilterInputStream(input) {
            var bytes = 0L
            override fun read(): Int = super.read().also { if (it >= 0 && ++bytes > MAX_BYTES) tooLarge() }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also {
                if (it > 0) { bytes += it; if (bytes > MAX_BYTES) tooLarge() }
            }
            fun tooLarge(): Nothing = throw IOException("Game metadata XML exceeds 8 MiB")
        }, null)
        var depth = 0
        var gameDepth = -1
        var field: String? = null
        val values = mutableMapOf<String, StringBuilder>()
        var entries = 0
        var tokens = 0
        while (true) {
            if (++tokens % 128 == 0) currentCoroutineContext().ensureActive()
            when (parser.nextToken()) {
                XmlPullParser.END_DOCUMENT -> return entries
                XmlPullParser.DOCDECL -> throw IOException("DTD is not supported in game metadata XML")
                XmlPullParser.START_TAG -> {
                    if (++depth > 32) throw IOException("Game metadata XML is nested too deeply")
                    if (parser.name == "game" && gameDepth < 0) {
                        gameDepth = depth
                        values.clear()
                    } else if (gameDepth >= 0 && depth == gameDepth + 1 && parser.name in fields) {
                        field = parser.name
                        values.putIfAbsent(parser.name, StringBuilder())
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                    field?.takeIf { depth == gameDepth + 1 }?.let { name ->
                        val text = parser.text.orEmpty()
                        val out = values.getValue(name)
                        if (out.length + text.length > if (name == "desc") 16_000 else 4096)
                            throw IOException("Game metadata field is too long")
                        out.append(text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (depth == gameDepth && parser.name == "game") {
                        fun value(name: String) = values[name]?.toString()?.trim().orEmpty()
                        val path = value("path")
                        if (path.isNotBlank()) {
                            if (++entries > 50_000) throw IOException("Game metadata XML contains too many entries")
                            receive(GameGamelistEntry(path, GameMetadata(
                                title = value("name"), summary = value("desc"),
                                year = value("releasedate").take(4).toIntOrNull(),
                                genres = value("genre").split(Regex("\\s*[,;/|]\\s*")).filter(String::isNotBlank),
                                developer = value("developer"), publisher = value("publisher"),
                                players = value("players"), region = value("region"), provider = "local",
                                canonicalTitle = value("name"),
                            ).bounded(), value("image").ifBlank { value("thumbnail") }.takeIf(String::isNotBlank),
                                value("fanart").ifBlank { value("screenshot") }.takeIf(String::isNotBlank)))
                        }
                        gameDepth = -1
                        field = null
                    } else if (depth == gameDepth + 1) field = null
                    depth--
                }
            }
        }
    }
}

/** Media references are resolved below the selected folder, never by an arbitrary URL/path. */
internal fun gameSidecarRelativePath(path: String): String? {
    val normalized = path.trim().replace('\\', '/')
    if (normalized.isBlank() || normalized.startsWith('/') || ':' in normalized ||
        normalized.length > 4096 || normalized.any { it.isISOControl() }) return null
    val parts = normalized.split('/').filter { it.isNotEmpty() && it != "." }
    if (parts.isEmpty() || parts.any { it == ".." }) return null
    return parts.joinToString("/")
}
