package com.ambient.tv.spotify

import com.ambient.tv.BuildConfig
import com.ambient.tv.data.emby.EmbyRepository
import com.ambient.tv.data.jellyfin.JellyfinRepository
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.repository.MediaRepository
import com.ambient.tv.server.EmbyConnectionManager
import com.ambient.tv.server.JellyfinConnectionManager
import com.ambient.tv.server.ServerConnectionManager
import com.ambient.tv.server.ServerConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A wide background for a Spotify artist page. Spotify's Web API only has square images, so:
 * 1. the same artist in a connected Plex/Jellyfin/Emby music library (its own backdrop),
 * 2. fanart.tv's artist background via MusicBrainz, only with the user's own fanart.tv API key.
 * Servers that are not online are never contacted here (no connect, no wake).
 */
@Singleton
class ArtistBackdrops @Inject constructor(
    private val plex: MediaRepository,
    private val plexConnection: ServerConnectionManager,
    private val jellyfin: JellyfinRepository,
    private val jellyfinConnection: JellyfinConnectionManager,
    private val emby: EmbyRepository,
    private val embyConnection: EmbyConnectionManager,
    private val tokens: com.ambient.tv.data.local.TokenStore,
    @com.ambient.tv.di.SharedOkHttp connectionPool: okhttp3.ConnectionPool,
    @com.ambient.tv.di.SharedOkHttp dispatcher: okhttp3.Dispatcher,
) {
    private val client = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    /** Per artist name for this process; null results are remembered too (no repeated lookups). */
    private val found = java.util.concurrent.ConcurrentHashMap<String, Result<String?>>()

    suspend fun find(artist: String): String? {
        val key = artistKey(artist).takeIf(String::isNotBlank) ?: return null
        found[key]?.let { return it.getOrNull() }
        val result = withTimeoutOrNull(12_000L) { fromServers(artist) ?: fromFanart(artist) }
        found[key] = Result.success(result)
        return result
    }

    private suspend fun fromServers(artist: String): String? {
        suspend fun search(online: Boolean, sections: suspend () -> List<String>, query: suspend (List<String>) -> List<MediaItem>): String? {
            if (!online) return null
            val keys = runCatching { sections() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { query(keys) }.getOrNull()
                ?.firstOrNull { it.type == "artist" && artistKey(it.title) == artistKey(artist) }
                ?.wideBackdropUrl
        }
        fun musicKeys(sections: List<com.ambient.tv.domain.model.LibrarySection>) =
            sections.filter { it.type in MUSIC_SECTION_TYPES }.map { it.key }
        return search(plexConnection.state.value is ServerConnectionState.Online,
            { musicKeys(plex.getLibrarySections().getOrThrow()) }, { plex.search(it, artist).getOrThrow() })
            ?: search(jellyfinConnection.state.value is ServerConnectionState.Online,
                { musicKeys(jellyfin.getLibrarySections().getOrThrow()) }, { jellyfin.search(it, artist).getOrThrow() })
            ?: search(embyConnection.state.value is ServerConnectionState.Online,
                { musicKeys(emby.getLibrarySections().getOrThrow()) }, { emby.search(it, artist).getOrThrow() })
    }

    private suspend fun fromFanart(artist: String): String? {
        val apiKey = tokens.fanartApiKey?.takeIf(String::isNotBlank) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val mbid = musicBrainzId(artist) ?: return@runCatching null
                val url = "https://webservice.fanart.tv/v3/music/$mbid".toHttpUrl().newBuilder()
                    .addQueryParameter("api_key", apiKey).build()
                val body = get(url.toString()) ?: return@runCatching null
                json.parseToJsonElement(body).jsonObject["artistbackground"]?.jsonArray
                    ?.firstNotNullOfOrNull { it.jsonObject["url"]?.jsonPrimitive?.contentOrNull }
            }.getOrNull()
        }
    }

    /** The best MusicBrainz match with an exact (case-insensitive) name and a high score. */
    private fun musicBrainzId(artist: String): String? {
        val url = "https://musicbrainz.org/ws/2/artist/".toHttpUrl().newBuilder()
            .addQueryParameter("query", "artist:\"${artist.replace("\"", "")}\"")
            .addQueryParameter("fmt", "json")
            .addQueryParameter("limit", "5")
            .build()
        val body = get(url.toString()) ?: return null
        val artists = json.parseToJsonElement(body).jsonObject["artists"]?.jsonArray ?: return null
        return artists.map { it.jsonObject }.firstOrNull { candidate: JsonObject ->
            val name = candidate["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val score = candidate["score"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            score >= 90 && artistKey(name) == artistKey(artist)
        }?.get("id")?.jsonPrimitive?.contentOrNull
    }

    private fun get(url: String): String? {
        // MusicBrainz requires an identifying User-Agent; no personal data is sent.
        val request = Request.Builder().url(url)
            .header("User-Agent", "AmbientTV/${BuildConfig.VERSION_NAME} (https://github.com/seinist/ambient-tv)")
            .build()
        return client.newCall(request).execute().use { response -> if (response.isSuccessful) response.body.string() else null }
    }

    private companion object {
        val MUSIC_SECTION_TYPES = setOf("artist", "music", "musicartist")
        fun artistKey(name: String): String = name.trim().lowercase(Locale.ROOT)
    }
}
