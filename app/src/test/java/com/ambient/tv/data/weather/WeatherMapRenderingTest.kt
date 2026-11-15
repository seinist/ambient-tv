package com.ambient.tv.data.weather

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.memoryPreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Check real raster pixels: URL/geometry tests alone cannot detect a stretched or shifted base map. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeatherMapRenderingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val viewport = WeatherMapViewport(3, 3, 2, offsetX = .25, offsetY = .5)
    private val provider = WeatherMapProvider.EUMETSAT
    private val now = Instant.parse("2026-11-15T18:00:00Z")
    private val frames = listOf(
        WeatherMapFrame(now.toString(), now.toEpochMilli()),
        WeatherMapFrame(now.plusSeconds(300).toString(), now.plusSeconds(300).toEpochMilli()),
    )
    private val series = WeatherMapSeries(provider.id, frames, now.toEpochMilli())

    @Test fun fractionalViewportCropsFourBaseTilesAndKeepsTheWeatherOverlayAligned() = runBlocking {
        val backgrounds = backgroundImages()
        val weather = png(512, 512) { x, y ->
            if (x in 248..263 && y in 248..263) Color.MAGENTA else Color.TRANSPARENT
        }
        fixture { chain -> response(chain, backgrounds[chain.request().url.encodedPath] ?: weather) }.use { fixture ->
            val result = withTimeout(5_000) { fixture.frame(viewport) }
            assertNull(result.error)
            val image = requireNotNull(result.image)
            assertFalse(image.baseMissing)
            assertEquals(512, image.bitmap.width)
            assertEquals(512, image.bitmap.height)
            assertBackgroundQuadrants(image.bitmap, Color.YELLOW)
            // A quarter-tile horizontal offset puts the next tile at 3/4 of the image.
            assertEquals(Color.RED, image.bitmap.getPixel(380, 128))
            assertEquals(Color.GREEN, image.bitmap.getPixel(388, 128))
            // The half-tile vertical offset places the row boundary at the image centre.
            assertEquals(Color.RED, image.bitmap.getPixel(32, 252))
            assertEquals(Color.BLUE, image.bitmap.getPixel(32, 260))
            assertEquals(Color.MAGENTA, image.bitmap.getPixel(256, 256))
            assertEquals(5, fixture.requests.size)
            assertEquals(setOf("/3/3/2.png", "/3/4/2.png", "/3/3/3.png", "/3/4/3.png"),
                fixture.requests.filter { it.url.host == "tile.openstreetmap.org" }.map { it.url.encodedPath }.toSet())
        }
    }

    @Test fun datelineSatelliteUsesTwoBoundedStripsWithoutLeavingAGapOrSwappingSides() = runBlocking {
        val crossing = WeatherMapViewport(3, 7, 3, offsetX = .75, offsetY = .25)
        val transparent = png { _, _ -> Color.TRANSPARENT }
        fixture { chain ->
            val url = chain.request().url
            if (url.host == "tile.openstreetmap.org") response(chain, transparent)
            else {
                val west = url.queryParameter("BBOX")!!.substringBefore(',').toDouble()
                val width = url.queryParameter("WIDTH")!!.toInt()
                response(chain, png(width, 512) { _, _ -> if (west > 0) Color.RED else Color.GREEN })
            }
        }.use { fixture ->
            val result = withTimeout(5_000) { fixture.frame(crossing) }
            assertNull(result.error)
            val bitmap = requireNotNull(result.image).bitmap
            // Only the rightmost quarter of the western tile remains before the dateline.
            for (x in 0 until 512) {
                assertEquals("Satellite seam at x=$x", if (x < 128) Color.RED else Color.GREEN,
                    bitmap.getPixel(x, 256))
            }
            val weather = fixture.requests.filter { it.url.host == "view.eumetsat.int" }
            assertEquals(2, weather.size)
            assertEquals(setOf(128, 384), weather.map { it.url.queryParameter("WIDTH")!!.toInt() }.toSet())
            weather.forEach { request ->
                val bounds = request.url.queryParameter("BBOX")!!.split(',').map(String::toDouble)
                assertTrue(bounds.all { it.isFinite() && it in -20_037_508.342790..20_037_508.342790 })
                assertTrue(bounds[0] < bounds[2])
                assertTrue(bounds[1] < bounds[3])
            }
            assertEquals(setOf("/3/7/3.png", "/3/0/3.png", "/3/7/4.png", "/3/0/4.png"),
                fixture.requests.filter { it.url.host == "tile.openstreetmap.org" }.map { it.url.encodedPath }.toSet())
        }
    }

    @Test fun oneMissingBaseTileKeepsTheOtherTilesAndReusesThemForTheNextFrame() = runBlocking {
        val backgrounds = backgroundImages()
        val transparent = png { _, _ -> Color.TRANSPARENT }
        fixture { chain ->
            val path = chain.request().url.encodedPath
            response(chain, backgrounds[path] ?: transparent, code = if (path == "/3/4/3.png") 503 else 200)
        }.use { fixture ->
            val first = withTimeout(5_000) { fixture.frame(viewport) }
            assertNull(first.error)
            val image = requireNotNull(first.image)
            assertTrue(image.baseMissing)
            assertFalse(image.coverageMissing)
            assertBackgroundQuadrants(image.bitmap, Color.rgb(36, 43, 51))

            val replay = withTimeout(5_000) { fixture.frame(viewport) }
            assertSame(image.bitmap, replay.image!!.bitmap)
            assertTrue(replay.image.fromCache)
            assertEquals(5, fixture.requests.size)

            val next = withTimeout(5_000) { fixture.frame(viewport, index = 1) }
            assertNull(next.error)
            assertTrue(next.image!!.baseMissing)
            assertBackgroundQuadrants(next.image.bitmap, Color.rgb(36, 43, 51))
            // A missing optional tile has a cooldown; the other three remain decoded in cache.
            assertEquals(4, fixture.requests.count { it.url.host == "tile.openstreetmap.org" })
            assertEquals(2, fixture.requests.count { it.url.host == "view.eumetsat.int" })
        }
    }

    @Test fun cancellingAMosaicCancelsEveryPendingLayerAndDoesNotPublishAnOldComposite() = runBlocking {
        val started = CountDownLatch(5)
        val release = CountDownLatch(1)
        val calls = CopyOnWriteArrayList<Call>()
        val image = png { _, _ -> Color.BLUE }
        fixture { chain ->
            calls += chain.call()
            started.countDown()
            if (!release.await(5, TimeUnit.SECONDS)) throw IOException("Fixture release timed out")
            response(chain, image)
        }.use { fixture ->
            val loading = async(Dispatchers.IO) { fixture.frame(viewport) }
            try {
                assertTrue("All visible layers should start together", started.await(5, TimeUnit.SECONDS))
                withTimeout(2_000) { loading.cancelAndJoin() }
                assertEquals(5, calls.size)
                assertTrue(calls.all { it.isCanceled() })
                val folder = File(fixture.directory, "weather-maps-v1")
                assertFalse(folder.listFiles().orEmpty().any { it.name.startsWith("map-${provider.id}-${viewport.key}-") })

                release.countDown()
                val current = withTimeout(5_000) { fixture.frame(viewport.zoomed(1)) }
                assertNull(current.error)
                assertEquals(Color.BLUE, current.image!!.bitmap.getPixel(256, 256))
                assertFalse(folder.listFiles().orEmpty().any { it.name.startsWith("map-${provider.id}-${viewport.key}-") })
            } finally {
                release.countDown()
                loading.cancel()
            }
        }
    }

    private fun assertBackgroundQuadrants(bitmap: Bitmap, bottomRight: Int) {
        assertEquals(Color.RED, bitmap.getPixel(32, 32))
        assertEquals(Color.GREEN, bitmap.getPixel(480, 32))
        assertEquals(Color.BLUE, bitmap.getPixel(32, 480))
        assertEquals(bottomRight, bitmap.getPixel(480, 480))
    }

    private fun backgroundImages() = mapOf(
        "/3/3/2.png" to png { _, _ -> Color.RED },
        "/3/4/2.png" to png { _, _ -> Color.GREEN },
        "/3/3/3.png" to png { _, _ -> Color.BLUE },
        "/3/4/3.png" to png { _, _ -> Color.YELLOW },
    )

    private fun png(width: Int = 256, height: Int = width, colorAt: (Int, Int) -> Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(IntArray(width * height) { colorAt(it % width, it / width) }, 0, width, 0, 0, width, height)
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun fixture(answer: (Interceptor.Chain) -> Response): Fixture {
        val directory = temporary.newFolder()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getCacheDir(): File = directory
        }
        val preferences = AppPreferences(memoryPreferences()).apply { weatherSettings = WeatherSettings(enabled = true) }
        val requests = CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain -> requests += chain.request(); answer(chain) }.build()
        return Fixture(WeatherMapRepository(context, preferences, client), client, requests, directory)
    }

    private inner class Fixture(val repository: WeatherMapRepository, val client: OkHttpClient,
        val requests: CopyOnWriteArrayList<Request>, val directory: File) : AutoCloseable {
        suspend fun frame(viewport: WeatherMapViewport, index: Int = 0) = repository.frame(provider, viewport, series, index, true)
        override fun close() {
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun response(chain: Interceptor.Chain, bytes: ByteArray, code: Int = 200) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .body(bytes.toResponseBody("image/png".toMediaType())).build()
}
