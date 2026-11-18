package com.ambient.tv.ui.weather

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.weather.WEATHER_CACHE_TTL_MILLIS
import com.ambient.tv.data.weather.WeatherLocation
import com.ambient.tv.data.weather.WeatherRepository
import com.ambient.tv.data.weather.WeatherSettings
import com.ambient.tv.data.weather.WeatherState
import com.ambient.tv.data.weather.WeatherError
import com.ambient.tv.data.weather.WeatherCurrent
import com.ambient.tv.data.weather.WeatherSnapshot
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drives the real Compose consumer, lifecycle and repository; HTTP stays inside the fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WeatherPresentationUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()

    private val place = WeatherLocation("erfurt", "Erfurt", "Deutschland", latitude = 50.98,
        longitude = 11.03, timezone = "Europe/Berlin")
    private val settings = WeatherSettings(enabled = true, locations = listOf(place),
        showInHeader = true, showInScreensaver = true)

    @Test fun hiddenDisplaysAndScreensaverSettingsStayIdleUntilTheScreensaverIsVisible() {
        fixture().use { fixture ->
            var headerVisible by mutableStateOf(false)
            var screensaverVisible by mutableStateOf(false)
            rule.setContent { fixture.Content(headerVisible, screensaverVisible) }

            // Startup/intro can keep both consumers hidden even when weather is configured.
            fixture.assertNoWork()
            rule.runOnIdle {
                fixture.repository.updateSettings(settings.copy(showInHeader = false))
            }
            rule.waitForIdle()
            rule.runOnIdle { headerVisible = true }
            // Enabling screensaver weather is not a request to prefetch while browsing.
            fixture.assertNoWork()

            rule.runOnIdle {
                headerVisible = false
                screensaverVisible = true
            }
            rule.waitForIdle()
            rule.waitUntil(5_000) { fixture.presentation.get().screensaver != null }
            assertEquals(1, fixture.calls.size)
            assertTrue(fixture.cacheAccesses.get() > 0)
            assertEquals(place, fixture.presentation.get().screensaver?.location)
            assertNull(fixture.presentation.get().header)
        }
    }

    @Test fun aVisibleHeaderWaitsForResumeBeforeReadingCacheOrRequestingWeather() {
        fixture(settings.copy(showInScreensaver = false), Lifecycle.State.STARTED).use { fixture ->
            rule.setContent { fixture.Content(headerVisible = true, screensaverVisible = false) }
            fixture.assertNoWork()
            // Without valid data, the weather reserves no blank header space.
            assertFalse(fixture.presentation.get().hasHeader)
            assertNull(fixture.presentation.get().header)

            rule.runOnIdle { fixture.owner.registry.currentState = Lifecycle.State.RESUMED }
            rule.waitForIdle()
            rule.waitUntil(5_000) { fixture.presentation.get().header != null }
            assertEquals(1, fixture.calls.size)
            assertTrue(fixture.cacheAccesses.get() > 0)
            assertEquals(place, fixture.presentation.get().header?.location)
            assertNull(fixture.presentation.get().screensaver)
        }
    }

    @Test fun hidingAndPausingCancelRequestsAndReturningRetriesWithoutWaitingForTheRefreshInterval() {
        val releaseHiddenRequest = CountDownLatch(1)
        val releasePausedRequest = CountDownLatch(1)
        fixture(beforeResponse = { _, attempt ->
            val release = when (attempt) {
                1 -> releaseHiddenRequest
                2 -> releasePausedRequest
                else -> null
            }
            if (release != null) check(release.await(10, TimeUnit.SECONDS)) { "Forecast fixture was not released" }
        }).use { fixture ->
            try {
                var headerVisible by mutableStateOf(true)
                rule.setContent { fixture.Content(headerVisible, screensaverVisible = false) }
                rule.waitUntil(5_000) { fixture.calls.size == 1 }
                val hiddenRequest = fixture.calls[0]
                rule.runOnIdle { headerVisible = false }
                rule.waitForIdle()
                rule.waitUntil(5_000) { hiddenRequest.isCanceled() && !fixture.repository.state.value.loading }
                assertNull(fixture.repository.state.value.data)
                releaseHiddenRequest.countDown()

                rule.runOnIdle { headerVisible = true }
                rule.waitForIdle()
                rule.waitUntil(5_000) { fixture.calls.size == 2 }
                val pausedRequest = fixture.calls[1]
                rule.runOnIdle { fixture.owner.registry.currentState = Lifecycle.State.STARTED }
                rule.waitForIdle()
                rule.waitUntil(5_000) { pausedRequest.isCanceled() && !fixture.repository.state.value.loading }
                assertNull(fixture.repository.state.value.data)
                releasePausedRequest.countDown()

                rule.runOnIdle { fixture.owner.registry.currentState = Lifecycle.State.RESUMED }
                rule.waitForIdle()
                rule.waitUntil(5_000) { fixture.presentation.get().header != null }
                assertEquals(3, fixture.calls.size)
                assertEquals(place, fixture.presentation.get().header?.location)
                assertNull(fixture.repository.state.value.error)
            } finally {
                releaseHiddenRequest.countDown()
                releasePausedRequest.countDown()
            }
        }
    }

    @Test fun disabledOrUnselectedWeatherDoesNotAllocateAnyHeaderSpace() {
        val ready = sampleCurrent()
        val disabled = weatherPresentationFromState(
            settings.copy(enabled = false), WeatherState(data = ready))
        assertFalse(disabled.hasHeader)
        assertNull(disabled.header)
        assertNull(disabled.screensaver)

        val missingLocation = weatherPresentationFromState(
            settings.copy(locations = emptyList(), selectedLocationId = null), WeatherState())
        assertFalse(missingLocation.hasHeader)

        fixture(weatherSettings = settings.copy(enabled = false)).use { fixture ->
            rule.setContent { fixture.Content(headerVisible = true, screensaverVisible = false) }
            fixture.assertNoWork()
            assertFalse(fixture.presentation.get().hasHeader)
        }
    }

    @Test fun failedApiResponseWithoutCacheLeavesClockAlone() {
        fixture(responseCode = 503, responseBody = """{"error":true}""").use { fixture ->
            rule.setContent { fixture.Content(headerVisible = true, screensaverVisible = false) }
            rule.waitUntil(8_000) { fixture.repository.state.value.error != null }
            rule.waitForIdle()
            assertNull(fixture.presentation.get().header)
            assertFalse(fixture.presentation.get().hasHeader)
        }
    }

    @Test fun hourlyOnlyResponseWithoutCurrentTemperatureNeverCreatesBlankWeatherWidget() {
        fixture(responseBody = """{"hourly":{"time":[1794754800],"temperature_2m":[7]}}""").use { fixture ->
            rule.setContent { fixture.Content(headerVisible = true, screensaverVisible = false) }
            rule.waitUntil(8_000) { fixture.repository.state.value.data != null }
            rule.waitForIdle()
            assertNull(fixture.repository.state.value.data?.current)
            assertNull(fixture.presentation.get().header)
            assertFalse(fixture.presentation.get().hasHeader)
        }
    }

    @Test fun matchingCachedCurrentWeatherSurvivesApiFailureAndUnrelatedDataDoesNot() {
        val cached = sampleCurrent()
        val valid = weatherPresentationFromState(
            settings, WeatherState(data = cached, error = WeatherError.UNAVAILABLE))
        assertEquals(cached, valid.header)
        assertTrue(valid.hasHeader)
        assertEquals(cached, valid.screensaver)

        val otherLocation = cached.copy(location = place.copy(id = "other"))
        val mismatch = weatherPresentationFromState(
            settings, WeatherState(data = otherLocation, error = WeatherError.UNAVAILABLE))
        assertFalse(mismatch.hasHeader)

        val unusable = weatherPresentationFromState(
            settings, WeatherState(data = cached.copy(current = cached.current?.copy(temperature = null)),
                error = WeatherError.INVALID_DATA))
        assertFalse(unusable.hasHeader)
        assertNull(unusable.screensaver)
    }

    @Test fun expiredCacheIsHiddenFromHeaderAndScreensaverWithoutStatusText() {
        val now = System.currentTimeMillis()
        val valid = sampleCurrent().copy(fetchedAtMillis = now - WEATHER_CACHE_TTL_MILLIS + 1)
        val expired = valid.copy(fetchedAtMillis = now - WEATHER_CACHE_TTL_MILLIS)

        val cached = weatherPresentationFromState(
            settings, WeatherState(data = valid, error = WeatherError.OFFLINE), nowMillis = now)
        assertEquals(valid, cached.header)
        assertEquals(valid, cached.screensaver)

        val hidden = weatherPresentationFromState(
            settings, WeatherState(data = expired, error = WeatherError.OFFLINE), nowMillis = now)
        assertNull(hidden.header)
        assertNull(hidden.screensaver)
        assertFalse(hidden.hasHeader)
    }

    @Test fun freshDiskCacheAppearsBeforeLiveDataAndIsReplaced() {
        val releaseLiveResponse = CountDownLatch(1)
        val cached = sampleCurrent().copy(current = sampleCurrent().current?.copy(temperature = 4.0))
        fixture(cachedForecast = cached, beforeResponse = { _, _ ->
            check(releaseLiveResponse.await(10, TimeUnit.SECONDS)) { "Live forecast was not released" }
        }, responseBody = """{"current":{"time":1794754800,"temperature_2m":11}}""").use { fixture ->
            try {
                rule.setContent { fixture.Content(headerVisible = true, screensaverVisible = false) }
                rule.waitUntil(5_000) { fixture.presentation.get().header?.current?.temperature == 4.0 }
                rule.waitUntil(5_000) { fixture.calls.size == 1 }
                assertTrue(fixture.presentation.get().hasHeader)
                releaseLiveResponse.countDown()
                rule.waitUntil(5_000) { fixture.presentation.get().header?.current?.temperature == 11.0 }
                assertNull(fixture.repository.state.value.error)
            } finally {
                releaseLiveResponse.countDown()
            }
        }
    }

    private fun sampleCurrent(): WeatherSnapshot = WeatherSnapshot(
        place, place.timezone, "celsius", "kmh", System.currentTimeMillis(),
        WeatherCurrent(1794754800, 7.0, null, 2, true, null, null, null),
        hourly = emptyList(), daily = emptyList(),
    )

    private fun fixture(
        weatherSettings: WeatherSettings = settings,
        lifecycleState: Lifecycle.State = Lifecycle.State.RESUMED,
        beforeResponse: (Interceptor.Chain, Int) -> Unit = { _, _ -> },
        responseCode: Int = 200,
        responseBody: String = """{"current":{"time":1794754800,"temperature_2m":7}}""",
        cachedForecast: WeatherSnapshot? = null,
    ): Fixture {
        val directory = temporary.newFolder()
        val cacheAccesses = AtomicInteger()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getCacheDir(): File {
                cacheAccesses.incrementAndGet()
                return directory
            }
        }
        cachedForecast?.let {
            val cacheFile = File(directory, "weather/forecasts-v1.json")
            cacheFile.parentFile!!.mkdirs()
            cacheFile.writeText("""{"version":1,"snapshots":[${Json.encodeToString(it)}]}""")
        }
        val storage = context.getSharedPreferences("weather-presentation-${directory.name}", Context.MODE_PRIVATE)
        val preferences = AppPreferences(storage).apply { this.weatherSettings = weatherSettings }
        val calls = CopyOnWriteArrayList<Call>()
        val firstRequest = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.add(chain.call())
            firstRequest.countDown()
            beforeResponse(chain, calls.size)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(responseCode).message("fixture")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val owner = Owner()
        rule.runOnIdle { owner.registry.currentState = lifecycleState }
        return Fixture(WeatherRepository(context, preferences, storage, client), client, owner,
            calls, firstRequest, cacheAccesses)
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private inner class Fixture(
        val repository: WeatherRepository,
        private val client: OkHttpClient,
        val owner: Owner,
        val calls: CopyOnWriteArrayList<Call>,
        private val firstRequest: CountDownLatch,
        val cacheAccesses: AtomicInteger,
    ) : AutoCloseable {
        private val viewModel = WeatherPresentationViewModel(repository)
        val presentation = AtomicReference(WeatherPresentation())

        @Composable fun Content(headerVisible: Boolean, screensaverVisible: Boolean) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val current = rememberWeatherPresentation(headerVisible, screensaverVisible, viewModel = viewModel)
                SideEffect { presentation.set(current) }
            }
        }

        fun assertNoWork() {
            rule.waitForIdle()
            assertFalse("Hidden or paused weather must not start HTTP", firstRequest.await(200, TimeUnit.MILLISECONDS))
            assertEquals("Unused weather must not touch the disk cache", 0, cacheAccesses.get())
            assertTrue(calls.isEmpty())
            assertNull(repository.state.value.data)
        }

        override fun close() {
            rule.runOnIdle { owner.registry.currentState = Lifecycle.State.DESTROYED }
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
