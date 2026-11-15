package com.ambient.tv.ui.weather

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipe
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.memoryPreferences
import com.ambient.tv.data.weather.WeatherRepository
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the real weather screen, including its selected chips and phone-only gesture hook. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w420dp-h900dp-mdpi")
@OptIn(ExperimentalTestApi::class)
class WeatherScreenSwipeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val viewModels = ViewModelStore()
    private val requests = AtomicInteger()
    private val client = OkHttpClient.Builder().addInterceptor {
        requests.incrementAndGet()
        throw IOException("Disabled weather must not request data")
    }.build()

    @After fun close() {
        rule.runOnIdle { viewModels.clear() }
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }

    @Test fun phoneSwipesAndChipsShareSelectionWithoutLoadingDisabledWeather() {
        showWeather(handheld = true)
        rule.onNodeWithText("Overview").assertIsSelected()
        swipe(left = true)
        rule.onNodeWithText("Radar").assertIsSelected()
        swipe(left = true)
        rule.onNodeWithText("Satellite").assertIsSelected()
        // The last category is a boundary, not a wrap back to the first one.
        swipe(left = true)
        rule.onNodeWithText("Satellite").assertIsSelected()
        swipe(left = false)
        rule.onNodeWithText("Radar").assertIsSelected()
        rule.onNodeWithText("Overview").performClick().assertIsSelected()
        swipe(left = true)
        rule.onNodeWithText("Radar").assertIsSelected()
        rule.runOnIdle { assertEquals(0, requests.get()) }
    }

    @Test fun televisionRetainsChipNavigationWithoutTouchSwipes() {
        showWeather(handheld = false)
        rule.onNodeWithText("Refresh weather").assertExists()
        swipe(left = true)
        rule.onNodeWithText("Refresh weather").assertExists()
        // Enter the row on its selected chip, then move with the remote. The row's
        // focus restorer intentionally overrides direct focus requests to another chip.
        rule.onNodeWithText("Overview").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.onNodeWithText("Overview").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithText("Radar").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithText("Refresh weather").assertDoesNotExist()
        swipe(left = false)
        rule.onNodeWithText("Refresh weather").assertDoesNotExist()
        rule.onNodeWithText("Radar").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.onNodeWithText("Radar").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        rule.onNodeWithText("Overview").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithText("Refresh weather").assertExists()
        rule.runOnIdle { assertEquals(0, requests.get()) }
    }

    private fun showWeather(handheld: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = memoryPreferences()
        val repository = WeatherRepository(context, AppPreferences(storage), storage, client)
        val viewModel = WeatherViewModel(repository)
        viewModels.put("weather", viewModel)
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides handheld) {
                AmbientTvTheme { WeatherScreen(onOpenSettings = {}, viewModel = viewModel) }
            }
        }
    }

    private fun swipe(left: Boolean) {
        rule.onRoot().performTouchInput {
            // Use the page body so the horizontally scrolling category strip keeps its gestures.
            val from = Offset(width * if (left) .85f else .15f, height * .7f)
            val to = Offset(width * if (left) .15f else .85f, height * .7f)
            swipe(start = from, end = to, durationMillis = 300)
        }
    }

}
