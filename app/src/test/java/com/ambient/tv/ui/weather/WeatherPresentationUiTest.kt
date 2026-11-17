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
import com.ambient.tv.data.weather.WeatherLocation
import com.ambient.tv.data.weather.WeatherRepository
import com.ambient.tv.data.weather.WeatherSettings
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
            // Layout reserves the header slot even though lifecycle gating has not fetched data.
            assertTrue(fixture.presentation.get().headerEnabled)
            assertTrue(fixture.presentation.get().hasHeader)
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

    private fun fixture(
        weatherSettings: WeatherSettings = settings,
        lifecycleState: Lifecycle.State = Lifecycle.State.RESUMED,
        beforeResponse: (Interceptor.Chain, Int) -> Unit = { _, _ -> },
    ): Fixture {
        val directory = temporary.newFolder()
        val cacheAccesses = AtomicInteger()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getCacheDir(): File {
                cacheAccesses.incrementAndGet()
                return directory
            }
        }
        val storage = context.getSharedPreferences("weather-presentation-${directory.name}", Context.MODE_PRIVATE)
        val preferences = AppPreferences(storage).apply { this.weatherSettings = weatherSettings }
        val calls = CopyOnWriteArrayList<Call>()
        val firstRequest = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.add(chain.call())
            firstRequest.countDown()
            beforeResponse(chain, calls.size)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"current":{"time":1794754800,"temperature_2m":7}}""".toResponseBody("application/json".toMediaType()))
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
