package com.ambient.tv.games.metadata

import android.content.Context
import com.ambient.tv.di.SharedOkHttp
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

enum class GameMetadataFailure { CREDENTIALS, RATE_LIMIT, NETWORK, INVALID_RESPONSE, UNSUPPORTED }

/** Contains neither request URLs nor response text: both can contain account credentials. */
class GameMetadataProviderException(
    val kind: GameMetadataFailure,
    val retryAfterMs: Long = 0L,
) : IOException(when (kind) {
    GameMetadataFailure.CREDENTIALS -> "Game metadata provider credentials are missing or rejected"
    GameMetadataFailure.RATE_LIMIT -> "Game metadata provider request limit reached; try again later"
    GameMetadataFailure.NETWORK -> "Game metadata provider is unavailable"
    GameMetadataFailure.INVALID_RESPONSE -> "Game metadata provider returned an invalid response"
    GameMetadataFailure.UNSUPPORTED -> "Game metadata provider does not support this system"
})

/** Provider requests are independent of Plex authentication/routing and never log URL queries. */
@Singleton
class GameMetadataProviders @Inject constructor(
    private val settings: GameMetadataSettings,
    private val json: Json,
    @SharedOkHttp pool: ConnectionPool,
    @SharedOkHttp dispatcher: okhttp3.Dispatcher,
    @ApplicationContext private val context: Context,
) {
    private val client = OkHttpClient.Builder().connectionPool(pool).dispatcher(dispatcher)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    // A name database is a few MB of text: slow TV networks need longer than an API answer.
    private val databaseClient = client.newBuilder().callTimeout(3, TimeUnit.MINUTES).build()
    // ScreenScraper guests may have only one worker. A conservative shared API lane respects it.
    private val apiSlots = Semaphore(1)
    private val artworkSlots = Semaphore(2)
    private val screenScraperSlots = Semaphore(1)
    private val cooldowns = ConcurrentHashMap<String, Long>()
    private val minimumIntervals = ConcurrentHashMap<String, Long>()
    private val nextRequests = ConcurrentHashMap<String, Long>()
    private val lookupMutex = Mutex()
    private val lookups = mutableMapOf<String, Map<String, String>>()

    fun availableProviders(): List<String> = buildList {
        add(GameMetadataProviderIds.LIBRETRO)
        val keys = settings.credentials()
        if (keys.screenScraperReady) add(GameMetadataProviderIds.SCREENSCRAPER)
        if (keys.theGamesDbReady) add(GameMetadataProviderIds.THEGAMESDB)
    }

    suspend fun search(query: GameMetadataQuery, provider: String): List<GameMetadataCandidate> {
        if (query.title.isBlank() && query.providerGameId.isNullOrBlank() && query.crc32.isNullOrBlank() && query.serial.isNullOrBlank()) return emptyList()
        return when (provider) {
            GameMetadataProviderIds.LIBRETRO -> GameMetadataPlatforms.libretroCandidates(query)
            GameMetadataProviderIds.SCREENSCRAPER -> screenScraper(query)
            GameMetadataProviderIds.THEGAMESDB -> theGamesDb(query)
            else -> throw GameMetadataProviderException(GameMetadataFailure.UNSUPPORTED)
        }
    }

    /** Alternate images retain the matched game identity; no additional name lookup is needed. */
    suspend fun artworkChoices(query: GameMetadataQuery, provider: String, kind: GameArtworkKind): List<GameMetadataCandidate> {
        val choices = when (provider) {
            GameMetadataProviderIds.LIBRETRO -> GameMetadataPlatforms.libretroCandidates(query)
            GameMetadataProviderIds.SCREENSCRAPER -> screenScraper(query, kind)
            GameMetadataProviderIds.THEGAMESDB -> theGamesDb(query, kind)
            else -> throw GameMetadataProviderException(GameMetadataFailure.UNSUPPORTED)
        }
        return choices.filter { it.metadata.artwork(kind) != null }.distinctBy { it.metadata.artwork(kind) }.take(MAX_ARTWORK_CHOICES)
    }

    /** Writes only to the caller's staging file. Publishing/ownership is the library's decision. */
    suspend fun downloadArtwork(provider: String, reference: String, destination: File): Boolean {
        val url = sanitizedGameArtworkUrl(provider, reference)?.toHttpUrlOrNull()
            ?: throw GameMetadataProviderException(GameMetadataFailure.INVALID_RESPONSE)
        return artworkSlots.withPermit {
            withProviderSlot(provider) {
                try {
                    val alternatives = listOfNotNull(url,
                        if (provider == GameMetadataProviderIds.LIBRETRO) libretroArtworkMirrorUrl(url) else null)
                    var invalidResponse: GameMetadataProviderException? = null
                    for (target in alternatives) {
                        try {
                            if (downloadArtworkAt(provider, target, destination)) return@withProviderSlot true
                        } catch (failure: GameMetadataProviderException) {
                            if (failure.kind != GameMetadataFailure.INVALID_RESPONSE) throw failure
                            invalidResponse = failure
                        }
                        destination.delete()
                    }
                    // A broken response followed by a missing mirror is still an error, not a cached miss.
                    invalidResponse?.let { throw it }
                    false
                } catch (failure: Throwable) {
                    destination.delete()
                    throw failure
                }
            }
        }
    }

    private suspend fun downloadArtworkAt(provider: String, url: HttpUrl, destination: File): Boolean =
        request(provider, url) { response ->
            if (response.code == 404) return@request false
            checkStatus(provider, response)
            val body = response.body
            if (body.contentLength() > MAX_IMAGE_BYTES || body.contentType()?.type != "image") {
                throw GameMetadataProviderException(GameMetadataFailure.INVALID_RESPONSE)
            }
            body.byteStream().use { input -> destination.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_IMAGE_BYTES) throw GameMetadataProviderException(GameMetadataFailure.INVALID_RESPONSE)
                    output.write(buffer, 0, count)
                }
                if (total == 0L) throw GameMetadataProviderException(GameMetadataFailure.INVALID_RESPONSE)
            } }
            true
        }

    /**
     * One DAT file of libretro-database ([path] below its root) into the caller's staging file.
     * False when the file is gone upstream; the library imports it, nothing here is kept.
     */
    suspend fun downloadNameDatabase(path: String, destination: File): Boolean {
        val url = HttpUrl.Builder().scheme("https").host(LIBRETRO_DATABASE_HOST)
            .addPathSegments(LIBRETRO_DATABASE_ROOT)
            .apply { path.split('/').forEach(::addPathSegment) }.build()
        val provider = GameMetadataProviderIds.LIBRETRO_DATABASE
        return try {
            request(provider, url, databaseClient) { response ->
                if (response.code == 404) return@request false
                checkStatus(provider, response)
                response.body.byteStream().use { input -> destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_DATABASE_BYTES) throw GameMetadataProviderException(GameMetadataFailure.INVALID_RESPONSE)
                        output.write(buffer, 0, count)
                    }
                    if (total == 0L) throw GameMetadataProviderException(GameMetadataFailure.INVALID_RESPONSE)
                } }
                true
            }.also { if (!it) destination.delete() }
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        }
    }

    private suspend fun screenScraper(query: GameMetadataQuery, artworkKind: GameArtworkKind? = null): List<GameMetadataCandidate> {
        if (!settings.credentials().screenScraperReady) throw GameMetadataProviderException(GameMetadataFailure.CREDENTIALS)
        val platformIds = GameMetadataPlatforms.forQuery(query).mapNotNull { it.screenScraper }.distinct()
        if (platformIds.isEmpty()) throw GameMetadataProviderException(GameMetadataFailure.UNSUPPORTED)
        val pinned = query.providerGameId?.takeIf { it.matches(Regex("[0-9]{1,12}")) }
        val crc = query.crc32?.uppercase()?.takeIf { it.matches(Regex("[A-F0-9]{8}")) }
        val hasIdentity = pinned != null || !query.serial.isNullOrBlank() || crc != null && query.size > 0
        val results = mutableListOf<GameMetadataCandidate>()
        for (platform in platformIds) {
            val builder = HttpUrl.Builder().scheme("https").host("api.screenscraper.fr")
                .addPathSegments("api2/" + if (hasIdentity) "jeuInfos.php" else "jeuRecherche.php")
                .addQueryParameter("systemeid", platform.toString()).addQueryParameter("output", "json")
            when {
                pinned != null -> builder.addQueryParameter("gameid", pinned)
                !query.serial.isNullOrBlank() -> builder.addQueryParameter("serialnum", query.serial.take(128))
                hasIdentity -> builder.addQueryParameter("crc", crc).addQueryParameter("romtaille", query.size.toString())
                    .addQueryParameter("romnom", query.filename.take(512)).addQueryParameter("romtype", "rom")
                else -> builder.addQueryParameter("recherche", query.title.take(256))
            }
            val payload = getJson(GameMetadataProviderIds.SCREENSCRAPER, builder.build(), missingIsEmpty = true) ?: continue
            results += parseScreenScraper(payload, query, platformIds.toSet(), hasIdentity, artworkKind)
            if (pinned != null && results.isNotEmpty()) break
        }
        if (results.isEmpty() && hasIdentity && pinned == null) {
            // A hash miss is distinct from provider failure; title fallback still requires confirmation.
            return screenScraper(query.copy(crc32 = null, serial = null, providerGameId = null), artworkKind)
        }
        val clean = gameMetadataSearchName(query.title)
        if (results.isEmpty() && !hasIdentity && clean != query.title) {
            return screenScraper(query.copy(title = clean), artworkKind)
        }
        return results.distinctBy { if (artworkKind == null) it.metadata.providerGameId else it.metadata.artwork(artworkKind) }
            .take(if (artworkKind == null) MAX_MATCHES else MAX_ARTWORK_CHOICES)
    }

    private suspend fun theGamesDb(query: GameMetadataQuery, artworkKind: GameArtworkKind? = null): List<GameMetadataCandidate> {
        if (!settings.credentials().theGamesDbReady) throw GameMetadataProviderException(GameMetadataFailure.CREDENTIALS)
        val platformNames = GameMetadataPlatforms.forQuery(query).flatMap { it.gamesDbNames }.toSet()
        if (platformNames.isEmpty()) throw GameMetadataProviderException(GameMetadataFailure.UNSUPPORTED)
        val platforms = lookup("Platforms", "platforms")
        val ids = platforms.filterValues { normalizedPlatformName(it) in platformNames }.keys
        if (ids.isEmpty()) throw GameMetadataProviderException(GameMetadataFailure.UNSUPPORTED)
        val pinned = query.providerGameId?.takeIf { it.matches(Regex("[0-9]{1,12}")) }
        val crc = query.crc32?.takeIf { it.matches(Regex("[a-fA-F0-9]{8}")) }
        val path = when { pinned != null -> "v1/Games/ByGameID"; crc != null -> "v1/Games/ByGameHash"; else -> "v1.1/Games/ByGameName" }
        val builder = HttpUrl.Builder().scheme("https").host("api.thegamesdb.net").addPathSegments(path)
            .addQueryParameter("fields", "players,publishers,genres,overview,platform")
            .addQueryParameter("include", "boxart").addQueryParameter("filter[platform]", ids.joinToString(","))
        when { pinned != null -> builder.addQueryParameter("id", pinned)
            crc != null -> builder.addQueryParameter("hash", crc).addQueryParameter("filter[type]", "crc")
            else -> builder.addQueryParameter("name", query.title.take(256)) }
        val clean = gameMetadataSearchName(query.title)
        suspend fun onMiss(): List<GameMetadataCandidate> = when {
            crc != null && pinned == null -> theGamesDb(query.copy(crc32 = null), artworkKind)
            pinned == null && crc == null && clean != query.title -> theGamesDb(query.copy(title = clean), artworkKind)
            else -> emptyList()
        }
        val payload = getJson(GameMetadataProviderIds.THEGAMESDB, builder.build(), missingIsEmpty = true)
            ?: return onMiss()
        val gamesElement = payload.obj("data")["games"]
        if (gamesElement !is JsonArray && gamesElement !is JsonObject) throw GameMetadataProviderException(GameMetadataFailure.INVALID_RESPONSE)
        val games = payload.obj("data").objects("games").filter { it.text("platform") in ids }.take(MAX_MATCHES)
        if (games.isEmpty()) return onMiss()
        // Reference catalogues are fetched once and persisted; browsing never fetches them.
        val genres = if (artworkKind == null && games.any { it["genres"] is JsonArray }) lookup("Genres", "genres") else emptyMap()
        // Developers are a default response field in the official API, not an optional fields token.
        val developers = if (artworkKind == null && games.any { it["developers"] is JsonArray }) lookup("Developers", "developers") else emptyMap()
        val publishers = if (artworkKind == null && games.any { it["publishers"] is JsonArray }) lookup("Publishers", "publishers") else emptyMap()
        val gameIds = games.map { it.text("id") }.filter { it.matches(Regex("[0-9]{1,12}")) }
        if (gameIds.isEmpty()) throw GameMetadataProviderException(GameMetadataFailure.INVALID_RESPONSE)
        val imageUrl = HttpUrl.Builder().scheme("https").host("api.thegamesdb.net").addPathSegments("v1/Games/Images")
            .addQueryParameter("games_id", gameIds.joinToString(","))
            .addQueryParameter("filter[type]", "boxart,screenshot,fanart,titlescreen").build()
        val images = getJson(GameMetadataProviderIds.THEGAMESDB, imageUrl, missingIsEmpty = true)
        return parseTheGamesDb(payload, images, query, ids, genres, developers, publishers,
            exact = pinned != null || crc != null, artworkKind = artworkKind)
    }

    private suspend fun lookup(endpoint: String, key: String): Map<String, String> = lookupMutex.withLock {
        lookups[key]?.let { return@withLock it }
        val cached = withContext(Dispatchers.IO) {
            val file = File(context.cacheDir, "game_metadata_providers/tgdb_" + key + ".json")
            if (!file.isFile || file.length() > MAX_JSON_BYTES || System.currentTimeMillis() - file.lastModified() > LOOKUP_MAX_AGE) null
            else runCatching { json.parseToJsonElement(file.readText()).jsonObject.takeIf { labels ->
                labels.isNotEmpty() && labels.size <= 100_000 && labels.all { (id, name) ->
                    id.matches(Regex("[0-9]{1,12}")) && name is JsonPrimitive && name.isString && name.content.length in 1..256
                }
            } }.getOrNull()
        }
        val payload = cached ?: getJson(GameMetadataProviderIds.THEGAMESDB,
            HttpUrl.Builder().scheme("https").host("api.thegamesdb.net").addPathSegments("v1/" + endpoint).build())
            ?: throw GameMetadataProviderException(GameMetadataFailure.INVALID_RESPONSE)
        val entries = if (cached != null) cached.mapValues { it.value.jsonPrimitive.content } else
            payload.obj("data").objects(key).mapNotNull { item ->
                val id = item.text("id"); val name = item.text("name")
                if (id.matches(Regex("[0-9]{1,12}")) && name.isNotBlank()) id to name.take(256) else null
            }.take(100_000).toMap()
        if (entries.isEmpty()) throw GameMetadataProviderException(GameMetadataFailure.INVALID_RESPONSE)
        if (cached == null) withContext(Dispatchers.IO) {
            // These are public catalogue labels only; never persist a raw API response or pagination URLs.
            val directory = File(context.cacheDir, "game_metadata_providers").apply { mkdirs() }
            val target = File(directory, "tgdb_" + key + ".json")
            val temporary = File(directory, target.name + ".part")
            runCatching {
                val encoded = JsonObject(entries.mapValues { JsonPrimitive(it.value) }).toString()
                if (encoded.toByteArray().size <= MAX_JSON_BYTES) {
                    temporary.writeText(encoded); if (!temporary.renameTo(target)) temporary.delete()
                }
            }.onFailure { temporary.delete() }
        }
        entries.also { lookups[key] = it }
    }

    private suspend fun getJson(provider: String, url: HttpUrl, missingIsEmpty: Boolean = false): JsonObject? =
        apiSlots.withPermit { withProviderSlot(provider) {
            request(provider, url) { response ->
                if (response.code == 404 && missingIsEmpty) return@request null
                val gamesDbRejected = provider == GameMetadataProviderIds.THEGAMESDB && response.code == 403
                if (!gamesDbRejected) checkStatus(provider, response)
                val body = response.body
                if (body.contentLength() > MAX_JSON_BYTES) throw GameMetadataProviderException(GameMetadataFailure.INVALID_RESPONSE)
                val source = body.source()
                if (source.request(MAX_JSON_BYTES + 1)) throw GameMetadataProviderException(GameMetadataFailure.INVALID_RESPONSE)
                val payload = runCatching { json.parseToJsonElement(source.readUtf8()) as? JsonObject }.getOrNull()
                    ?: throw GameMetadataProviderException(if (gamesDbRejected) GameMetadataFailure.CREDENTIALS else GameMetadataFailure.INVALID_RESPONSE)
                if (provider == GameMetadataProviderIds.SCREENSCRAPER) checkScreenScraperQuota(payload)
                if (provider == GameMetadataProviderIds.THEGAMESDB) checkGamesDbQuota(payload, gamesDbRejected)
                checkStatus(provider, response)
                val code = (payload["code"] as? JsonPrimitive)?.intOrNull
                if (code in setOf(429, 430, 431)) rateLimited(provider, 60_000L)
                if (code != null && code != 200) throw GameMetadataProviderException(
                    if (code == 401 || code == 403) GameMetadataFailure.CREDENTIALS else GameMetadataFailure.INVALID_RESPONSE)
                payload
            }
        } }

    private suspend fun <T> withProviderSlot(provider: String, action: suspend () -> T): T =
        if (provider == GameMetadataProviderIds.SCREENSCRAPER) screenScraperSlots.withPermit { action() } else action()

    private fun checkScreenScraperQuota(payload: JsonObject) {
        val user = payload.obj("response").obj("ssuser")
        val scope = credentialScope(GameMetadataProviderIds.SCREENSCRAPER)
        val perMinute = (user.text("maxrequestspermin").toLongOrNull()
            ?: user.text("maxrequestsperdmin").toLongOrNull())?.takeIf { it > 0 }
        if (perMinute != null) {
            val interval = (60_000L / perMinute).coerceAtLeast(250L)
            minimumIntervals[scope] = interval
            nextRequests[scope] = maxOf(nextRequests[scope] ?: 0L, System.currentTimeMillis() + interval)
        }
        val max = user.text("maxrequestsperday").toLongOrNull()?.takeIf { it > 0 }
        val used = user.text("requeststoday").toLongOrNull()
        val maxMissing = user.text("maxrequestskoperday").toLongOrNull()?.takeIf { it > 0 }
        val missing = user.text("requestskotoday").toLongOrNull()
        if (max != null && used != null && used >= max || maxMissing != null && missing != null && missing >= maxMissing)
            cooldowns[scope] = System.currentTimeMillis() + screenScraperDayResetWait()
    }

    private fun checkGamesDbQuota(payload: JsonObject, rejected: Boolean) {
        val remaining = payload.text("remaining_monthly_allowance").toLongOrNull()
        val extra = payload.text("extra_allowance").toLongOrNull() ?: 0L
        if (remaining != null && remaining <= 0 && extra <= 0) {
            val wait = payload.text("allowance_refresh_timer").toLongOrNull()?.coerceIn(1, 31L * 24L * 60L * 60L)?.times(1000L) ?: 60_000L
            cooldowns[credentialScope(GameMetadataProviderIds.THEGAMESDB)] = System.currentTimeMillis() + wait
            if (rejected) throw GameMetadataProviderException(GameMetadataFailure.RATE_LIMIT, wait)
        }
    }

    private fun credentialScope(provider: String): String {
        val keys = settings.credentials()
        val identity = when (provider) {
            GameMetadataProviderIds.SCREENSCRAPER -> keys.screenScraperDevId.orEmpty() + "\u0000" + keys.screenScraperUser.orEmpty()
            GameMetadataProviderIds.THEGAMESDB -> keys.theGamesDbApiKey.orEmpty()
            else -> provider
        }
        return provider + ":" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }

    private fun checkStatus(provider: String, response: Response) {
        if (response.isSuccessful) return
        val kind = gameMetadataHttpFailure(provider, response.code)
        if (kind == GameMetadataFailure.RATE_LIMIT) {
            val defaultWait = if (provider == GameMetadataProviderIds.SCREENSCRAPER && response.code in setOf(430, 431)) screenScraperDayResetWait() else 60_000L
            val wait = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1L, 86_400L)?.times(1000L) ?: defaultWait
            rateLimited(provider, wait)
        }
        throw GameMetadataProviderException(kind)
    }

    private fun rateLimited(provider: String, wait: Long): Nothing {
        cooldowns[credentialScope(provider)] = System.currentTimeMillis() + wait
        throw GameMetadataProviderException(GameMetadataFailure.RATE_LIMIT, wait)
    }

    private fun authenticatedUrl(provider: String, input: HttpUrl): HttpUrl {
        val keys = settings.credentials()
        // Redirects and stored URLs are untrusted. Strip supplied secrets before attaching the
        // current device identity, and never forward API credentials to an image CDN.
        val builder = credentialFreeGameUrl(input).newBuilder()
        if (provider == GameMetadataProviderIds.SCREENSCRAPER && input.host == "api.screenscraper.fr") {
            if (!keys.screenScraperReady) throw GameMetadataProviderException(GameMetadataFailure.CREDENTIALS)
            builder.setQueryParameter("devid", keys.screenScraperDevId).setQueryParameter("devpassword", keys.screenScraperDevPassword)
                .setQueryParameter("softname", "Ambient TV")
            if (!keys.screenScraperUser.isNullOrBlank()) builder.setQueryParameter("ssid", keys.screenScraperUser)
                .setQueryParameter("sspassword", keys.screenScraperPassword)
        }
        if (provider == GameMetadataProviderIds.THEGAMESDB && input.host == "api.thegamesdb.net") {
            if (!keys.theGamesDbReady) throw GameMetadataProviderException(GameMetadataFailure.CREDENTIALS)
            builder.setQueryParameter("apikey", keys.theGamesDbApiKey)
        }
        return builder.build()
    }

    private suspend fun <T> request(provider: String, url: HttpUrl, http: OkHttpClient = client, consume: (Response) -> T): T {
        if (url.host in setOf("api.screenscraper.fr", "api.thegamesdb.net")) {
            val scope = credentialScope(provider)
            val until = cooldowns[scope] ?: 0L
            if (until > System.currentTimeMillis()) throw GameMetadataProviderException(GameMetadataFailure.RATE_LIMIT, until - System.currentTimeMillis())
            val wait = (nextRequests[scope] ?: 0L) - System.currentTimeMillis()
            if (wait > 0) delay(wait.coerceAtMost(60_000L))
            nextRequests[scope] = System.currentTimeMillis() + (minimumIntervals[scope] ?: 250L)
        }
        return suspendCancellableCoroutine { continuation ->
            val active = AtomicReference<Call?>()
            continuation.invokeOnCancellation { active.get()?.cancel() }
            fun failure(error: Throwable) {
                if (!continuation.isActive) return
                continuation.resumeWithException(if (error is GameMetadataProviderException) error
                    else GameMetadataProviderException(GameMetadataFailure.NETWORK))
            }
            fun enqueue(target: HttpUrl, redirects: Int) {
                if (!continuation.isActive) return
                if (!allowedGameProviderHost(provider, target) || redirects > 3) {
                    failure(GameMetadataProviderException(GameMetadataFailure.INVALID_RESPONSE)); return
                }
                val request = try { Request.Builder().url(authenticatedUrl(provider, target)).header("User-Agent", "Ambient-TV").build() }
                    catch (error: Exception) { failure(error); return }
                val call = http.newCall(request)
                active.set(call)
                if (!continuation.isActive) { call.cancel(); return }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = failure(e)
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (!continuation.isActive) return
                            if (response.code in setOf(301, 302, 303, 307, 308)) {
                                val next = response.header("Location")?.let { target.resolve(it) }
                                if (next == null) failure(GameMetadataProviderException(GameMetadataFailure.INVALID_RESPONSE))
                                else enqueue(next, redirects + 1)
                                return
                            }
                            try {
                                val result = consume(response)
                                if (continuation.isActive) continuation.resume(result)
                            } catch (error: Exception) { failure(error) }
                        }
                    }
                })
            }
            enqueue(url, 0)
        }
    }

    companion object {
        const val MAX_IMAGE_BYTES = 8L * 1024L * 1024L
        private const val MAX_JSON_BYTES = 8L * 1024L * 1024L
        private const val LOOKUP_MAX_AGE = 30L * 24L * 60L * 60L * 1000L
        internal const val MAX_MATCHES = 24
        internal const val MAX_ARTWORK_CHOICES = 30
        private const val MAX_DATABASE_BYTES = 32L * 1024L * 1024L
        internal const val LIBRETRO_DATABASE_HOST = "raw.githubusercontent.com"
        internal const val LIBRETRO_DATABASE_ROOT = "libretro/libretro-database/master"
    }
}

