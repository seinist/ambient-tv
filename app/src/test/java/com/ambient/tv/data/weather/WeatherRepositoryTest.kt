package com.ambient.tv.data.weather

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.memoryPreferences
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class WeatherRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val place = WeatherLocation("a", "Erfurt", "Deutschland", "Thüringen", 50.98, 11.03, "Europe/Berlin")
    private val other = WeatherLocation("b", "New York", "United States", "New York", 40.71, -74.01, "America/New_York")
    private val settings = WeatherSettings(enabled = true, locations = listOf(place, other), selectedLocationId = place.id)

    @Test fun enabledWeatherUsesSearchAndForecastWithTheDefaultServerLanRestriction() = runBlocking {
        fixture { chain, _ ->
            if (chain.request().url.host == "geocoding-api.open-meteo.com") response(chain, body = geocoding)
            else response(chain)
        }.use { fixture ->
            assertTrue("The media-server LAN default must remain enabled", fixture.preferences.localNetworkOnly)
            assertEquals("Berlin", fixture.repository.searchLocations("Berlin", "de").single().name)
            val forecast = fixture.repository.refresh()
            assertEquals(place, forecast?.data?.location)
            assertNull(forecast?.error)
            assertEquals(2, fixture.requests.get())
            assertTrue("Weather must not change the media-server restriction", fixture.preferences.localNetworkOnly)
        }
    }

    @Test fun reenablingTheServerLanRestrictionDoesNotCancelActiveWeatherRequests() = runBlocking {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        fixture { chain, _ ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            if (chain.request().url.host == "geocoding-api.open-meteo.com") response(chain, body = geocoding)
            else response(chain)
        }.use { fixture ->
            fixture.preferences.localNetworkOnly = false
            fixture.repository.updateSettings(settings)
            val forecast = async(Dispatchers.IO) { fixture.repository.refresh() }
            val search = async(Dispatchers.IO) { fixture.repository.searchLocations("Berlin", "de") }
            try {
                started.awaitOrFail()
                fixture.preferences.localNetworkOnly = true
                // Also exercise a consumer synchronizing preferences after this server-only change.
                fixture.repository.updateSettings(settings)
                release.countDown()
                val result = withTimeout(5_000) { forecast.await() }
                assertEquals(place, result?.data?.location)
                assertNull(result?.error)
                assertEquals("Berlin", withTimeout(5_000) { search.await() }.single().name)
                assertEquals(2, fixture.requests.get())
                assertTrue(fixture.preferences.localNetworkOnly)
            } finally { release.countDown(); forecast.cancelAndJoin(); search.cancelAndJoin() }
        }
    }

    @Test fun disablingWeatherStillBlocksNewSearchAndForecastRequests() = runBlocking {
        fixture { chain, _ -> response(chain) }.use { fixture ->
            fixture.repository.updateSettings(settings.copy(enabled = false))
            val forecast = fixture.repository.refresh(force = true)
            val searchFailure = runCatching { fixture.repository.searchLocations("Berlin", "de") }.exceptionOrNull()
            assertEquals(WeatherError.DISABLED, forecast?.error)
            assertNull(forecast?.data)
            assertEquals(WeatherError.DISABLED, (searchFailure as? WeatherRequestException)?.reason)
            assertEquals(0, fixture.requests.get())
            assertTrue(fixture.preferences.localNetworkOnly)
        }
    }

    @Test fun cancelledVisibleRefreshCanRetryImmediatelyWithoutAnEmptyThirtyMinuteWait() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture { chain, attempt ->
            if (attempt == 1) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            response(chain)
        }.use { fixture ->
            val first = async(Dispatchers.IO) { fixture.repository.refresh() }
            try {
                started.awaitOrFail()
                first.cancelAndJoin()
                assertFalse(fixture.repository.state.value.loading)
                val recovered = withTimeout(5_000) { fixture.repository.refresh() }
                assertNotNull(recovered?.data)
                assertNull(recovered?.error)
                assertEquals(2, fixture.requests.get())
                // A real fresh-cache hit is still an outcome; a throttled forced call is not.
                assertEquals(recovered, fixture.repository.refresh())
                assertNull(fixture.repository.refresh(force = true))
                assertEquals(2, fixture.requests.get())
            } finally { release.countDown(); first.cancelAndJoin() }
        }
    }

    @Test fun disableAndReenableDoesNotThrottleTheReplacementOfACancelledHttpCall() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture { chain, attempt ->
            if (attempt == 1) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            response(chain)
        }.use { fixture ->
            val first = async(Dispatchers.IO) { fixture.repository.refresh() }
            try {
                started.awaitOrFail()
                fixture.repository.updateSettings(settings.copy(enabled = false))
                fixture.repository.updateSettings(settings)
                release.countDown()
                assertNull(withTimeout(5_000) { first.await() })
                val recovered = withTimeout(5_000) { fixture.repository.refresh() }
                assertEquals(place, recovered?.data?.location)
                assertNull(recovered?.error)
                assertEquals(2, fixture.requests.get())
            } finally { release.countDown(); first.cancelAndJoin() }
        }
    }

    @Test fun supersededLocationRequestHasNoOutcomeAndCannotPublishItsOldData() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture { chain, attempt ->
            if (attempt == 1) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            response(chain)
        }.use { fixture ->
            val first = async(Dispatchers.IO) { fixture.repository.refresh(force = true) }
            try {
                started.awaitOrFail()
                fixture.repository.updateSettings(settings.copy(selectedLocationId = other.id))
                release.countDown()
                assertNull(withTimeout(5_000) { first.await() })
                assertNull(fixture.repository.state.value.data)
                val second = withTimeout(5_000) { fixture.repository.refresh(force = true) }
                assertEquals(other, second?.data?.location)
                assertEquals(other, fixture.repository.state.value.data?.location)
            } finally { release.countDown(); first.cancelAndJoin() }
        }
    }

    @Test fun olderIoPreferenceReadCannotRollBackACompletedLocationUpdate() = runBlocking {
        val storage = ControlledPreferences()
        fixture(storage) { chain, _ -> response(chain) }.use { fixture ->
            val paused = ReadPause()
            storage.nextRead.set(paused)
            val reader = async(Dispatchers.IO) { fixture.repository.refresh() }
            val written = CountDownLatch(1)
            val writerDone = CountDownLatch(1)
            val writerFailure = AtomicReference<Throwable?>()
            val writer = Thread({
                try { fixture.repository.updateSettings(settings.copy(selectedLocationId = other.id)) }
                catch (error: Throwable) { writerFailure.set(error) }
                finally { writerDone.countDown() }
            }, "weather-settings-writer")
            try {
                paused.entered.awaitOrFail()
                storage.nextWrite.set(written)
                writer.start()
                written.awaitOrFail()
                // In the broken implementation the writer finishes before the old read resumes.
                // With the lock it waits in synchronizeSettings, then publishes the newer value.
                withTimeout(5_000) {
                    while (writerDone.count != 0L && !(writer.state == Thread.State.BLOCKED &&
                            writer.stackTrace.any { it.className == WeatherRepository::class.java.name && it.methodName == "synchronizeSettings" })) delay(5)
                }
                paused.release.countDown()
                writerDone.awaitOrFail()
                writerFailure.get()?.let { throw AssertionError("Settings writer failed", it) }
                withTimeout(5_000) { reader.await() }
                assertEquals(other.id, fixture.preferences.weatherSettings.selectedLocationId)
                assertEquals(other.id, fixture.repository.settings.value.selectedLocationId)
                assertTrue(fixture.repository.state.value.data?.matches(fixture.repository.settings.value) != false)
            } finally { paused.release.countDown(); reader.cancelAndJoin(); writer.join(5_000) }
        }
    }

    @Test fun aForcedRetryHonoursProviderCooldownAndReturnsItsOwnRateLimitedOutcome() = runBlocking {
        fixture { chain, _ -> response(chain, 429).newBuilder().header("Retry-After", "120").build() }.use { fixture ->
            val first = fixture.repository.refresh(force = true)
            val second = fixture.repository.refresh(force = true)
            assertEquals(WeatherError.RATE_LIMITED, first?.error)
            assertEquals(first?.retryAfterMillis, second?.retryAfterMillis)
            assertEquals(WeatherError.RATE_LIMITED, second?.error)
            assertEquals(1, fixture.requests.get())
        }
    }

    @Test fun changingTheForecastLocationAndUnitsDoesNotCancelIndependentGeocoding() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture { chain, _ ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            response(chain, body = geocoding)
        }.use { fixture ->
            val search = async(Dispatchers.IO) { fixture.repository.searchLocations("Berlin", "de") }
            try {
                started.awaitOrFail()
                fixture.repository.updateSettings(settings.copy(selectedLocationId = other.id))
                fixture.repository.updateSettings(settings.copy(selectedLocationId = other.id,
                    temperatureUnit = "fahrenheit", windUnit = "mph"))
                release.countDown()
                val result = withTimeout(5_000) { search.await() }
                assertEquals("Berlin", result.single().name)
                assertEquals("geonames:2950159", result.single().id)
                assertEquals(other.id, fixture.repository.settings.value.selectedLocationId)
                assertEquals(1, fixture.requests.get())
            } finally { release.countDown(); search.cancelAndJoin() }
        }
    }

    @Test fun locallyCancelledSearchStaysCancellationWhenWeatherIsReenabledBeforeTheCallback() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture { chain, attempt ->
            if (attempt == 1) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            response(chain, body = geocoding)
        }.use { fixture ->
            val search = async(Dispatchers.IO) { fixture.repository.searchLocations("Berlin", "de") }
            try {
                started.awaitOrFail()
                fixture.repository.updateSettings(settings.copy(enabled = false))
                fixture.repository.updateSettings(settings)
                release.countDown()
                val failure = runCatching { withTimeout(5_000) { search.await() } }.exceptionOrNull()
                assertTrue("Local cancellation must never become a provider failure", failure is CancellationException)
                assertTrue("The request itself must be cancelled, rather than timing out in the test", search.isCancelled)
                assertEquals("Berlin", fixture.repository.searchLocations("Berlin", "de").single().name)
                assertEquals(2, fixture.requests.get())
                assertTrue(fixture.preferences.localNetworkOnly)
            } finally { release.countDown(); search.cancelAndJoin() }
        }
    }

    private fun fixture(
        storage: SharedPreferences = memoryPreferences(),
        answer: (Interceptor.Chain, Int) -> Response,
    ): Fixture {
        val preferences = AppPreferences(storage).apply { weatherSettings = settings }
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { answer(it, requests.incrementAndGet()) }.build()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            private val directory = temporary.newFolder()
            override fun getCacheDir(): File = directory
        }
        return Fixture(WeatherRepository(context, preferences, storage, client), preferences, client, requests)
    }

    private fun response(chain: Interceptor.Chain, code: Int = 200,
        body: String = """{"current":{"time":1794754800,"temperature_2m":7}}"""): Response = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private class Fixture(
        val repository: WeatherRepository,
        val preferences: AppPreferences,
        val client: OkHttpClient,
        val requests: AtomicInteger,
    ) : AutoCloseable {
        override fun close() {
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private class ReadPause {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    /** Pause after capturing one value, without holding the storage lock while another writer saves. */
    private class ControlledPreferences(private val delegate: SharedPreferences = memoryPreferences()) : SharedPreferences by delegate {
        val nextRead = AtomicReference<ReadPause?>()
        val nextWrite = AtomicReference<CountDownLatch?>()
        private val storageLock = Any()

        override fun getString(key: String?, default: String?): String? {
            val value = synchronized(storageLock) { delegate.getString(key, default) }
            if (key == "weather_settings") nextRead.getAndSet(null)?.let { pause ->
                pause.entered.countDown()
                pause.release.awaitOrFail()
            }
            return value
        }

        override fun getBoolean(key: String?, default: Boolean): Boolean = synchronized(storageLock) { delegate.getBoolean(key, default) }

        override fun edit(): SharedPreferences.Editor {
            val editor = synchronized(storageLock) { delegate.edit() }
            return object : SharedPreferences.Editor by editor {
                private var weatherChanged = false
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value)
                    if (key == "weather_settings") weatherChanged = true
                    return this
                }
                override fun apply() {
                    synchronized(storageLock) { editor.apply() }
                    if (weatherChanged) nextWrite.getAndSet(null)?.countDown()
                }
            }
        }
    }

    companion object {
        private val geocoding = """{"results":[{"id":2950159,"name":"Berlin","country":"Deutschland",
            "latitude":52.52,"longitude":13.41,"timezone":"Europe/Berlin"}]}"""
        private fun CountDownLatch.awaitOrFail() = assertTrue("Fixture timed out", await(5, TimeUnit.SECONDS))
    }
}
