package com.ambient.tv.spotify

import com.ambient.tv.util.suspendResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

class SpotifyApiException(
    val statusCode: Int,
    val responseBody: String?,
    val retryAfterSeconds: Long?,
    message: String,
) : IOException(message)

@Singleton
class SpotifyWebApi @Inject constructor(
    @com.ambient.tv.di.SharedOkHttp connectionPool: okhttp3.ConnectionPool,
    @com.ambient.tv.di.SharedOkHttp dispatcher: okhttp3.Dispatcher,
) {
    internal companion object { const val SEARCH_LIMIT = 10 }
    /** Also used by [SpotifyAuthManager]; both share the app's connection pool and threads. */
    internal val client = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor(com.ambient.tv.diagnostics.DiagnosticHttpInterceptor).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun home(token: String): List<SpotifyBrowseItem> = withContext(Dispatchers.IO) {
        coroutineScope {
            val results = listOf(
                async { suspendResult {
                    get(token, "/me/playlists?limit=20")["items"]?.jsonArray.orEmpty()
                        .mapNotNull { (it as? JsonObject)?.let(::playlistItem) }
                } },
                async { suspendResult {
                    get(token, "/me/albums?limit=20")["items"]?.jsonArray.orEmpty()
                        .mapNotNull { (it.jsonObject["album"] as? JsonObject)?.let(::albumItem) }
                } },
                async { suspendResult {
                    get(token, "/me/tracks?limit=20")["items"]?.jsonArray.orEmpty()
                        .mapNotNull { (it.jsonObject["track"] as? JsonObject)?.let(::trackItem) }
                } },
            ).awaitAll()
            // An offline/unauthorized account must not masquerade as an empty library.
            if (results.all { it.isFailure }) throw requireNotNull(results.first().exceptionOrNull())
            results.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.type to it.id }
        }
    }

    suspend fun librarySection(token: String, section: String): List<SpotifyBrowseItem> = withContext(Dispatchers.IO) {
        when (section) {
            "liked" -> loadSimplePages(token, "/me/tracks?limit=50") { page ->
                page["items"]?.jsonArray.orEmpty().mapNotNull { e -> e.jsonObject["track"]?.let { t -> if (t is kotlinx.serialization.json.JsonNull) null else trackItem(t.jsonObject) } }
            }
            "playlists" -> loadSimplePages(token, "/me/playlists?limit=50") { page ->
                page["items"]?.jsonArray.orEmpty().mapNotNull { e -> if (e is kotlinx.serialization.json.JsonNull) null else playlistItem(e.jsonObject) }
            }
            "albums" -> loadSimplePages(token, "/me/albums?limit=50") { page ->
                page["items"]?.jsonArray.orEmpty().mapNotNull { e -> e.jsonObject["album"]?.let { a -> if (a is kotlinx.serialization.json.JsonNull) null else albumItem(a.jsonObject) } }
            }
            "artists" -> loadArtistFollowingPages(token)
            else -> home(token)
        }
    }

    private suspend fun loadSimplePages(
        token: String,
        firstPath: String,
        parse: (JsonObject) -> List<SpotifyBrowseItem>,
    ): List<SpotifyBrowseItem> {
        val result = mutableListOf<SpotifyBrowseItem>()
        val visited = mutableSetOf<String>()
        var next: String? = firstPath
        while (!next.isNullOrBlank() && visited.add(next)) {
            currentCoroutineContext().ensureActive()
            val page = if (next.startsWith("http")) getUrl(token, next) else get(token, next)
            result += parse(page)
            next = page.str("next")
        }
        return result
    }

    private suspend fun loadArtistFollowingPages(token: String): List<SpotifyBrowseItem> {
        val result = mutableListOf<SpotifyBrowseItem>()
        val visited = mutableSetOf<String>()
        var next: String? = "/me/following?type=artist&limit=50"
        while (!next.isNullOrBlank() && visited.add(next)) {
            currentCoroutineContext().ensureActive()
            val root = if (next.startsWith("http")) getUrl(token, next) else get(token, next)
            val artists = root["artists"]?.jsonObject ?: break
            result += artists["items"]?.jsonArray.orEmpty().mapNotNull { artistItem(it.jsonObject) }
            next = artists.str("next")
        }
        return result
    }

    suspend fun search(token: String, query: String): List<SpotifyBrowseItem> = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val root = get(token, "/search?q=$encoded&type=track,album,artist,playlist&limit=$SEARCH_LIMIT")
        buildList {
            root["tracks"]?.jsonObject?.get("items")?.jsonArray?.forEach { element -> trackItem(element.jsonObject)?.let { item -> add(item) } }
            root["albums"]?.jsonObject?.get("items")?.jsonArray?.forEach { element -> albumItem(element.jsonObject)?.let { item -> add(item) } }
            root["artists"]?.jsonObject?.get("items")?.jsonArray?.forEach { element -> artistItem(element.jsonObject)?.let { item -> add(item) } }
            root["playlists"]?.jsonObject?.get("items")?.jsonArray?.forEach { element -> if (element !is kotlinx.serialization.json.JsonNull) playlistItem(element.jsonObject)?.let { item -> add(item) } }
        }
    }

    suspend fun profile(token: String): SpotifyAccount = withContext(Dispatchers.IO) {
        val root = get(token, "/me")
        SpotifyAccount(
            id = root.str("id").orEmpty(),
            displayName = root.str("display_name"),
            product = root.str("product"),
            images = parseImages(root["images"]?.jsonArray),
        )
    }

    suspend fun album(token: String, id: String): SpotifyAlbum = withContext(Dispatchers.IO) {
        val root = get(token, "/albums/$id")
        val base = parseAlbum(root)
        val tracksPage = root["tracks"] as? JsonObject
        val allTracks = mutableListOf<SpotifyTrack>().apply { addAll(base.tracks) }
        val visited = mutableSetOf<String>()
        var next = tracksPage?.str("next")
        while (!next.isNullOrBlank() && visited.add(next)) {
            currentCoroutineContext().ensureActive()
            val page = getUrl(token, next)
            allTracks += page["items"]?.jsonArray.orEmpty().mapNotNull { runCatching { parseTrack(it.jsonObject) }.getOrNull() }
            next = page.str("next")
        }
        base.copy(tracks = allTracks)
    }
    suspend fun playlist(token: String, id: String): SpotifyPlaylist = withContext(Dispatchers.IO) {
        val root = get(token, "/playlists/$id")
        // Since February 2026 playlist contents live under /playlists/{id}/items.
        // Fetch every page for owned/collaborative playlists; for playlists where Spotify
        // withholds items, keep the metadata page usable instead of failing the screen.
        val tracks = suspendResult { loadPlaylistItems(token, id) }
            .getOrElse { parsePlaylistItems(root["items"] as? JsonObject) }
        parsePlaylist(root).copy(tracks = tracks)
    }

    private suspend fun loadPlaylistItems(token: String, id: String): List<SpotifyTrack> {
        val result = mutableListOf<SpotifyTrack>()
        val visited = mutableSetOf<String>()
        var nextPath: String? = "/playlists/$id/items?limit=50"
        while (!nextPath.isNullOrBlank() && visited.add(nextPath)) {
            currentCoroutineContext().ensureActive()
            val page = if (nextPath.startsWith("http")) getUrl(token, nextPath) else get(token, nextPath)
            result += parsePlaylistItems(page)
            nextPath = page.str("next")
        }
        return result
    }

    suspend fun artist(token: String, id: String): SpotifyArtistDetail = withContext(Dispatchers.IO) {
        val artistRoot = get(token, "/artists/$id")
        val albums = runCatching {
            get(token, "/artists/$id/albums?include_groups=album,single&limit=30")["items"]
                ?.jsonArray?.map { parseAlbum(it.jsonObject) }.orEmpty()
        }.getOrDefault(emptyList())
        // Spotify removed GET /artists/{id}/top-tracks in February 2026. Use the supported
        // Search endpoint as a best-effort replacement and keep only tracks that actually
        // contain this artist id. The page remains useful even if Search is unavailable.
        val artistName = artistRoot.str("name").orEmpty()
        val tracks = runCatching {
            val q = java.net.URLEncoder.encode("artist:\"$artistName\"", "UTF-8")
            get(token, "/search?q=$q&type=track&limit=$SEARCH_LIMIT")["tracks"]?.jsonObject?.get("items")?.jsonArray
                ?.mapNotNull { row -> runCatching { parseTrack(row.jsonObject) }.getOrNull() }
                ?.filter { track -> track.artists.any { it.id == id } }
                .orEmpty()
        }.getOrDefault(emptyList())
        SpotifyArtistDetail(artist = parseArtist(artistRoot), topTracks = tracks, albums = albums)
    }

    suspend fun resolvePlaybackDeviceId(token: String, preferredId: String?, preferredName: String): String? = withContext(Dispatchers.IO) {
        val devices = get(token, "/me/player/devices")["devices"]?.jsonArray.orEmpty().mapNotNull { element ->
            if (element is kotlinx.serialization.json.JsonNull) null else element.jsonObject
        }.filterNot { it.bool("is_restricted") }
        val candidates = devices.mapNotNull { device ->
            val id = device.str("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            id to device.str("name").orEmpty()
        }
        pickSpotifyPlaybackDevice(candidates, preferredId, preferredName)
    }

    suspend fun startPlayback(token: String, deviceId: String?, uri: String, offsetUri: String? = null) = withContext(Dispatchers.IO) {
        val isContext = uri.startsWith("spotify:album:") || uri.startsWith("spotify:playlist:") || uri.startsWith("spotify:artist:")
        val payload = if (isContext) {
            JsonObject(buildMap {
                put("context_uri", JsonPrimitive(uri))
                offsetUri?.let { put("offset", JsonObject(mapOf("uri" to JsonPrimitive(it)))) }
            }).toString()
        } else {
            JsonObject(mapOf("uris" to JsonArray(listOf(JsonPrimitive(uri))))).toString()
        }
        val suffix = deviceId?.takeIf(String::isNotBlank)?.let { "?device_id=${java.net.URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        put(token, "/me/player/play$suffix", payload)
    }

    suspend fun startPlayback(token: String, deviceId: String?, uris: List<String>) = withContext(Dispatchers.IO) {
        val playable = uris.filter(String::isNotBlank).distinct().take(100)
        require(playable.isNotEmpty()) { "No Spotify tracks to play" }
        val payload = JsonObject(mapOf("uris" to JsonArray(playable.map(::JsonPrimitive)))).toString()
        val suffix = deviceId?.takeIf(String::isNotBlank)?.let { "?device_id=${java.net.URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        put(token, "/me/player/play$suffix", payload)
    }

    suspend fun seek(token: String, positionMs: Long, deviceId: String? = null) = withContext(Dispatchers.IO) {
        put(token, "/me/player/seek?position_ms=${positionMs.coerceAtLeast(0)}${deviceQuery(deviceId, prefix = "&")}", "")
    }
    suspend fun shuffle(token: String, enabled: Boolean, deviceId: String? = null) = withContext(Dispatchers.IO) {
        put(token, "/me/player/shuffle?state=$enabled${deviceQuery(deviceId, prefix = "&")}", "")
    }
    suspend fun repeat(token: String, mode: Int, deviceId: String? = null) = withContext(Dispatchers.IO) {
        val state = when (mode) { 1 -> "context"; 2 -> "track"; else -> "off" }
        put(token, "/me/player/repeat?state=$state${deviceQuery(deviceId, prefix = "&")}", "")
    }
    suspend fun volume(token: String, volume: Int, deviceId: String? = null) = withContext(Dispatchers.IO) {
        put(token, "/me/player/volume?volume_percent=${volume.coerceIn(0,100)}${deviceQuery(deviceId, prefix = "&")}", "")
    }

    private fun deviceQuery(deviceId: String?, prefix: String = "?"): String =
        deviceId?.takeIf(String::isNotBlank)?.let { "$prefix" + "device_id=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()

    suspend fun currentPlayback(token: String): SpotifyRemotePlaybackState? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/me/player")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 204) return@withContext null
            val raw = response.body.string().orEmpty()
            if (!response.isSuccessful) throw apiException(response.code, response.message, raw, response.header("Retry-After"))
            val root = json.parseToJsonElement(raw).jsonObject
            val device = root["device"] as? JsonObject
            SpotifyRemotePlaybackState(
                positionMs = (root["progress_ms"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
                volume = (device?.get("volume_percent") as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                supportsVolume = (device?.get("supports_volume") as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull(),
                shuffle = (root["shuffle_state"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull(),
                repeatMode = when ((root["repeat_state"] as? JsonPrimitive)?.contentOrNull) {
                    "context" -> 1
                    "track" -> 2
                    else -> 0
                },
            )
        }
    }

    suspend fun addToQueue(token: String, deviceId: String, uri: String) = withContext(Dispatchers.IO) {
        require(uri.matches(Regex("spotify:track:[A-Za-z0-9]{22}")))
        val url = "https://api.spotify.com/v1/me/player/queue".toHttpUrl().newBuilder()
            .addQueryParameter("uri", uri).addQueryParameter("device_id", deviceId).build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .post("".toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw apiException(response.code, response.message, response.body.string().orEmpty(), response.header("Retry-After"))
        }
    }

    suspend fun queue(token: String): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val root = get(token, "/me/player/queue")
        root["queue"]?.jsonArray?.mapNotNull { element ->
            if (element is kotlinx.serialization.json.JsonNull) null else runCatching { parseTrack(element.jsonObject) }.getOrNull()
        }.orEmpty()
    }

    private fun put(token: String, path: String, jsonBody: String) {
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("https://api.spotify.com/v1$path").header("Authorization", "Bearer $token").put(body).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body.string().orEmpty()
            if (!response.isSuccessful) throw apiException(response.code, response.message, raw, response.header("Retry-After"))
        }
    }

    private fun get(token: String, path: String): JsonObject = getUrl(token, "https://api.spotify.com/v1$path")

    private fun getUrl(token: String, url: String): JsonObject {
        // Paging links come from API responses; the bearer token never goes to another host.
        require(url.toHttpUrlOrNull()?.let { it.isHttps && it.host == "api.spotify.com" } == true) { "Unexpected Spotify API URL" }
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        client.newCall(request).execute().use { response ->
            val raw = response.body.string().orEmpty()
            if (!response.isSuccessful) throw apiException(response.code, response.message, raw, response.header("Retry-After"))
            return json.parseToJsonElement(raw).jsonObject
        }
    }

    private fun apiException(code: Int, message: String, raw: String, retryAfter: String?): SpotifyApiException {
        val detail = runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val error = root["error"]
            when (error) {
                is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> error.contentOrNull
                else -> null
            }
        }.getOrNull()?.trim()?.take(240)
        val useful = detail?.takeIf(String::isNotBlank) ?: message
        return SpotifyApiException(
            statusCode = code,
            responseBody = raw.take(2_000).takeIf(String::isNotBlank),
            retryAfterSeconds = retryAfter?.toLongOrNull(),
            message = "Spotify API $code: $useful",
        )
    }

    private fun playlistItem(o: JsonObject): SpotifyBrowseItem? {
        val id = o.str("id") ?: return null
        return SpotifyBrowseItem(id, SpotifyEntityType.PLAYLIST, o.str("name") ?: return null, o["owner"]?.jsonObject?.str("display_name"), image(o), "spotify:playlist:$id")
    }
    private fun albumItem(o: JsonObject): SpotifyBrowseItem? {
        val id = o.str("id") ?: return null
        return SpotifyBrowseItem(id, SpotifyEntityType.ALBUM, o.str("name") ?: return null, artistNames(o), image(o), "spotify:album:$id")
    }
    private fun artistItem(o: JsonObject): SpotifyBrowseItem? {
        val id = o.str("id") ?: return null
        return SpotifyBrowseItem(id, SpotifyEntityType.ARTIST, o.str("name") ?: return null, null, image(o), "spotify:artist:$id")
    }
    private fun trackItem(o: JsonObject): SpotifyBrowseItem? {
        val id = o.str("id") ?: return null
        val album = o["album"]?.jsonObject
        return SpotifyBrowseItem(id, SpotifyEntityType.TRACK, o.str("name") ?: return null, artistNames(o), album?.let(::image), "spotify:track:$id")
    }

    private fun parseAlbum(o: JsonObject): SpotifyAlbum = SpotifyAlbum(
        id = o.str("id").orEmpty(), name = o.str("name").orEmpty(), artists = parseArtists(o["artists"]?.jsonArray), images = parseImages(o["images"]?.jsonArray), releaseDate = o.str("release_date"),
        tracks = o["tracks"]?.jsonObject?.get("items")?.jsonArray?.map { parseTrack(it.jsonObject) }.orEmpty(),
    )
    private fun parsePlaylist(o: JsonObject): SpotifyPlaylist = SpotifyPlaylist(
        id = o.str("id").orEmpty(), name = o.str("name").orEmpty(), description = o.str("description"), ownerName = o["owner"]?.jsonObject?.str("display_name"), images = parseImages(o["images"]?.jsonArray),
        tracks = parsePlaylistItems(o["items"]?.jsonObject),
    )

    internal fun parsePlaylistItems(page: JsonObject?): List<SpotifyTrack> = page?.get("items")?.jsonArray?.mapNotNull { row ->
        if (row is kotlinx.serialization.json.JsonNull) return@mapNotNull null
        val objectRow = row.jsonObject
        val item = objectRow["item"] ?: objectRow["track"] ?: return@mapNotNull null
        if (item is kotlinx.serialization.json.JsonNull) null else runCatching { parseTrack(item.jsonObject) }.getOrNull()
    }.orEmpty()
    private fun parseArtist(o: JsonObject) = SpotifyArtist(o.str("id").orEmpty(), o.str("name").orEmpty(), parseImages(o["images"]?.jsonArray))
    private fun parseTrack(o: JsonObject): SpotifyTrack {
        val id = o.str("id").orEmpty()
        val type = o.str("type").orEmpty()
        val albumObj = o["album"] as? JsonObject
        val showObj = o["show"] as? JsonObject
        val artists = parseArtists(o["artists"] as? JsonArray).ifEmpty {
            listOfNotNull(showObj?.str("publisher")?.takeIf(String::isNotBlank)?.let { SpotifyArtist("", it) })
        }
        val album = albumObj?.let(::parseAlbumShallow) ?: if (type == "episode") {
            SpotifyAlbum(
                id = showObj?.str("id").orEmpty(),
                name = showObj?.str("name").orEmpty(),
                images = parseImages((o["images"] as? JsonArray) ?: (showObj?.get("images") as? JsonArray)),
            )
        } else null
        val uri = o.str("uri") ?: "spotify:${type.ifBlank { "track" }}:$id"
        return SpotifyTrack(id, o.str("name").orEmpty(), artists, album, o.long("duration_ms"), uri, o.bool("explicit"))
    }
    private fun parseAlbumShallow(o: JsonObject) = SpotifyAlbum(o.str("id").orEmpty(), o.str("name").orEmpty(), parseArtists(o["artists"]?.jsonArray), parseImages(o["images"]?.jsonArray), o.str("release_date"))
    private fun parseArtists(a: JsonArray?) = a?.map { val o=it.jsonObject; SpotifyArtist(o.str("id").orEmpty(), o.str("name").orEmpty()) }.orEmpty()
    private fun parseImages(a: JsonArray?) = a?.map { val o=it.jsonObject; SpotifyImage(o.str("url").orEmpty(), o.int("width"), o.int("height")) }.orEmpty()
    private fun artistNames(o: JsonObject) = o["artists"]?.jsonArray?.joinToString(", ") { it.jsonObject.str("name").orEmpty() }
    private fun image(o: JsonObject) = o["images"]?.jsonArray?.firstOrNull()?.jsonObject?.str("url")
    private fun JsonObject.str(k:String)= (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(k:String)= (this[k] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
    private fun JsonObject.int(k:String)= (this[k] as? JsonPrimitive)?.content?.toIntOrNull()
    private fun JsonObject.bool(k:String)= (this[k] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
}