internal fun allowedGameProviderHost(provider: String, url: HttpUrl): Boolean =
    url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() && url.port == 443 && when (provider) {
        GameMetadataProviderIds.LIBRETRO -> url.host == "thumbnails.libretro.com" || allowedLibretroArtworkMirror(url)
        GameMetadataProviderIds.SCREENSCRAPER -> url.host in setOf("api.screenscraper.fr", "www.screenscraper.fr", "screenscraper.fr", "media.screenscraper.fr")
        GameMetadataProviderIds.THEGAMESDB -> url.host in setOf("api.thegamesdb.net", "cdn.thegamesdb.net", "images.thegamesdb.net")
        GameMetadataProviderIds.LIBRETRO_DATABASE -> url.host == GameMetadataProviders.LIBRETRO_DATABASE_HOST &&
            (url.encodedPath.startsWith("/" + GameMetadataProviders.LIBRETRO_DATABASE_ROOT + "/metadat/") ||
                url.pathSegments == listOf("libretro", "libretro-database", "master", "dat", "Nintendo - Wii U.dat"))
        else -> false
    }

private val LIBRETRO_ARTWORK_DIRECTORIES = setOf("Named_Boxarts", "Named_Snaps", "Named_Titles")
private val LIBRETRO_ENCODED_SEPARATOR = Regex("%(?:25)*(?:2f|5c)", RegexOption.IGNORE_CASE)

