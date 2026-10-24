package com.ambient.tv.trailers

import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.util.suspendResult
import java.util.concurrent.TimeUnit
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class TrailerConfigurationMissing : IllegalStateException()
open class TrailerRequestFailed(val status: Int) : IllegalStateException("TMDB HTTP $status")
class TrailerAuthenticationFailed(status: Int) : TrailerRequestFailed(status)
class TrailerNotFound : TrailerRequestFailed(404)
class TrailerRateLimited(val retryAfterSeconds: Long?) : TrailerRequestFailed(429)
class TrailerServiceUnavailable(status: Int) : TrailerRequestFailed(status)

/** Dedicated client: never attach media-server credentials or log the TMDB Authorization header. */
@Singleton
class TmdbTrailerRepository internal constructor(private val tokens: TokenStore, private val client: OkHttpClient,
    private val nanoTime: () -> Long = System::nanoTime) {
    @Inject constructor(
        tokens: TokenStore,
        @com.ambient.tv.di.SharedOkHttp connectionPool: okhttp3.ConnectionPool,
        @com.ambient.tv.di.SharedOkHttp dispatcher: okhttp3.Dispatcher,
    ) : this(tokens,
        OkHttpClient.Builder().connectionPool(connectionPool).dispatcher(dispatcher).callTimeout(15, TimeUnit.SECONDS).addInterceptor(com.ambient.tv.diagnostics.DiagnosticHttpInterceptor).build())
    val configuration = tokens.tmdbCredentialVersion
    val configured: Boolean get() = !tokens.tmdbApiKey.isNullOrBlank()
    private var cacheGeneration = -1L
    private var retryDeadlineNanos: Long? = null
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val limiter = Semaphore(4)
    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightMutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<JsonObject>>()
    private data class Cached(val at: Long, val value: JsonObject)
    private val cache = object : LinkedHashMap<String, Cached>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 256
    }

    suspend fun connect(value: String): Result<Unit> = suspendResult {
        val key = value.trim()
        require(key.isNotBlank() && key.none(Char::isWhitespace))
        request("configuration", credential = key, cacheable = false)
        currentCoroutineContext().ensureActive()
        tokens.tmdbApiKey = key
        synchronized(cache) { cache.clear() }
    }

    fun disconnect() {
        tokens.tmdbApiKey = null
        synchronized(cache) { cache.clear() }
    }

    private suspend fun request(path: String, parameters: Map<String, String> = emptyMap(),
        credential: String = tokens.tmdbApiKey.orEmpty(), cacheable: Boolean = true,
        ttlNanos: Long = TimeUnit.HOURS.toNanos(12)): JsonObject {
        if (credential.isBlank()) throw TrailerConfigurationMissing()
        val requestGeneration = tokens.tmdbCredentialVersion.value
        synchronized(cache) {
            if (cacheGeneration != requestGeneration) {
                cache.clear(); cacheGeneration = requestGeneration; retryDeadlineNanos = null
            }
        }
        val v3Key = isTmdbV3Key(credential)
        val url = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder().apply {
            parameters.forEach { (key, value) -> addQueryParameter(key, value) }
            if (v3Key) addQueryParameter("api_key", credential)
        }.build()
        val stableParameters = parameters.toSortedMap().entries.joinToString("&") { "${it.key.length}:${it.key}${it.value.length}:${it.value}" }
        val key = "$path?$stableParameters"
        if (cacheable) synchronized(cache) { cache[key]?.takeIf { nanoTime() - it.at < ttlNanos } }?.let { return it.value }
        suspend fun execute(): JsonObject = limiter.withPermit {
            currentCoroutineContext().ensureActive()
            if (cacheable && tokens.tmdbCredentialVersion.value != requestGeneration) throw TrailerConfigurationMissing()
            if (cacheable) synchronized(cache) {
                val remaining = retryDeadlineNanos?.let { it - nanoTime() } ?: 0L
                if (remaining > 0) throw TrailerRateLimited((remaining + 999_999_999L) / 1_000_000_000L)
            }
            withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(url).apply {
                    header("Accept", "application/json")
                    if (!v3Key) header("Authorization", "Bearer $credential")
                }.build()).execute().use { response ->
                    if (!response.isSuccessful) throw when (response.code) {
                        401, 403 -> TrailerAuthenticationFailed(response.code)
                        404 -> TrailerNotFound()
                        429 -> {
                            val retry = (parseRetryAfter(response.header("Retry-After"))) ?: 30L
                            synchronized(cache) {
                                if (cacheGeneration == requestGeneration) {
                                    val deadline = nanoTime() + TimeUnit.SECONDS.toNanos(retry)
                                    retryDeadlineNanos = retryDeadlineNanos?.let { maxOf(it, deadline) } ?: deadline
                                }
                            }
                            TrailerRateLimited(retry)
                        }
                        in 500..599 -> TrailerServiceUnavailable(response.code)
                        else -> TrailerRequestFailed(response.code)
                    }
                    val body = response.body.string().orEmpty()
                    val result = runCatching { json.parseToJsonElement(body).jsonObject }
                        .getOrElse { throw TrailerServiceUnavailable(502) }
                    if (cacheable) synchronized(cache) {
                        if (tokens.tmdbCredentialVersion.value == requestGeneration && cacheGeneration == requestGeneration)
                            cache[key] = Cached(nanoTime(), result)
                    }
                    result
                }
            }
        }
        if (!cacheable) return execute()
        val requestKey = "$requestGeneration:$key"
        val deferred = inFlightMutex.withLock {
            inFlight[requestKey]?.takeUnless { it.isCompleted } ?: requestScope.async { execute() }.also { created ->
                inFlight[requestKey] = created
                created.invokeOnCompletion {
                    requestScope.launch {
                        inFlightMutex.withLock {
                            if (inFlight[requestKey] === created) inFlight.remove(requestKey)
                        }
                    }
                }
            }
        }
        return deferred.await()
    }

    private fun parseRetryAfter(value: String?): Long? {
        val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        raw.toLongOrNull()?.let { return it.coerceIn(0, 86_400) }
        return runCatching {
            Duration.between(Instant.now(), ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
                .seconds.coerceIn(0, 86_400)
        }.getOrNull()
    }

    private fun titles(response: JsonObject, field: String = "results") =
        response[field]?.jsonArray.orEmpty().map { json.decodeFromJsonElement<TrailerTitle>(it) }

    suspend fun videos(type: String, id: Int, language: String, original: String): List<TrailerVideo> {
        require(type in setOf("movie", "tv") && id > 0)
        // Stop at the first language with playable results. A catalog page can otherwise fan out
        // into sixty requests before it renders, which needlessly reaches TMDB rate limits.
        val preferred = language.substringBefore('-')
        for (locale in listOf(language, original, "en").filter(String::isNotBlank).distinctBy { it.substringBefore('-') }) {
            val values = request("$type/$id/videos", mapOf("language" to locale),
                ttlNanos = TimeUnit.HOURS.toNanos(24))["results"]?.jsonArray.orEmpty()
                .map { json.decodeFromJsonElement<TrailerVideo>(it) }
            val ranked = rankTrailers(values, preferred, original)
            if (ranked.isNotEmpty()) return ranked
        }
        return emptyList()
    }

    suspend fun feedPage(feed: TrailerFeed, page: Int, language: String, region: String): Result<TrailerPage> = suspendResult {
        require(page in 1..500)
        val response = request(feed.path, mapOf("language" to language, "region" to region, "page" to page.toString()),
            ttlNanos = TimeUnit.MINUTES.toNanos(15))
        val candidates = titles(response).distinctBy { it.id }.take(20)
        coroutineScope {
            val results = candidates.map { title -> async {
                suspendResult { TrailerEntry(title, feed.mediaType, videos(feed.mediaType, title.id, language, title.originalLanguage)) }
            } }.awaitAll()
            if (results.isNotEmpty() && results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
            TrailerPage(
                entries = results.mapNotNull { it.getOrNull()?.takeIf { entry -> entry.videos.isNotEmpty() } },
                page = response["page"]?.jsonPrimitive?.intOrNull ?: page,
                totalPages = (response["total_pages"]?.jsonPrimitive?.intOrNull ?: page).coerceIn(page, 500),
                partialFailure = results.any { it.isFailure },
            )
        }
    }

    suspend fun feed(feed: TrailerFeed, language: String, region: String): Result<List<TrailerEntry>> =
        feedPage(feed, 1, language, region).map { it.entries }

    suspend fun search(query: String, language: String, region: String): Result<List<TrailerSearchItem>> = suspendResult {
        val normalized = query.trim()
        if (normalized.length < 2) return@suspendResult emptyList()
        coroutineScope {
            listOf("movie", "tv").map { type -> async {
                val parameters = mutableMapOf(
                    "query" to normalized, "language" to language,
                    "include_adult" to "false", "page" to "1",
                ).apply { if (type == "movie") put("region", region) }
                val response = request("search/$type", parameters, ttlNanos = TimeUnit.MINUTES.toNanos(10))
                titles(response).take(12).map { TrailerSearchItem(it, type) }
            } }.awaitAll().flatten().distinctBy { it.key }.sortedByDescending { it.title.popularity }.take(24)
        }
    }

    suspend fun forTmdb(type: String, id: Int, language: String): Result<Pair<TrailerTitle, List<TrailerVideo>>> = suspendResult {
        require(type in setOf("movie", "tv") && id > 0)
        val title = json.decodeFromJsonElement<TrailerTitle>(request("$type/$id", mapOf("language" to language),
            ttlNanos = TimeUnit.HOURS.toNanos(24)))
        title to videos(type, id, language, title.originalLanguage)
    }

    suspend fun forItem(item: MediaItem, language: String): Result<List<TrailerVideo>> = suspendResult {
        val type = tmdbType(item) ?: return@suspendResult emptyList()
        val (id, matched) = tmdbMatch(item, type, language) ?: return@suspendResult emptyList()
        val original = matched?.originalLanguage ?: request("$type/$id")["original_language"]?.jsonPrimitive?.contentOrNull.orEmpty()
        videos(type, id, language, original)
    }

    /** TMDb's wide backdrop for a movie or show, for pages whose media server has none. */
    suspend fun backdropFor(item: MediaItem, language: String): Result<String?> = suspendResult {
        val type = tmdbType(item) ?: return@suspendResult null
        val (id, matched) = tmdbMatch(item, type, language) ?: return@suspendResult null
        val path = matched?.backdrop ?: request("$type/$id", ttlNanos = TimeUnit.HOURS.toNanos(24))["backdrop_path"]?.jsonPrimitive?.contentOrNull
        path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w1280$it" }
    }

    private fun tmdbType(item: MediaItem): String? = when (item.type) { "movie" -> "movie"; "show" -> "tv"; else -> null }

    /** The TMDb id: from the item's tmdb:// guid, its imdb:// guid, or a title/year search. */
    private suspend fun tmdbMatch(item: MediaItem, type: String, language: String): Pair<Int, TrailerTitle?>? {
        val ids = item.guids + listOfNotNull(item.guid)
        val direct = ids.firstOrNull { it.startsWith("tmdb://") }?.substringAfter("tmdb://")?.substringBefore('?')?.toIntOrNull()
        if (direct != null) return direct to null
        val imdb = ids.firstOrNull { it.startsWith("imdb://") }?.substringAfter("imdb://")?.substringBefore('?')
        val found = if (imdb != null && Regex("tt[0-9]+").matches(imdb)) {
            titles(request("find/$imdb", mapOf("external_source" to "imdb_id")), if (type == "movie") "movie_results" else "tv_results").singleOrNull()
        } else null
        val matched = found ?: matchTrailerTitle(titles(request("search/$type",
            mapOf("query" to item.title, "language" to language, "include_adult" to "false"))), item.title, item.year)
        return matched?.let { it.id to it }
    }
}
