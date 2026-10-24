package com.ambient.tv.trailers

import com.ambient.tv.data.local.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TmdbTrailerRepositoryTest {
    private val tokens = TokenStore(memoryPreferences(), AppPreferences(memoryPreferences()))
    private val requests = java.util.Collections.synchronizedList(mutableListOf<Request>())
    private var status = 200
    private var retryAfter: String? = null
    private var responder: (Request) -> String = { request ->
        val language = request.url.queryParameter("language").orEmpty().substringBefore('-')
        if (request.url.encodedPath.endsWith("/videos"))
            """{"results":[{"key":"abcdefghijk","site":"YouTube","type":"Trailer","iso_639_1":"$language"}]}"""
        else "{}"
    }
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
            .message("fixture").apply { retryAfter?.let { header("Retry-After", it) } }
            .body(responder(request).toResponseBody("application/json".toMediaType())).build()
    }.build()
    private var nowNanos = 1L
    private val repository = TmdbTrailerRepository(tokens, client) { nowNanos }

    @Test fun missingKeyNeverMakesARequest() = runBlocking {
        assertTrue(repository.feed(TrailerFeed.CINEMA, "de-DE", "DE").exceptionOrNull() is TrailerConfigurationMissing)
        assertTrue(requests.isEmpty())
    }

    @Test fun rejectedReplacementPreservesPreviouslyValidatedKey() = runBlocking {
        assertTrue(repository.connect("a".repeat(32)).isSuccess)
        assertEquals("a".repeat(32), requests.single().url.queryParameter("api_key"))
        assertNull(requests.single().header("Authorization"))
        status = 401
        assertTrue(repository.connect("replacement-token").isFailure)
        assertEquals("a".repeat(32), tokens.tmdbApiKey)
        assertEquals("Bearer replacement-token", requests.last().header("Authorization"))
    }

    @Test fun preferredLanguageShortCircuitsFallbackAndCacheIsScopedToCredentialGeneration() = runBlocking {
        repository.connect("first-token").getOrThrow()
        requests.clear()
        val videos = repository.videos("movie", 42, "de-DE", "fr")
        assertEquals(listOf("de-DE"), requests.map { it.url.queryParameter("language") })
        assertEquals("de", videos.single().language)
        repository.videos("movie", 42, "de-DE", "fr")
        assertEquals(1, requests.size)
        // Backup import can replace the token without going through connect().
        tokens.tmdbApiKey = "restored-token"
        repository.videos("movie", 42, "de-DE", "fr")
        assertEquals(2, requests.size)
        assertEquals("Bearer restored-token", requests.last().header("Authorization"))
        repository.disconnect()
        assertFalse(repository.configured)
    }

    @Test fun languageFallbackUsesOriginalThenEnglish() = runBlocking {
        tokens.tmdbApiKey = "token"
        responder = { request ->
            val language = request.url.queryParameter("language").orEmpty()
            if (language == "en") """{"results":[{"key":"abcdefghijk","site":"YouTube","type":"Trailer","iso_639_1":"en"}]}"""
            else """{"results":[]}"""
        }
        val videos = repository.videos("movie", 42, "de-DE", "fr")
        assertEquals(listOf("de-DE", "fr", "en"), requests.map { it.url.queryParameter("language") })
        assertEquals("en", videos.single().language)
    }

    @Test fun pageMetadataAndPlayableEntriesArePreserved() = runBlocking {
        tokens.tmdbApiKey = "token"
        responder = { request ->
            if (request.url.encodedPath.endsWith("/videos"))
                """{"results":[{"key":"abcdefghijk","site":"YouTube","type":"Trailer","iso_639_1":"de"}]}"""
            else """{"page":2,"total_pages":5,"results":[{"id":7,"title":"Film","release_date":"2026-01-02"}]}"""
        }
        val page = repository.feedPage(TrailerFeed.CINEMA, 2, "de-DE", "DE").getOrThrow()
        assertEquals(2, page.page)
        assertEquals(5, page.totalPages)
        assertTrue(page.hasNextPage)
        assertEquals(7, page.entries.single().title.id)
        assertEquals("2", requests.first().url.queryParameter("page"))
    }

    @Test fun tmdbFailuresExposeActionableTypesAndRetryDelay() = runBlocking {
        tokens.tmdbApiKey = "token"
        status = 401
        assertTrue(repository.feed(TrailerFeed.CINEMA, "de-DE", "DE").exceptionOrNull() is TrailerAuthenticationFailed)
        status = 404
        assertTrue(repository.feed(TrailerFeed.CINEMA, "de-DE", "DE").exceptionOrNull() is TrailerNotFound)
        status = 429
        retryAfter = "17"
        val limited = repository.feed(TrailerFeed.CINEMA, "de-DE", "DE").exceptionOrNull()
        assertEquals(17L, (limited as TrailerRateLimited).retryAfterSeconds)
        val requestCount = requests.size
        val blocked = repository.feed(TrailerFeed.SERIES, "en-US", "US").exceptionOrNull()
        assertTrue(blocked is TrailerRateLimited)
        assertEquals(requestCount, requests.size)
        nowNanos += java.util.concurrent.TimeUnit.SECONDS.toNanos(18)
        status = 503
        assertTrue(repository.feed(TrailerFeed.CINEMA, "de-DE", "DE").exceptionOrNull() is TrailerServiceUnavailable)
    }

    @Test fun concurrentIdenticalRequestsShareOneHttpCall() = runBlocking {
        val callCount = AtomicInteger()
        val coalescingClient = OkHttpClient.Builder().addInterceptor { chain ->
            callCount.incrementAndGet()
            Thread.sleep(100)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"results":[{"key":"abcdefghijk","site":"YouTube","type":"Trailer","iso_639_1":"de"}]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        tokens.tmdbApiKey = "token"
        val subject = TmdbTrailerRepository(tokens, coalescingClient)
        listOf(async { subject.videos("movie", 42, "de-DE", "de") },
            async { subject.videos("movie", 42, "de-DE", "de") }).awaitAll()
        assertEquals(1, callCount.get())
    }

    @Test fun globalSearchMergesMoviesAndSeriesByPopularity() = runBlocking {
        tokens.tmdbApiKey = "token"
        responder = { request -> when {
            request.url.encodedPath.endsWith("/search/movie") ->
                """{"results":[{"id":1,"title":"Film","popularity":4.0}]}"""
            request.url.encodedPath.endsWith("/search/tv") ->
                """{"results":[{"id":2,"name":"Serie","popularity":9.0}]}"""
            else -> "{}"
        } }
        val results = repository.search("test", "de-DE", "DE").getOrThrow()
        assertEquals(listOf("tv:2", "movie:1"), results.map { it.key })
        assertEquals("DE", requests.single { it.url.encodedPath.endsWith("/search/movie") }.url.queryParameter("region"))
        assertNull(requests.single { it.url.encodedPath.endsWith("/search/tv") }.url.queryParameter("region"))
    }

    @Test fun oneFailedTitleKeepsTheRestOfTheCatalogPage() = runBlocking {
        val partialClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val isFailedVideo = request.url.encodedPath.contains("/movie/2/videos")
            val body = when {
                request.url.encodedPath.endsWith("/movie/now_playing") ->
                    """{"page":1,"total_pages":1,"results":[{"id":1,"title":"One"},{"id":2,"title":"Two"}]}"""
                else -> """{"results":[{"key":"abcdefghijk","site":"YouTube","type":"Trailer","iso_639_1":"de"}]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (isFailedVideo) 503 else 200).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        tokens.tmdbApiKey = "token"
        val page = TmdbTrailerRepository(tokens, partialClient)
            .feedPage(TrailerFeed.CINEMA, 1, "de-DE", "DE").getOrThrow()
        assertTrue(page.partialFailure)
        assertEquals(listOf(1), page.entries.map { it.title.id })
    }

    @Test fun duplicateTitlesAndApiPageLimitAreHandled() = runBlocking {
        tokens.tmdbApiKey = "token"
        responder = { request ->
            if (request.url.encodedPath.endsWith("/videos"))
                """{"results":[{"key":"abcdefghijk","site":"YouTube","type":"Trailer"}]}"""
            else """{"page":500,"total_pages":9000,"results":[{"id":7},{"id":7}]}"""
        }
        val page = repository.feedPage(TrailerFeed.MOVIES, 500, "en-US", "US").getOrThrow()
        assertEquals(1, page.entries.size)
        assertFalse(page.hasNextPage)
        val count = requests.size
        assertTrue(repository.feedPage(TrailerFeed.MOVIES, 501, "en-US", "US").isFailure)
        assertEquals(count, requests.size)
    }

    @Test fun queuedRequestsStopAtTheConcurrencyLimitAfter429() = runBlocking {
        tokens.tmdbApiKey = "token"
        val calls = AtomicInteger()
        val limitedClient = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Thread.sleep(30)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("fixture")
                .header("Retry-After", "30").body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val subject = TmdbTrailerRepository(tokens, limitedClient)
        val failures = (1..20).map { id -> async {
            runCatching { subject.videos("movie", id, "en-US", "en") }.exceptionOrNull()
        } }.awaitAll()
        assertTrue(failures.all { it is TrailerRateLimited })
        assertTrue("Queued requests ignored cooldown: ${calls.get()}", calls.get() <= 4)
    }

    @Test fun missingRetryAfterUsesCooldownAndCredentialReplacementResetsIt() = runBlocking {
        tokens.tmdbApiKey = "token"
        status = 429
        assertEquals(30L, (repository.feed(TrailerFeed.MOVIES, "en-US", "US").exceptionOrNull() as TrailerRateLimited).retryAfterSeconds)
        tokens.tmdbApiKey = "replacement"
        status = 200
        assertTrue(repository.feed(TrailerFeed.MOVIES, "en-US", "US").isSuccess)
    }

    @Test fun backdropComesFromTheTmdbIdAndNeverForEpisodes() = runBlocking {
        repository.connect("a".repeat(32)).getOrThrow()
        responder = { request -> if (request.url.encodedPath.endsWith("/movie/603")) """{"id":603,"backdrop_path":"/bg.jpg"}""" else "{}" }
        fun item(type: String) = com.ambient.tv.domain.model.MediaItem(ratingKey = "1", title = "The Matrix", type = type,
            index = null, thumbUrl = "poster", artUrl = null, year = 1999, summary = null,
            tagline = null, durationMs = null, viewOffsetMs = null, parentTitle = null,
            parentRatingKey = null, grandparentTitle = null, grandparentRatingKey = null,
            contentRating = null, rating = null, studio = null, guids = listOf("tmdb://603"))
        assertEquals("https://image.tmdb.org/t/p/w1280/bg.jpg", repository.backdropFor(item("movie"), "de-DE").getOrThrow())
        requests.clear()
        assertNull(repository.backdropFor(item("episode"), "de-DE").getOrThrow())
        assertTrue(requests.isEmpty())
    }

    @Test fun ambiguousRemakesAndMissingYearsNeverGuess() {
        val original = TrailerTitle(1, title = "The Film", releaseDate = "1990-01-01")
        val remake = TrailerTitle(2, title = "The Film", releaseDate = "2020-01-01")
        assertEquals(remake, matchTrailerTitle(listOf(original, remake), " the film ", 2020))
        assertNull(matchTrailerTitle(listOf(original, remake), "The Film", null))
        assertNull(matchTrailerTitle(listOf(remake, remake.copy(id = 3)), "The Film", 2020))
    }

    @Test fun onlyPlayableTrailersAndTeasersSurviveRanking() {
        val official = TrailerVideo("abcdefghijk", site = "YouTube", type = "Trailer", official = true, language = "de")
        val english = official.copy(key = "12345678901", language = "en")
        val values = listOf(english, official.copy(site = "Vimeo"), official.copy(key = "invalid"),
            official.copy(type = "Clip"), official, official)
        assertEquals(listOf(official, english), rankTrailers(values, "de", "en"))
    }
}
