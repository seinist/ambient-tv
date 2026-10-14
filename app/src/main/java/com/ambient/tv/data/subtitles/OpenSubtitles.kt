package com.ambient.tv.data.subtitles

import android.content.Context
import com.ambient.tv.BuildConfig
import com.ambient.tv.data.files.SeekableMedia
import com.ambient.tv.data.local.TokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What the player knows about the title, for a subtitle search. */
data class SubtitleSearchQuery(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val imdbId: String? = null,
    /** OpenSubtitles file hash; exact matches for the very same release. */
    val movieHash: String? = null,
    val languages: List<String> = emptyList(),
)

data class OnlineSubtitle(
    val fileId: Long,
    val fileName: String,
    val release: String,
    val languageCode: String,
    val downloads: Int,
    val hearingImpaired: Boolean,
    val forced: Boolean,
    val hashMatch: Boolean,
    val format: String,
)

class OpenSubtitlesException(message: String, val kind: Kind) : IOException(message) {
    enum class Kind { NOT_CONFIGURED, LOGIN, QUOTA, RATE_LIMIT, OTHER }
}

/**
 * OpenSubtitles.com REST API (v1) with the user's own API key, like the TMDB key for trailers.
 * Searching works with the key alone; downloads count against the account when one is entered.
 */
@Singleton
class OpenSubtitlesClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokens: TokenStore,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val loginLock = Mutex()
    /** Account key -> (API base, bearer token). Tokens last 24 h; a 401 drops it. */
    @Volatile private var session: Triple<String, HttpUrl, String>? = null

    val configured: Boolean get() = !tokens.openSubtitlesApiKey.isNullOrBlank()

    suspend fun search(query: SubtitleSearchQuery): List<OnlineSubtitle> = withContext(Dispatchers.IO) {
        val url = searchUrl(API, query)
        val body = execute(Request.Builder().url(url).get(), authenticated = false)
        parseSearch(body)
    }

    /** Downloads into the app cache and returns the file; the player reads it from there. */
    suspend fun download(subtitle: OnlineSubtitle): File = withContext(Dispatchers.IO) {
        val payload = buildJsonObject { put("file_id", subtitle.fileId); put("sub_format", subtitle.format) }.toString()
        val body = execute(Request.Builder().url(base().newBuilder().addPathSegment("download").build())
            .post(payload.toRequestBody(JSON)), authenticated = true)
        val link = json.parseToJsonElement(body).jsonObject["link"]?.jsonPrimitive?.contentOrNull
            ?: throw OpenSubtitlesException("No download link", OpenSubtitlesException.Kind.OTHER)
        val bytes = http.newCall(Request.Builder().url(link).header("User-Agent", USER_AGENT).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download HTTP ${response.code}")
            response.body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk); if (n < 0) break
                    out.write(chunk, 0, n)
                    if (out.size() > MAX_SUBTITLE_BYTES) throw IOException("Subtitle too large")
                }
                out.toByteArray()
            }
        }
        val folder = File(context.cacheDir, "opensubtitles").apply { mkdirs() }
        // Keep the cache small: subtitles of the last titles only.
        folder.listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
        File(folder, "${subtitle.fileId}.${subtitle.format}").apply { writeBytes(bytes) }
    }

    /** Checks key and account; returns the remaining downloads today when the API reports them. */
    suspend fun test(): Int? = withContext(Dispatchers.IO) {
        session = null
        val hasAccount = !tokens.openSubtitlesUsername.isNullOrBlank()
        if (!hasAccount) {
            // A tiny search proves the key.
            execute(Request.Builder().url(searchUrl(API, SubtitleSearchQuery("big buck bunny"))).get(), authenticated = false)
            return@withContext null
        }
        val body = execute(Request.Builder().url(base().newBuilder().addPathSegments("infos/user").build()).get(), authenticated = true)
        json.parseToJsonElement(body).jsonObject["data"]?.jsonObject?.get("remaining_downloads")?.jsonPrimitive?.intOrNull
    }

    private suspend fun base(): HttpUrl = ensureSession()?.second ?: API

    private suspend fun ensureSession(): Triple<String, HttpUrl, String>? {
        val user = tokens.openSubtitlesUsername?.takeIf(String::isNotBlank) ?: return null
        val password = tokens.openSubtitlesPassword.orEmpty()
        val account = user + "\u0000" + password.hashCode() + "\u0000" + tokens.openSubtitlesApiKey
        session?.takeIf { it.first == account }?.let { return it }
        return loginLock.withLock {
            session?.takeIf { it.first == account }?.let { return@withLock it }
            val payload = buildJsonObject { put("username", user); put("password", password) }.toString()
            val body = execute(Request.Builder().url(API.newBuilder().addPathSegment("login").build())
                .post(payload.toRequestBody(JSON)), authenticated = false, loginRequest = true)
            val root = json.parseToJsonElement(body).jsonObject
            val token = root["token"]?.jsonPrimitive?.contentOrNull
                ?: throw OpenSubtitlesException("Login failed", OpenSubtitlesException.Kind.LOGIN)
            // VIP accounts get their own API host.
            val host = root["base_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.matches(Regex("[A-Za-z0-9.-]+\\.opensubtitles\\.com")) }
            val apiBase = host?.let { "https://$it/api/v1".toHttpUrl() } ?: API
            Triple(account, apiBase, token).also { session = it }
        }
    }

    private suspend fun execute(builder: Request.Builder, authenticated: Boolean, loginRequest: Boolean = false): String {
        val key = tokens.openSubtitlesApiKey?.takeIf(String::isNotBlank)
            ?: throw OpenSubtitlesException("No API key", OpenSubtitlesException.Kind.NOT_CONFIGURED)
        builder.header("Api-Key", key).header("User-Agent", USER_AGENT).header("Accept", "application/json")
        if (authenticated) ensureSession()?.let { builder.header("Authorization", "Bearer ${it.third}") }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body.string().take(MAX_JSON_CHARS)
            when {
                response.isSuccessful -> return text
                response.code == 401 -> {
                    session = null
                    throw OpenSubtitlesException(apiMessage(text) ?: "Unauthorized",
                        if (loginRequest || authenticated) OpenSubtitlesException.Kind.LOGIN else OpenSubtitlesException.Kind.NOT_CONFIGURED)
                }
                response.code == 406 -> throw OpenSubtitlesException(apiMessage(text) ?: "Download limit reached", OpenSubtitlesException.Kind.QUOTA)
                response.code == 429 -> throw OpenSubtitlesException("Too many requests", OpenSubtitlesException.Kind.RATE_LIMIT)
                else -> throw OpenSubtitlesException(apiMessage(text) ?: "HTTP ${response.code}", OpenSubtitlesException.Kind.OTHER)
            }
        }
    }

    private fun apiMessage(body: String): String? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        root["message"]?.jsonPrimitive?.contentOrNull ?: root["errors"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    internal companion object {
        val API: HttpUrl = "https://api.opensubtitles.com/api/v1".toHttpUrl()
        val JSON = "application/json".toMediaType()
        // The API requires "<app name> v<version>".
        val USER_AGENT = "AmbientTV v" + BuildConfig.VERSION_NAME.substringBefore(' ')
        const val MAX_SUBTITLE_BYTES = 5 * 1024 * 1024
        const val MAX_JSON_CHARS = 2 * 1024 * 1024
    }
}

