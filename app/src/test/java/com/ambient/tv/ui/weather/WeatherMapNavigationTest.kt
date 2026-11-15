package com.ambient.tv.ui.weather

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.ViewModelStore
import com.ambient.tv.data.weather.WeatherLocation
import com.ambient.tv.data.weather.WeatherMapError
import com.ambient.tv.data.weather.WeatherMapFrame
import com.ambient.tv.data.weather.WeatherMapImage
import com.ambient.tv.data.weather.WeatherMapKind
import com.ambient.tv.data.weather.WeatherMapProvider
import com.ambient.tv.data.weather.WeatherMapResult
import com.ambient.tv.data.weather.WeatherMapSeries
import com.ambient.tv.data.weather.WeatherMapViewport
import com.ambient.tv.notifications.OperationFeedbackReporter
import com.ambient.tv.ui.common.pageContentUp
import com.ambient.tv.ui.common.rememberPageFocusChain
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real map controls and focus-restoring lazy content with deliberately slow map responses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w1280dp-h720dp-mdpi")
@OptIn(ExperimentalTestApi::class)
class WeatherMapNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private val loader = ControlledMapLoader()
    private val model = WeatherMapViewModel(loader, OperationFeedbackReporter())
    private val location = WeatherLocation("erfurt", "Erfurt", "Germany", latitude = 50.98,
        longitude = 11.03, timezone = "Europe/Berlin")

    @After fun close() {
        rule.runOnIdle { models.clear() }
    }

    @Test fun televisionZoomKeepsTheLocationCentredWhileSlowLoadsAreCancelledAndReplaced() {
        showMap(handheld = false)
        finishLatest()
        repeat(8) { step ->
            val label = if (step % 2 == 0) "Zoom out" else "Zoom in"
            focus(label)
            val controlTop = rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top
            rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            rule.waitForIdle()
            rule.onNodeWithText(label).assertIsFocused().assertIsDisplayed()
            assertEquals("Zooming must not shift the focused map controls", controlTop,
                rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top, 1f)
            rule.runOnIdle {
                assertEquals(if (step % 2 == 0) 5 else 6, model.state.value.request!!.viewport.zoom)
                assertTrue(model.state.value.loading)
            }
            assertLocationCentred()
            // Another key press is allowed while the former HTTP request is still pending.
            if (step % 2 == 1) finishLatest()
        }
        rule.runOnIdle {
            assertTrue(loader.requests.dropLast(1).any { it.cancelled })
            assertEquals(9, loader.requests.size)
        }
        rule.onNodeWithText("Zoom in").assertIsFocused()
    }

    @Test fun televisionCanNavigateAfterEumetsatErrorsAndAtBothZoomLimits() {
        showMap(handheld = false)
        finishLatest()
        focus("Zoom in")
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        finishLatest(WeatherMapError.UNAVAILABLE)
        assertLocationCentred()
        rule.onNodeWithText("Zoom in").assertIsFocused().assertIsNotEnabled()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        rule.onNodeWithText("Zoom out").assertIsFocused().assertIsDisplayed()
        repeat(WeatherMapViewport.MAX_ZOOM - WeatherMapViewport.MIN_ZOOM) {
            rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            finishLatest()
            assertLocationCentred()
            rule.onNodeWithText("Zoom out").assertIsFocused()
        }
        rule.onNodeWithText("Zoom out").assertIsNotEnabled()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithText("Zoom in").assertIsFocused().assertIsDisplayed()
        rule.runOnIdle { assertFalse(model.state.value.loading) }
    }

    @Test
    @Config(qualifiers = "en-rUS-w960dp-h540dp-mdpi")
    fun compactTelevisionControlsRetainFocusAcrossRepeatedImageAndErrorChanges() {
        showMap(handheld = false)
        finishLatest()
        repeat(6) { step ->
            val label = if (step % 2 == 0) "Zoom out" else "Zoom in"
            focus(label)
            rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            rule.waitForIdle()
            rule.onNodeWithText(label).assertIsFocused().assertIsDisplayed()
            finishLatest(if (step % 2 == 0) WeatherMapError.UNAVAILABLE else null)
            rule.onNodeWithText(label).assertIsFocused().assertIsDisplayed()
            assertLocationCentred()
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h640dp-mdpi")
    fun handheldNeedsOnlyZoomControlsAndKeepsTheLocationCentredAfterFailures() {
        showMap(handheld = true)
        finishLatest()
        listOf("West", "East", "North", "South", "My location").forEach {
            rule.onNodeWithText(it).assertDoesNotExist()
        }
        touch("Zoom out")
        finishLatest(WeatherMapError.UNAVAILABLE)
        assertLocationCentred()
        touch("Zoom in")
        finishLatest()
        assertLocationCentred()
        touch("Zoom in")
        finishLatest()
        assertLocationCentred()
        rule.onNodeWithText("Zoom in").assertIsNotEnabled()
        rule.runOnIdle { assertFalse(model.state.value.loading) }
    }

    private fun showMap(handheld: Boolean) {
        models.put("map", model)
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides handheld) {
                AmbientTvTheme {
                    val chain = rememberPageFocusChain()
                    WeatherMapPanel(location, WeatherMapKind.SATELLITE, WeatherMapProvider.EUMETSAT.id,
                        onOpenSettings = {}, networkAllowed = true,
                        modifier = Modifier.fillMaxSize().pageContentUp(chain), viewModel = model)
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1, loader.requests.size) }
    }

    private fun finishLatest(error: WeatherMapError? = null) {
        rule.runOnIdle {
            val request = loader.requests.last()
            val frame = WeatherMapFrame("2026-11-15T18:00:00Z", Instant.parse("2026-11-15T18:00:00Z").toEpochMilli())
            val series = WeatherMapSeries(request.provider.id, listOf(frame), frame.epochMillis)
            request.result.complete(WeatherMapResult(series,
                image = if (error == null) WeatherMapImage(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888),
                    frame, fromCache = false, baseMissing = false, coverageMissing = false) else null,
                error = error))
        }
        rule.waitForIdle()
    }

    private fun assertLocationCentred() = rule.runOnIdle {
        val viewport = model.state.value.request!!.viewport
        assertEquals(WeatherMapViewport.at(location.latitude, location.longitude, viewport.zoom), viewport)
        val marker = requireNotNull(viewport.marker(location.latitude, location.longitude))
        assertEquals(.5f, marker.first, .000001f)
        assertEquals(.5f, marker.second, .000001f)
    }

    private fun focus(label: String) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(label))
        rule.onNodeWithText(label).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.onNodeWithText(label).assertIsFocused().assertIsDisplayed()
    }

    private fun touch(label: String) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(label))
        rule.onNodeWithText(label).assertIsDisplayed().performClick()
        rule.waitForIdle()
    }

    private class ControlledMapLoader : WeatherMapLoader {
        data class Pending(val provider: WeatherMapProvider, val viewport: WeatherMapViewport,
            val result: CompletableDeferred<WeatherMapResult> = CompletableDeferred(), var cancelled: Boolean = false)
        val requests = mutableListOf<Pending>()

        override suspend fun cached(provider: WeatherMapProvider, viewport: WeatherMapViewport) = WeatherMapResult()
        override suspend fun load(provider: WeatherMapProvider, viewport: WeatherMapViewport,
            networkAllowed: Boolean, force: Boolean): WeatherMapResult {
            val pending = Pending(provider, viewport).also(requests::add)
            try { return pending.result.await() }
            finally { pending.cancelled = !pending.result.isCompleted }
        }

        override suspend fun frame(provider: WeatherMapProvider, viewport: WeatherMapViewport,
            series: WeatherMapSeries, index: Int, networkAllowed: Boolean) = error("No playback requested")
    }
}
