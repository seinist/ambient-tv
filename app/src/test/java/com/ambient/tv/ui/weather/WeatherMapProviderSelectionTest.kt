package com.ambient.tv.ui.weather

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import com.ambient.tv.data.weather.WeatherLocation
import com.ambient.tv.data.weather.WeatherMapError
import com.ambient.tv.data.weather.WeatherMapKind
import com.ambient.tv.data.weather.WeatherMapProvider
import com.ambient.tv.data.weather.WeatherMapResult
import com.ambient.tv.data.weather.WeatherMapSeries
import com.ambient.tv.data.weather.WeatherMapViewport
import com.ambient.tv.notifications.OperationFeedbackReporter
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w420dp-h900dp-mdpi")
class WeatherMapProviderSelectionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val viewModels = ViewModelStore()
    private val loader = RecordingLoader()
    private val place = WeatherLocation("erfurt", "Erfurt", "Deutschland", latitude = 50.98,
        longitude = 11.03, timezone = "Europe/Berlin")

    @After fun close() { rule.runOnIdle { viewModels.clear() } }

    @Test fun radarShowsOnlyTheSavedProviderEvenDuringAnOutageAndUpdatesWithSettings() {
        val provider = mutableStateOf(WeatherMapProvider.DWD.id)
        loader.result = WeatherMapResult(error = WeatherMapError.UNAVAILABLE)
        showPanel(WeatherMapKind.RADAR, providerId = { provider.value })
        rule.onAllNodesWithText("DWD")[0].assertExists()
        rule.onAllNodesWithText("RainViewer", substring = true).assertCountEquals(0)
        rule.runOnIdle { assertEquals(listOf(WeatherMapProvider.DWD), loader.loads) }

        rule.runOnIdle { provider.value = WeatherMapProvider.RAINVIEWER.id }
        rule.onAllNodesWithText("RainViewer")[0].assertExists()
        rule.onAllNodesWithText("DWD", substring = true).assertCountEquals(0)
        rule.runOnIdle {
            assertEquals(listOf(WeatherMapProvider.DWD, WeatherMapProvider.RAINVIEWER), loader.loads)
        }
    }

    @Test fun satelliteShowsOnlyItsSavedProvider() {
        val provider = mutableStateOf(WeatherMapProvider.NASA.id)
        showPanel(WeatherMapKind.SATELLITE, providerId = { provider.value })
        rule.onAllNodesWithText("NASA GIBS")[0].assertExists()
        rule.onAllNodesWithText("EUMETSAT", substring = true).assertCountEquals(0)
        rule.runOnIdle { provider.value = WeatherMapProvider.EUMETSAT.id }
        rule.onAllNodesWithText("EUMETSAT")[0].assertExists()
        rule.onAllNodesWithText("NASA", substring = true).assertCountEquals(0)
        rule.runOnIdle {
            assertEquals(listOf(WeatherMapProvider.NASA, WeatherMapProvider.EUMETSAT), loader.loads)
        }
    }

    @Test fun noSelectionOffersSettingsWithoutLoadingOrShowingOtherProviders() {
        val provider = mutableStateOf("")
        var settingsOpened = 0
        showPanel(WeatherMapKind.RADAR, providerId = { provider.value }, onOpenSettings = { settingsOpened++ })
        WeatherMapProvider.entries.forEach {
            rule.onAllNodesWithText(it.title, substring = true).assertCountEquals(0)
        }
        rule.onNodeWithText("Weather settings").performClick()
        rule.runOnIdle {
            assertEquals(1, settingsOpened)
            assertEquals(0, loader.cachedReads)
            assertEquals(emptyList<WeatherMapProvider>(), loader.loads)
            provider.value = WeatherMapProvider.DWD.id
        }
        rule.onAllNodesWithText("DWD")[0].assertExists()
        rule.runOnIdle { provider.value = "" }
        rule.onNodeWithText("Weather settings").assertExists()
        rule.onAllNodesWithText("DWD", substring = true).assertCountEquals(0)
        rule.runOnIdle {
            assertEquals(1, loader.cachedReads)
            assertEquals(listOf(WeatherMapProvider.DWD), loader.loads)
        }
    }

    private fun showPanel(kind: WeatherMapKind, providerId: () -> String, onOpenSettings: () -> Unit = {}) {
        val viewModel = WeatherMapViewModel(loader, OperationFeedbackReporter())
        viewModels.put("map", viewModel)
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true) {
                AmbientTvTheme {
                    WeatherMapPanel(location = place, kind = kind, providerId = providerId(),
                        onOpenSettings = onOpenSettings, networkAllowed = true,
                        modifier = Modifier.fillMaxSize(), viewModel = viewModel)
                }
            }
        }
    }

    private class RecordingLoader : WeatherMapLoader {
        var cachedReads = 0
        val loads = mutableListOf<WeatherMapProvider>()
        var result = WeatherMapResult()
        override suspend fun cached(provider: WeatherMapProvider, viewport: WeatherMapViewport): WeatherMapResult {
            cachedReads++
            return WeatherMapResult()
        }
        override suspend fun load(provider: WeatherMapProvider, viewport: WeatherMapViewport,
            networkAllowed: Boolean, force: Boolean): WeatherMapResult {
            loads += provider
            return result
        }
        override suspend fun frame(provider: WeatherMapProvider, viewport: WeatherMapViewport,
            series: WeatherMapSeries, index: Int, networkAllowed: Boolean): WeatherMapResult =
            error("No animation is requested in this fixture")
    }
}
