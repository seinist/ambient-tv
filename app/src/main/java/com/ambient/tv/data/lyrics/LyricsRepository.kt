package com.ambient.tv.data.lyrics

import com.ambient.tv.BuildConfig
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.domain.repository.MediaRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** What the player knows about the playing song. */
data class LyricsRequest(
    val ratingKey: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
)

/**
 * Lyrics from the media server (Plex lyric streams, Jellyfin's lyrics) and, only when the user
 * allows it, from LRCLIB online: that sends artist, title, album and length to lrclib.net.
 */
@Singleton
class LyricsRepository @Inject constructor(
    private val media: MediaRepository,
    private val preferences: AppPreferences,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = object : LinkedHashMap<String, Lyrics?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics?>?) = size > CACHE_SIZE
    }

    suspend fun lyricsFor(request: LyricsRequest): Lyrics? {
        val online = preferences.lyricsOnlineEnabled
        val key = "${request.ratingKey}|$online"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val fromServer = media.getLyrics(request.ratingKey).getOrNull()
        val lyrics = fromServer ?: if (online) runCatching { lrclib(request) }.getOrNull() else null
        synchronized(cache) { cache[key] = lyrics }
        return lyrics
    }

    private suspend fun lrclib(request: LyricsRequest): Lyrics? = withContext(Dispatchers.IO) {
        val artist = request.artist?.takeIf { it.isNotBlank() } ?: return@withContext null
        val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", request.title)
            .addQueryParameter("artist_name", artist)
            .apply {
                request.album?.takeIf { it.isNotBlank() }?.let { addQueryParameter("album_name", it) }
                request.durationMs?.takeIf { it > 0 }?.let { addQueryParameter("duration", (it / 1000).toString()) }
            }
            .build()
        val call = Request.Builder().url(url).header("User-Agent", "AmbientTV/${BuildConfig.VERSION_NAME}").build()
        http.newCall(call).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = json.parseToJsonElement(response.body.string()) as? JsonObject ?: return@withContext null
            lrclibLyrics(body)
        }
    }

    private companion object {
        const val CACHE_SIZE = 40
    }
}

/** Synced lyrics first, plain lyrics otherwise; instrumental tracks have none. */
internal fun lrclibLyrics(body: JsonObject): Lyrics? {
    fun text(name: String) = runCatching { body[name]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }
    return text("syncedLyrics")?.let { parseLyricsText(it, "LRCLIB") }
        ?: text("plainLyrics")?.let { parseLyricsText(it, "LRCLIB") }
}

/** Jellyfin's `/Audio/{id}/Lyrics`: `Lyrics: [{ Text, Start (ticks) }]`. */
internal fun jellyfinLyrics(body: JsonObject): Lyrics? {
    val entries = (body.entries.firstOrNull { it.key.equals("Lyrics", ignoreCase = true) }?.value as? kotlinx.serialization.json.JsonArray)
        ?: return null
    val lines = entries.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        fun field(name: String) = obj.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        val text = runCatching { field("Text")?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()
        val start = runCatching { field("Start")?.jsonPrimitive?.contentOrNull?.toLongOrNull() }.getOrNull()
        LyricLine(start?.let { it / 10_000L }, text.trim())
    }
    if (lines.none { it.text.isNotBlank() }) return null
    val synced = lines.all { it.startMs != null }
    return Lyrics(if (synced) lines.sortedBy { it.startMs } else lines.map { it.copy(startMs = null) }, "Jellyfin")
}
