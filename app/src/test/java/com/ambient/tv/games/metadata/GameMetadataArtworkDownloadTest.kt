package com.ambient.tv.games.metadata

import android.app.Application
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.data.local.memoryPreferences
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class GameMetadataArtworkDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val provider = GameMetadataProviderIds.LIBRETRO
    private val canonical = "https://thumbnails.libretro.com/Nintendo%20-%20Game%20Boy/Named_Boxarts/Tetris%20(World)%20(Rev%201).png"
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl9sAAAAASUVORK5CYII=")

    private class Fixture(val providers: GameMetadataProviders, val client: OkHttpClient, val requests: List<Request>) : AutoCloseable {
        override fun close() {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private fun fixture(answer: (Request) -> Response): Fixture {
        val preferences = AppPreferences(memoryPreferences())
        val tokens = TokenStore(memoryPreferences(), preferences).apply {
            setGameMetadataCredentials(GameMetadataCredentials(theGamesDbApiKey = "private-other-provider-key"))
        }
        val providers = GameMetadataProviders(GameMetadataSettings(preferences, tokens), Json,
            ConnectionPool(), Dispatcher(), RuntimeEnvironment.getApplication())
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val field = providers.javaClass.getDeclaredField("client").apply { isAccessible = true }
        val client = (field.get(providers) as OkHttpClient).newBuilder().addInterceptor { chain ->
            requests += chain.request()
            answer(chain.request())
        }.build()
        field.set(providers, client)
        return Fixture(providers, client, requests)
    }

    private fun response(request: Request, code: Int = 200, body: ResponseBody = png.toResponseBody("image/png".toMediaType()),
        location: String? = null): Response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(code).message("Test response").body(body).apply { if (location != null) header("Location", location) }.build()

    private fun missing(request: Request) = response(request, 404, byteArrayOf().toResponseBody())
    private fun destination() = File(temporary.newFolder(), "artwork.part").apply { writeText("stale staging bytes") }

    @Test fun primaryNotFoundUsesTheOfficialMirrorWithoutForwardingQueriesOrCredentials() = runBlocking {
        fixture { request -> if (request.url.host == "thumbnails.libretro.com") missing(request) else response(request) }.use { fixture ->
            val target = destination()
            assertTrue(withTimeout(5_000) { fixture.providers.downloadArtwork(provider, "$canonical?apikey=secret&download=1#secret", target) })
            assertArrayEquals(png, target.readBytes())
            assertEquals(2, fixture.requests.size)
            val mirror = fixture.requests.last()
            assertEquals(listOf("libretro-thumbnails", "Nintendo_-_Game_Boy", "master", "Named_Boxarts",
                "Tetris (World) (Rev 1).png"), mirror.url.pathSegments)
            assertEquals("raw.githubusercontent.com", mirror.url.host)
            assertNull(mirror.url.query)
            assertNull(mirror.url.fragment)
            fixture.requests.forEach { assertNull(it.header("Authorization")); assertNull(it.url.queryParameter("apikey")) }
        }
    }

    @Test fun aValidPrimaryImageDoesNotContactTheMirror() = runBlocking {
        fixture { response(it) }.use { fixture ->
            val target = destination()
            assertTrue(fixture.providers.downloadArtwork(provider, canonical, target))
            assertArrayEquals(png, target.readBytes())
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test fun invalidMimeEmptyAndOversizedBodiesCanRecoverAndNeverLeavePartialPrimaryBytes() = runBlocking {
        for (invalid in listOf("mime", "empty", "declared-size", "stream-size")) {
            fixture { request ->
                if (request.url.host != "thumbnails.libretro.com") response(request)
                else response(request, body = when (invalid) {
                    "mime" -> "upstream error page".toResponseBody("text/html".toMediaType())
                    "empty" -> byteArrayOf().toResponseBody("image/png".toMediaType())
                    else -> object : ResponseBody() {
                        private val source = Buffer().write(
                            if (invalid == "declared-size") png else ByteArray(GameMetadataProviders.MAX_IMAGE_BYTES.toInt() + 1))
                        override fun contentType(): MediaType = "image/png".toMediaType()
                        override fun contentLength(): Long = if (invalid == "declared-size") GameMetadataProviders.MAX_IMAGE_BYTES + 1 else -1
                        override fun source(): BufferedSource = source
                    }
                })
            }.use { fixture ->
                val target = destination()
                assertTrue(withTimeout(5_000) { fixture.providers.downloadArtwork(provider, canonical, target) })
                assertArrayEquals(invalid, png, target.readBytes())
                assertEquals(invalid, 2, fixture.requests.size)
            }
        }
    }

    @Test fun onlyTwoRealMissesReturnFalseWhileAnyInvalidAlternativeRemainsAnError() = runBlocking {
        for (primaryInvalid in listOf(false, true)) for (mirrorInvalid in listOf(false, true)) {
            fixture { request ->
                if (if (request.url.host == "thumbnails.libretro.com") primaryInvalid else mirrorInvalid)
                    response(request, body = "bad body".toResponseBody("text/html".toMediaType())) else missing(request)
            }.use { fixture ->
                val target = destination()
                val result = runCatching { fixture.providers.downloadArtwork(provider, canonical, target) }
                if (primaryInvalid || mirrorInvalid) {
                    assertEquals(GameMetadataFailure.INVALID_RESPONSE, (result.exceptionOrNull() as? GameMetadataProviderException)?.kind)
                } else assertEquals(false, result.getOrThrow())
                assertFalse(target.exists())
                assertEquals(2, fixture.requests.size)
            }
        }
    }

    @Test fun credentialsRateLimitsAndNetworkFailuresNeverTriggerFallback() = runBlocking {
        for ((status, expected) in listOf(401 to GameMetadataFailure.CREDENTIALS, 403 to GameMetadataFailure.CREDENTIALS,
            429 to GameMetadataFailure.RATE_LIMIT, 503 to GameMetadataFailure.NETWORK, 0 to GameMetadataFailure.NETWORK)) {
            fixture { request -> if (status == 0) throw IOException("offline") else response(request, status) }.use { fixture ->
                val target = destination()
                val failure = runCatching { fixture.providers.downloadArtwork(provider, canonical, target) }.exceptionOrNull()
                assertEquals(expected, (failure as? GameMetadataProviderException)?.kind)
                assertEquals(1, fixture.requests.size)
                assertFalse(target.exists())
            }
        }
    }

    @Test fun foreignMirrorRedirectsAreRejectedBeforeAnyForeignRequest() = runBlocking {
        fixture { request -> if (request.url.host == "thumbnails.libretro.com") missing(request)
            else response(request, 302, location = "https://raw.githubusercontent.com/other/private/master/file.png")
        }.use { fixture ->
            val target = destination()
            val failure = runCatching { fixture.providers.downloadArtwork(provider, canonical, target) }.exceptionOrNull()
            assertEquals(GameMetadataFailure.INVALID_RESPONSE, (failure as? GameMetadataProviderException)?.kind)
            assertEquals(2, fixture.requests.size)
            assertFalse(target.exists())
        }
    }

    @Test fun anotherProviderNeverUsesLibretroAsAnAutomaticFallback() = runBlocking {
        fixture { missing(it) }.use { fixture ->
            val target = destination()
            assertFalse(fixture.providers.downloadArtwork(GameMetadataProviderIds.THEGAMESDB,
                "https://cdn.thegamesdb.net/images/large/boxart/front/42.png", target))
            assertEquals(1, fixture.requests.size)
            assertFalse(target.exists())
        }
    }
}