private fun libretroPngFilename(value: String): Boolean = value.length > 4 && value.endsWith(".png") &&
    value.none { it == '/' || it == '\\' || it.isISOControl() } &&
    // Do not let encoded separators survive as a second path after a downstream decode.
    !LIBRETRO_ENCODED_SEPARATOR.containsMatchIn(value)

/** GitHub is an artwork host only inside explicitly mapped, official Libretro thumbnail repos. */
private fun allowedLibretroArtworkMirror(url: HttpUrl): Boolean {
    if (url.host != "raw.githubusercontent.com" || url.query != null || url.fragment != null) return false
    val path = url.pathSegments
    return path.size == 5 && path[0] == "libretro-thumbnails" &&
        path[1] in GameMetadataPlatforms.libretroThumbnailRepositories.values && path[2] == "master" &&
        path[3] in LIBRETRO_ARTWORK_DIRECTORIES && libretroPngFilename(path[4])
}

/** A transport fallback only: candidates/cache identities retain the canonical thumbnail URL. */
internal fun libretroArtworkMirrorUrl(primary: HttpUrl): HttpUrl? {
    if (primary.host != "thumbnails.libretro.com" || !allowedGameProviderHost(GameMetadataProviderIds.LIBRETRO, primary)) return null
    val path = primary.pathSegments
    if (path.size != 3 || path[1] !in LIBRETRO_ARTWORK_DIRECTORIES || !libretroPngFilename(path[2])) return null
    val repository = GameMetadataPlatforms.libretroThumbnailRepositories[path[0]] ?: return null
    // Construct afresh: no credentials, query parameters or fragments can reach the mirror.
    return HttpUrl.Builder().scheme("https").host("raw.githubusercontent.com")
        .addPathSegment("libretro-thumbnails").addPathSegment(repository).addPathSegment("master")
        .addPathSegment(path[1])
        .addEncodedPathSegment(primary.encodedPathSegments.last().replace("[", "%5B").replace("]", "%5D")).build()
}

