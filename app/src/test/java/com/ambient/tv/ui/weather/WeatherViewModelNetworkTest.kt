package com.ambient.tv.ui.weather

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.weather.WeatherLocation
import com.ambient.tv.data.weather.WeatherRepository
import com.ambient.tv.data.weather.WeatherSettings
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.Call
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WeatherViewModelNetworkTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun aFirstLocationCanBeSearchedAndAddedBeforeAVisibleConsumerLoadsItsForecast() = runBlocking {
        fixture().use { fixture ->
            val model = fixture.model
            assertTrue(fixture.preferences.localNetworkOnly)
            assertFalse(model.settings.value.enabled)
            assertTrue(model.settings.value.locations.isEmpty())

            model.setEnabled(true)
            model.searchLocations("Berlin", "de")
            fixture.finishSearchDebounce()
            val search = withTimeout(5_000) { model.search.first { it.completed } }
            assertFalse(search.failed)
            assertNull(search.error)
            val place = search.results.single()
            assertEquals("Berlin", place.name)
            assertEquals("geonames:2950159", place.id)

            model.addLocation(place)
            assertEquals(listOf("geocoding-api.open-meteo.com"), fixture.hosts)
            assertNull(model.state.value.data)
            model.refreshVisible()
            val forecast = withTimeout(5_000) {
                model.state.first { !it.loading && (it.data != null || it.error != null) }
            }
            assertNull(forecast.error)
            assertEquals(place, forecast.data?.location)
            assertEquals(place, model.settings.value.selectedLocation)
            assertEquals(listOf(place), fixture.preferences.weatherSettings.locations)
            assertEquals(WeatherLocationSearch(), model.search.value)
            assertEquals(listOf("geocoding-api.open-meteo.com", "api.open-meteo.com"), fixture.hosts)
            assertTrue("Adding weather must preserve the media-server restriction", fixture.preferences.localNetworkOnly)
        }
    }

    @Test fun changingSettingsWithoutAVisibleForecastDoesNotStartRequests() = runBlocking {
        val requested = CountDownLatch(1)
        val first = WeatherLocation("erfurt", "Erfurt", "Deutschland", latitude = 50.98,
            longitude = 11.03, timezone = "Europe/Berlin")
        val second = first.copy(id = "berlin", name = "Berlin", latitude = 52.52, longitude = 13.41)
        fixture(enabled = true, locations = listOf(first)) { requested.countDown() }.use { fixture ->
            val model = fixture.model
            model.setEnabled(false)
            model.setEnabled(true)
            model.addLocation(second)
            model.selectLocation(first.id)
            model.removeLocation(second.id)
            model.setTemperatureUnit("fahrenheit")
            model.setWindUnit("mph")
            model.changeSettings { it.copy(showInHeader = false, showInScreensaver = true) }
            fixture.dispatcher.scheduler.runCurrent()
            assertFalse("Settings must wait for a visible weather consumer", requested.await(200, TimeUnit.MILLISECONDS))
            assertTrue(fixture.hosts.isEmpty())
            assertNull(model.state.value.data)

            model.refreshVisible()
            assertEquals(listOf("api.open-meteo.com"), fixture.hosts)
            assertEquals(first, model.state.value.data?.location)
            assertEquals("fahrenheit", model.state.value.data?.temperatureUnit)
            assertEquals("mph", model.state.value.data?.windUnit)
        }
    }

    @Test fun restoringTheServerLanRestrictionDoesNotClearOrCancelAnActiveLocationSearch() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = AtomicReference<Call>()
        fixture(enabled = true, serverLanOnly = false) { chain ->
            call.set(chain.call())
            started.countDown()
            assertTrue("Search fixture was not released", release.await(5, TimeUnit.SECONDS))
        }.use { fixture ->
            try {
                fixture.model.searchLocations("Berlin", "de")
                fixture.finishSearchDebounce()
                assertTrue("Search request did not start", started.await(5, TimeUnit.SECONDS))

                fixture.preferences.localNetworkOnly = true
                // Real SharedPreferences listeners must see this write; the lightweight test
                // preference proxy deliberately has no listeners and would hide the old bug.
                shadowOf(Looper.getMainLooper()).idle()
                fixture.dispatcher.scheduler.runCurrent()
                assertEquals("Berlin", fixture.model.search.value.query)
                assertTrue(fixture.model.search.value.loading)
                assertFalse("A server-only preference must not cancel weather HTTP", call.get().isCanceled())

                release.countDown()
                val search = withTimeout(5_000) { fixture.model.search.first { it.completed } }
                assertFalse(search.failed)
                assertEquals("Berlin", search.results.single().name)
                assertEquals(listOf("geocoding-api.open-meteo.com"), fixture.hosts)
                assertTrue(fixture.preferences.localNetworkOnly)
            } finally { release.countDown() }
        }
    }

    private fun fixture(
        enabled: Boolean = false,
        serverLanOnly: Boolean? = null,
        locations: List<WeatherLocation> = emptyList(),
        beforeResponse: (Interceptor.Chain) -> Unit = {},
    ): Fixture {
        val directory = temporary.newFolder()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getCacheDir(): File = directory
        }
        val storage = context.getSharedPreferences("weather-viewmodel-${directory.name}", Context.MODE_PRIVATE)
        val preferences = AppPreferences(storage).apply {
            weatherSettings = WeatherSettings(enabled = enabled, locations = locations)
            serverLanOnly?.let { localNetworkOnly = it }
        }
        val hosts = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val host = chain.request().url.host
            hosts += host
            beforeResponse(chain)
            val body = when (host) {
                "geocoding-api.open-meteo.com" -> """{"results":[{"id":2950159,"name":"Berlin","country":"Deutschland","latitude":52.52,"longitude":13.41,"timezone":"Europe/Berlin"}]}"""
                "api.open-meteo.com" -> """{"current":{"time":1794754800,"temperature_2m":7}}"""
                else -> error("Unexpected weather endpoint: $host")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val repository = WeatherRepository(context, preferences, storage, client)
        val model = WeatherViewModel(repository)
        val store = ViewModelStore().apply { put("weather", model) }
        return Fixture(model, preferences, client, hosts, dispatcher, store)
    }

    private class Fixture(
        val model: WeatherViewModel,
        val preferences: AppPreferences,
        private val client: OkHttpClient,
        val hosts: List<String>,
        val dispatcher: kotlinx.coroutines.test.TestDispatcher,
        private val store: ViewModelStore,
    ) : AutoCloseable {
        fun finishSearchDebounce() {
            dispatcher.scheduler.advanceTimeBy(450)
            dispatcher.scheduler.runCurrent()
        }

        override fun close() {
            store.clear()
            dispatcher.scheduler.runCurrent()
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            Dispatchers.resetMain()
        }
    }
}
