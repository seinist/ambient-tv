package com.ambient.tv.data.weather

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.memoryPreferences
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WeatherMapRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    // Keep the generic I/O/concurrency fixtures tile-aligned; centered mosaics have their own
    // rendering regressions, including all required background pieces and dateline strips.
    private val viewport = WeatherMapViewport(6, 33, 21)
    private val now = Instant.now().minusSeconds(600)
    private val frames = listOf(
        WeatherMapFrame(now.minusSeconds(300).toString(), now.minusSeconds(300).toEpochMilli()),
        WeatherMapFrame(now.toString(), now.toEpochMilli()),
    )
    private val series = WeatherMapSeries("dwd", frames, now.toEpochMilli())

    @Test fun constructionDoesNotReadMapCacheOrStartHttp() {
        fixture { response(it) }.use { fixture ->
            assertEquals(0, fixture.cacheDirectoryReads.get())
            assertEquals(0, fixture.requests.get())
            fixture.preferences.weatherSettings = fixture.preferences.weatherSettings.copy(enabled = false)
            assertEquals(0, fixture.cacheDirectoryReads.get())
            assertEquals(0, fixture.requests.get())
        }
    }

    @Test fun independentWeatherAndBackgroundRequestsRunTogether() = runBlocking {
        val started = CountDownLatch(2)
        fixture { chain ->
            started.countDown()
            if (!started.await(3, TimeUnit.SECONDS)) throw IOException("Layers were loaded serially")
            response(chain)
        }.use { fixture ->
            val result = withTimeout(5_000) { fixture.frame() }
            assertNull(result.error)
            assertNotNull(result.image)
            assertFalse(result.image!!.baseMissing)
            assertEquals(2, fixture.requests.get())
        }
    }

    @Test fun optionalGracePeriodStartsAfterTheWeatherImageArrives() = runBlocking {
        val started = CountDownLatch(2)
        val weather = CountDownLatch(1)
        val background = CountDownLatch(1)
        fixture(optionalTimeoutMillis = 50) { chain ->
            started.countDown()
            val release = if (chain.request().url.host == "tile.openstreetmap.org") background else weather
            if (!release.await(3, TimeUnit.SECONDS)) throw IOException("Fixture timed out")
            response(chain)
        }.use { fixture ->
            val loading = async(Dispatchers.IO) { fixture.frame() }
            try {
                assertTrue(started.await(3, TimeUnit.SECONDS))
                // Both network calls are slower than the optional tail budget. That is valid:
                // the optional layer must get the time already spent loading the required one.
                delay(150)
                background.countDown()
                weather.countDown()
                val result = withTimeout(3_000) { loading.await() }
                assertNotNull(result.image)
                assertFalse(result.image!!.baseMissing)
            } finally { background.countDown(); weather.countDown(); loading.cancel() }
        }
    }

    @Test fun stalledOptionalLayerDoesNotDelayEveryAnimationFrame() = runBlocking {
        val background = CountDownLatch(1)
        val backgroundRequests = AtomicInteger()
        fixture(optionalTimeoutMillis = 50) { chain ->
            if (chain.request().url.host == "tile.openstreetmap.org") {
                backgroundRequests.incrementAndGet()
                background.await(3, TimeUnit.SECONDS)
            }
            response(chain)
        }.use { fixture ->
            try {
                val first = withTimeout(2_000) { fixture.frame(0) }
                val second = withTimeout(2_000) { fixture.frame(1) }
                assertTrue(first.image!!.baseMissing)
                assertTrue(second.image!!.baseMissing)
                assertEquals(1, backgroundRequests.get())
                assertEquals(3, fixture.requests.get())
            } finally { background.countDown() }
        }
    }

    @Test fun expiredBackgroundRemainsUsableWhenItsRefreshExceedsTheGracePeriod() = runBlocking {
        val directory = temporary.newFolder()
        fixture(directory = directory) { response(it) }.use { fixture ->
            assertFalse(fixture.frame(0).image!!.baseMissing)
        }
        File(directory, "weather-maps-v1").listFiles().orEmpty()
            .filter { it.name.startsWith("raw-") }.forEach { file ->
                assertTrue(file.setLastModified(System.currentTimeMillis() - 8 * 24 * 60 * 60_000L))
            }
        val background = CountDownLatch(1)
        fixture(optionalTimeoutMillis = 50, directory = directory) { chain ->
            if (chain.request().url.host == "tile.openstreetmap.org") background.await(3, TimeUnit.SECONDS)
            response(chain)
        }.use { fixture ->
            try {
                val result = withTimeout(2_000) { fixture.frame(1) }
                assertNotNull(result.image)
                assertFalse(result.image!!.baseMissing)
                assertEquals(2, fixture.requests.get())
            } finally { background.countDown() }
        }
    }

    @Test fun media3FrameFileCarriesItsActualLayerStatusAndReusesTheCache() = runBlocking {
        fixture { response(it) }.use { fixture ->
            val first = fixture.repository.playbackFrameFile(
                WeatherMapProvider.DWD, viewport, series, 0, networkAllowed = true)
            assertNotNull(first)
            assertTrue(first!!.file.isFile)
            assertFalse(first.baseMissing)
            assertFalse(first.coverageMissing)
            val again = fixture.repository.playbackFrameFile(
                WeatherMapProvider.DWD, viewport, series, 0, networkAllowed = true)
            assertEquals(first.file, again!!.file)
            assertEquals(2, fixture.requests.get())
        }
    }

    @Test fun media3FrameFileReportsAnUnavailableBaseLayer() = runBlocking {
        fixture { chain ->
            response(chain, if (chain.request().url.host == "tile.openstreetmap.org") 503 else 200)
        }.use { fixture ->
            val frame = fixture.repository.playbackFrameFile(
                WeatherMapProvider.DWD, viewport, series, 1, networkAllowed = true)
            assertNotNull(frame)
            assertTrue(frame!!.baseMissing)
            assertFalse(frame.coverageMissing)
            assertTrue(frame.file.isFile)
        }
    }

    @Test fun concurrentRequestsAndPlaybackReuseTheDecodedFrame() = runBlocking {
        val weatherStarted = CountDownLatch(1)
        val weather = CountDownLatch(1)
        fixture { chain ->
            if (chain.request().url.host != "tile.openstreetmap.org") {
                weatherStarted.countDown()
                weather.await(3, TimeUnit.SECONDS)
            }
            response(chain)
        }.use { fixture ->
            val requests = (0 until 4).map { async(Dispatchers.IO) { fixture.frame() } }
            try {
                assertTrue(weatherStarted.await(3, TimeUnit.SECONDS))
                weather.countDown()
                val results = withTimeout(5_000) { requests.awaitAll() }
                val bitmap = results.first().image!!.bitmap
                assertTrue(results.all { it.image?.bitmap === bitmap })
                val replay = fixture.frame()
                assertSame(bitmap, replay.image!!.bitmap)
                assertTrue(replay.image.fromCache)
                assertEquals(2, fixture.requests.get())
            } finally { weather.countDown(); requests.forEach { it.cancel() } }
        }
    }

    @Test fun differentFramesShareTheirOneBackgroundRequest() = runBlocking {
        val weatherStarted = CountDownLatch(2)
        val backgroundRequests = AtomicInteger()
        fixture { chain ->
            if (chain.request().url.host == "tile.openstreetmap.org") {
                backgroundRequests.incrementAndGet()
                if (!weatherStarted.await(3, TimeUnit.SECONDS)) throw IOException("Weather did not load concurrently")
            } else weatherStarted.countDown()
            response(chain)
        }.use { fixture ->
            val results = withTimeout(5_000) {
                listOf(async(Dispatchers.IO) { fixture.frame(0) }, async(Dispatchers.IO) { fixture.frame(1) }).awaitAll()
            }
            assertTrue(results.all { it.image != null && !it.image.baseMissing })
            assertEquals(1, backgroundRequests.get())
            assertEquals(3, fixture.requests.get())
        }
    }

    @Test fun explicitRefreshCanRepairAMissingBackgroundDuringItsCooldown() = runBlocking {
        val backgroundRequests = AtomicInteger()
        fixture { chain ->
            when {
                chain.request().url.queryParameter("request") == "GetCapabilities" -> capabilities(chain)
                chain.request().url.host == "tile.openstreetmap.org" -> response(chain,
                    if (backgroundRequests.incrementAndGet() == 1) 503 else 200)
                else -> response(chain)
            }
        }.use { fixture ->
            val first = fixture.repository.load(WeatherMapProvider.DWD, viewport, true)
            assertTrue(first.image!!.baseMissing)
            val cached = fixture.repository.load(WeatherMapProvider.DWD, viewport, true)
            assertSame(first.image.bitmap, cached.image!!.bitmap)
            assertTrue(cached.image.baseMissing)
            assertEquals(1, backgroundRequests.get())
            val repaired = fixture.repository.load(WeatherMapProvider.DWD, viewport, true, force = true)
            assertFalse(repaired.image!!.baseMissing)
            assertEquals(2, backgroundRequests.get())
        }
    }

    @Test fun publishedPathChangesAtTheSameTimestampInvalidateTheRenderedFrame() = runBlocking {
        val radar = WeatherMapSeries("rainviewer", listOf(
            WeatherMapFrame(now.toString(), now.toEpochMilli(), "/v2/radar/aa11"),
            WeatherMapFrame(now.toString(), now.toEpochMilli(), "/v2/radar/bb22"),
        ), now.toEpochMilli())
        val radarRequests = AtomicInteger()
        fixture { chain ->
            if (chain.request().url.encodedPath.startsWith("/v2/radar/")) radarRequests.incrementAndGet()
            response(chain)
        }.use { fixture ->
            val first = fixture.repository.frame(WeatherMapProvider.RAINVIEWER, viewport, radar, 0, true)
            val second = fixture.repository.frame(WeatherMapProvider.RAINVIEWER, viewport, radar, 1, true)
            assertNotNull(first.image)
            assertNotNull(second.image)
            assertNotSame(first.image!!.bitmap, second.image!!.bitmap)
            assertEquals(2, radarRequests.get())
        }
    }

    @Test fun rainviewerRateLimitIsSharedAcrossItsHostsAndDoesNotBlockDwd() = runBlocking {
        val rainviewerRequests = AtomicInteger()
        fixture { chain ->
            when {
                chain.request().url.host.endsWith(".rainviewer.com") -> {
                    rainviewerRequests.incrementAndGet()
                    response(chain, 429).newBuilder().header("Retry-After", "120").build()
                }
                chain.request().url.queryParameter("request") == "GetCapabilities" -> capabilities(chain)
                else -> response(chain)
            }
        }.use { fixture ->
            val radar = WeatherMapSeries("rainviewer", listOf(
                WeatherMapFrame(now.toString(), now.toEpochMilli(), "/v2/radar/aa11")), now.toEpochMilli())
            val first = fixture.repository.frame(WeatherMapProvider.RAINVIEWER, viewport, radar, 0, true)
            assertEquals(WeatherMapError.RATE_LIMITED, first.error)
            val manifest = fixture.repository.load(WeatherMapProvider.RAINVIEWER, viewport, true, force = true)
            assertEquals(WeatherMapError.RATE_LIMITED, manifest.error)
            assertEquals(1, rainviewerRequests.get())
            val dwd = fixture.repository.load(WeatherMapProvider.DWD, viewport, true)
            assertNotNull(dwd.image)
            assertNull(dwd.error)
        }
    }

    @Test fun disablingWeatherStillAllowsCachedFramesWithoutNewNetworkCalls() = runBlocking {
        fixture { response(it) }.use { fixture ->
            val first = fixture.frame()
            fixture.preferences.weatherSettings = fixture.preferences.weatherSettings.copy(enabled = false)
            val cached = fixture.frame()
            assertSame(first.image!!.bitmap, cached.image!!.bitmap)
            assertEquals(WeatherMapError.NETWORK_DISABLED, cached.error)
            assertEquals(2, fixture.requests.get())
        }
    }

    @Test fun invalidPersistedSourceHostIsDiscardedAndFreshLoadingCanRecover() = runBlocking {
        fixture { chain ->
            if (chain.request().url.host == "api.rainviewer.com") {
                val manifest = """{"host":"https://tilecache.rainviewer.com","radar":{"past":[
                    {"time":${now.epochSecond},"path":"/v2/radar/aa11"}]}}"""
                response(chain).newBuilder().body(manifest.toResponseBody("application/json".toMediaType())).build()
            } else response(chain)
        }.use { fixture ->
            val corrupt = WeatherMapFrame(now.toString(), now.toEpochMilli(), "/v2/radar/aa11", "https://unexpected.invalid")
            val cachedSeries = WeatherMapSeries("rainviewer", listOf(corrupt), now.toEpochMilli())
            val folder = File(fixture.directory, "weather-maps-v1").apply { mkdirs() }
            File(folder, "last-rainviewer-${viewport.key}.json").writeText("""{
                "viewport":${Json.encodeToString(viewport)},"series":${Json.encodeToString(cachedSeries)},
                "frame":${Json.encodeToString(corrupt)}}""")
            assertNull(fixture.repository.cached(WeatherMapProvider.RAINVIEWER, viewport).image)
            assertEquals(0, fixture.requests.get())
            val loaded = fixture.repository.load(WeatherMapProvider.RAINVIEWER, viewport, true)
            assertNull(loaded.error)
            assertNotNull(loaded.image)
            assertEquals("https://tilecache.rainviewer.com", loaded.image!!.frame.tileHost)
        }
    }

    private fun fixture(optionalTimeoutMillis: Long = 2_500, directory: File = temporary.newFolder(),
        answer: (Interceptor.Chain) -> Response): Fixture {
        val preferences = AppPreferences(memoryPreferences()).apply { weatherSettings = WeatherSettings(enabled = true) }
        val requests = AtomicInteger()
        val cacheDirectoryReads = AtomicInteger()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getCacheDir(): File = directory.also { cacheDirectoryReads.incrementAndGet() }
        }
        val client = OkHttpClient.Builder().addInterceptor { chain -> requests.incrementAndGet(); answer(chain) }.build()
        return Fixture(WeatherMapRepository(context, preferences, client, optionalTimeoutMillis), preferences, client,
            requests, cacheDirectoryReads, directory)
    }

    private inner class Fixture(val repository: WeatherMapRepository, val preferences: AppPreferences, val client: OkHttpClient,
        val requests: AtomicInteger, val cacheDirectoryReads: AtomicInteger, val directory: File) : AutoCloseable {
        suspend fun frame(index: Int = 0) = repository.frame(WeatherMapProvider.DWD, viewport, series, index, true)
        override fun close() {
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun response(chain: Interceptor.Chain, code: Int = 200) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .body(pixel.toResponseBody("image/png".toMediaType())).build()

    private fun capabilities(chain: Interceptor.Chain): Response {
        val xml = """<WMS_Capabilities xmlns="http://www.opengis.net/wms"><Capability><Layer>
            <Name>Radar_wn-analysis_1x1km_ger</Name><Dimension name="time" default="$now">$now</Dimension>
            </Layer></Capability></WMS_Capabilities>"""
        return response(chain).newBuilder().body(xml.toResponseBody("text/xml".toMediaType())).build()
    }

    private companion object {
        val pixel: ByteArray = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNQcGj4DwADRAHgMqq/hAAAAABJRU5ErkJggg==")
    }
}