/** Shared with parsers/tests: no application identity or user credentials may survive to SQLite. */
internal fun sanitizedGameArtworkUrl(provider: String, input: String?): String? {
    val raw = sanitizedGameMetadataUrl(input)?.toHttpUrlOrNull() ?: return null
    val url = if (raw.scheme == "http" && raw.port == 80) raw.newBuilder().scheme("https").port(443).build() else raw
    if (!allowedGameProviderHost(provider, url)) return null
    return credentialFreeGameUrl(url).toString()
}

internal fun credentialFreeGameUrl(url: HttpUrl): HttpUrl {
    // OkHttp permits literal square brackets in a path, but the strict
    // java.net.URI persistence boundary requires them percent-encoded.
    return sanitizedGameMetadataUrl(url.toUri().toASCIIString())?.toHttpUrlOrNull()
        ?: throw GameMetadataProviderException(GameMetadataFailure.INVALID_RESPONSE)
}

internal fun gameMetadataHttpFailure(provider: String, code: Int): GameMetadataFailure = when {
    code in setOf(429, 430, 431) -> GameMetadataFailure.RATE_LIMIT
    provider == GameMetadataProviderIds.SCREENSCRAPER && code in setOf(401, 423) -> GameMetadataFailure.RATE_LIMIT
    code == 401 || code == 403 -> GameMetadataFailure.CREDENTIALS
    else -> GameMetadataFailure.NETWORK
}

private fun screenScraperDayResetWait(): Long {
    val zone = java.time.ZoneId.of("Europe/Paris")
    val nextDay = java.time.LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return (nextDay - System.currentTimeMillis()).coerceIn(1_000L, TimeUnit.HOURS.toMillis(26))
}

internal fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.text(key: String): String = this[key].scalarText()
internal fun JsonElement?.scalarText(): String = when (this) {
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonObject -> listOf("text", "name", "nom", "value").firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull }.orEmpty()
    else -> ""
}
internal fun JsonObject.objects(key: String): List<JsonObject> = when (val value = this[key]) {
    is JsonArray -> value.mapNotNull { it as? JsonObject }
    is JsonObject -> if (value["id"] != null) listOf(value) else value.values.mapNotNull { it as? JsonObject }
    else -> emptyList()
}