/**
 * Search URL. The API redirects requests whose parameters are not sorted and lower-case, which
 * costs a round trip and drops the API key on some clients, so they are built that way here.
 */
internal fun searchUrl(base: HttpUrl, query: SubtitleSearchQuery): HttpUrl {
    val params = sortedMapOf<String, String>()
    val imdb = query.imdbId?.removePrefix("tt")?.trimStart('0')?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    query.movieHash?.takeIf { it.matches(Regex("[0-9a-f]{16}")) }?.let { params["moviehash"] = it }
    if (imdb != null) {
        // Plex/Jellyfin/Emby give an episode its own IMDb id, so it is never a parent (series) id.
        params["imdb_id"] = imdb
    } else {
        params["query"] = query.title.lowercase(Locale.ROOT).trim()
        query.year?.takeIf { query.season == null }?.let { params["year"] = it.toString() }
    }
    if (imdb == null) {
        query.season?.let { params["season_number"] = it.toString() }
        query.episode?.let { params["episode_number"] = it.toString() }
    }
    query.languages.map { it.lowercase(Locale.ROOT) }.filter { it.matches(Regex("[a-z]{2}(-[a-z]{2})?")) }.distinct().sorted()
        .takeIf { it.isNotEmpty() }?.let { params["languages"] = it.joinToString(",") }
    val builder = base.newBuilder().addPathSegment("subtitles")
    params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
    return builder.build()
}

internal fun parseSearch(body: String): List<OnlineSubtitle> {
    val root = Json.parseToJsonElement(body).jsonObject
    val data = (root["data"] as? JsonArray).orEmpty()
    return data.mapNotNull { element ->
        val attributes = (element as? JsonObject)?.get("attributes") as? JsonObject ?: return@mapNotNull null
        val file = (attributes["files"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@mapNotNull null
        val fileId = file["file_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        val fileName = file["file_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val format = attributes["format"]?.jsonPrimitive?.contentOrNull?.lowercase()
            ?.takeIf { it in setOf("srt", "ass", "ssa", "vtt") } ?: "srt"
        OnlineSubtitle(
            fileId = fileId,
            fileName = fileName,
            release = attributes["release"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: fileName,
            languageCode = attributes["language"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            downloads = attributes["download_count"]?.jsonPrimitive?.intOrNull ?: 0,
            hearingImpaired = attributes["hearing_impaired"].bool(),
            forced = attributes["foreign_parts_only"].bool(),
            hashMatch = attributes["moviehash_match"].bool(),
            format = format,
        )
    }.sortedWith(compareByDescending<OnlineSubtitle> { it.hashMatch }.thenByDescending { it.downloads })
}

private fun JsonElement?.bool(): Boolean = (this as? JsonPrimitive)?.booleanOrNull == true

/**
 * OpenSubtitles hash: file size plus the 64-bit little-endian words of the first and last 64 KiB,
 * as 16 lower-case hex digits. Null for files under 128 KiB, which the service does not hash.
 */
object OpenSubtitlesHash {
    private const val CHUNK = 64 * 1024

    fun of(media: SeekableMedia): String? {
        val size = media.size
        if (size < 2L * CHUNK) return null
        return of(size, read(media, 0), read(media, size - CHUNK))
    }

    fun of(size: Long, head: ByteArray, tail: ByteArray): String {
        require(head.size == CHUNK && tail.size == CHUNK)
        var hash = size
        for (block in listOf(head, tail)) {
            val buffer = java.nio.ByteBuffer.wrap(block).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            repeat(CHUNK / 8) { hash += buffer.getLong() }
        }
        return "%016x".format(hash)
    }

    private fun read(media: SeekableMedia, position: Long): ByteArray {
        val bytes = ByteArray(CHUNK); val part = ByteArray(CHUNK); var done = 0
        while (done < CHUNK) {
            val n = media.read(position + done, part, CHUNK - done)
            if (n <= 0) throw IOException("Short read")
            System.arraycopy(part, 0, bytes, done, n)
            done += n
        }
        return bytes
    }
}
