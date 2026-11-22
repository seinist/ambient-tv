package com.ambient.tv.ui.common

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.ambient.tv.data.local.ClockSettings
import com.ambient.tv.ui.settings.SettingsHeaderRow
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.ui.weather.LocalWeatherPresentation
import com.ambient.tv.ui.weather.WeatherPresentation
import com.ambient.tv.util.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The natural-width clock stays flush right on every TV header, regardless of title
 * length, action count, large date layout or the initial weather placeholder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1200dp-h720dp-land-mdpi")
class HeaderClockAlignmentTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // TvChip reserves 2.dp around its visible/focus surface. Semantics boundaries refer to
    // that visible chip, not the outer layout slot; mdpi qualifiers make 1.dp == 1 px here.
    private val tvChipInsetPx = 2f

    private fun clockSettings(weatherLayout: String = ClockSettings.WEATHER_LAYOUT_LEFT) = ClockSettings(
        display = ClockSettings.DISPLAY_EVERYWHERE,
        size = ClockSettings.SIZE_LARGE,
        date = ClockSettings.DATE_LONG,
        weatherLayout = weatherLayout,
    )

    @Test fun settingsActionsStayImmediatelyLeftOfRightAnchoredClock() {
        val visible = mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(
                LocalClockSettings provides clockSettings(),
                LocalWeatherPresentation provides WeatherPresentation(headerPending = true),
            ) {
                AmbientTvTheme {
                    SettingsHeaderRow(clockVisible = visible.value, modifier = Modifier.testTag("settings-header")) {
                        Box(Modifier.size(32.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Long settings title which must yield to the actions and clock")
                            Text("Subtitle")
                        }
                        TvChip("Search", modifier = Modifier.testTag("settings-search"), onClick = {})
                        TvChip("Advanced settings", modifier = Modifier.testTag("settings-level"), onClick = {})
                        TvChip("History", modifier = Modifier.testTag("settings-history"), onClick = {})
                    }
                }
            }
        }
        rule.waitForIdle()
        val header = rule.onNodeWithTag("settings-header").fetchSemanticsNode().boundsInRoot
        val clock = rule.onNodeWithTag("settings-header-clock").fetchSemanticsNode().boundsInRoot
        val history = rule.onNodeWithTag("settings-history").fetchSemanticsNode().boundsInRoot
        assertEquals("Settings clock must end at the right edge", header.right, clock.right, 1.01f)
        assertTrue("Header actions must remain left of the clock", history.right <= clock.left)

        rule.runOnIdle { visible.value = false }
        rule.waitForIdle()
        val withoutClock = rule.onNodeWithTag("settings-history").fetchSemanticsNode().boundsInRoot
        assertEquals("Without a clock, only TvChip's focus inset remains",
            header.right - tvChipInsetPx, withoutClock.right, 1.01f)
    }

    @Test fun sharedPageHeaderUsesTheSameRightEdgeAsSettings() {
        rule.setContent {
            CompositionLocalProvider(
                LocalClockSettings provides clockSettings(),
                LocalWeatherPresentation provides WeatherPresentation(headerPending = true),
            ) {
                AmbientTvTheme {
                    PageHeader(
                        modifier = Modifier.testTag("page-header"),
                        actions = { TvChip("Browse", modifier = Modifier.testTag("page-action"), onClick = {}) },
                    ) {
                        Text("Long library name that cannot push controls off screen")
                    }
                }
            }
        }
        rule.waitForIdle()
        val header = rule.onNodeWithTag("page-header").fetchSemanticsNode().boundsInRoot
        val clock = rule.onNodeWithTag("page-header-clock").fetchSemanticsNode().boundsInRoot
        val action = rule.onNodeWithTag("page-action").fetchSemanticsNode().boundsInRoot
        assertEquals("Shared PageHeader clock must end at the right edge", header.right, clock.right, 1.01f)
        assertTrue("Page controls remain to the left of the clock", action.right <= clock.left)
    }

    @Test fun topBarsShareExactlyTheSameActionBaselineWithTallClockAndWeather() {
        rule.setContent {
            CompositionLocalProvider(
                LocalClockSettings provides clockSettings(ClockSettings.WEATHER_LAYOUT_ABOVE),
                LocalWeatherPresentation provides WeatherPresentation(headerPending = true),
            ) {
                AmbientTvTheme {
                    Column {
                        PageHeader(
                            modifier = Modifier.testTag("page-header"),
                            actions = { TvChip("Browse", modifier = Modifier.testTag("page-action"), onClick = {}) },
                        ) { Text("SmartTube and library") }
                        SettingsHeaderRow(clockVisible = true, modifier = Modifier.testTag("settings-header")) {
                            Box(Modifier.size(32.dp))
                            Column(Modifier.weight(1f)) { Text("Settings") }
                            TvChip("Search", modifier = Modifier.testTag("settings-action"), onClick = {})
                        }
                        PageUtilityBar(onBack = {}, onSettings = {})
                    }
                }
            }
        }
        rule.waitForIdle()
        val page = rule.onNodeWithTag("page-header").fetchSemanticsNode().boundsInRoot
        val action = rule.onNodeWithTag("page-action").fetchSemanticsNode().boundsInRoot
        val pageClock = rule.onNodeWithTag("page-header-clock").fetchSemanticsNode().boundsInRoot
        val settings = rule.onNodeWithTag("settings-header").fetchSemanticsNode().boundsInRoot
        val settingsAction = rule.onNodeWithTag("settings-action").fetchSemanticsNode().boundsInRoot
        val settingsClock = rule.onNodeWithTag("settings-header-clock").fetchSemanticsNode().boundsInRoot
        val utilityBack = rule.onNodeWithTag("utility-header-back").fetchSemanticsNode().boundsInRoot
        val utilitySettings = rule.onNodeWithTag("utility-header-settings").fetchSemanticsNode().boundsInRoot
        val utilityClock = rule.onNodeWithTag("utility-header-clock").fetchSemanticsNode().boundsInRoot

        assertEquals("PageHeader action keeps its 2dp focus inset", page.top + tvChipInsetPx, action.top, 1.01f)
        assertEquals("Settings action keeps its 2dp focus inset", settings.top + tvChipInsetPx, settingsAction.top, 1.01f)
        assertEquals("Detail Back and Settings must share a top line", utilityBack.top, utilitySettings.top, 1.01f)
        assertEquals("Page action-to-clock gap includes TvChip inset", 20f + tvChipInsetPx, pageClock.left - action.right, 1.01f)
        assertEquals("Settings action-to-clock gap includes TvChip inset", 20f + tvChipInsetPx, settingsClock.left - settingsAction.right, 1.01f)
        assertEquals("Detail action-to-clock gap includes TvChip inset", 20f + tvChipInsetPx, utilityClock.left - utilitySettings.right, 1.01f)
    }

    @Test fun switchingWeatherLayoutNeverMovesPageActionsVertically() {
        val weatherLayout = mutableStateOf(ClockSettings.WEATHER_LAYOUT_LEFT)
        rule.setContent {
            CompositionLocalProvider(
                LocalClockSettings provides clockSettings(weatherLayout.value),
                LocalWeatherPresentation provides WeatherPresentation(headerPending = true),
            ) {
                AmbientTvTheme {
                    PageHeader(
                        modifier = Modifier.testTag("page-header"),
                        actions = { TvChip("Browse", modifier = Modifier.testTag("page-action"), onClick = {}) },
                    ) { Text("SmartTube") }
                }
            }
        }
        rule.waitForIdle()
        val originalTop = rule.onNodeWithTag("page-action").fetchSemanticsNode().boundsInRoot.top
        rule.runOnIdle { weatherLayout.value = ClockSettings.WEATHER_LAYOUT_ABOVE }
        rule.waitForIdle()
        val newTop = rule.onNodeWithTag("page-action").fetchSemanticsNode().boundsInRoot.top
        assertEquals("Top-bar actions should not jump when weather changes height", originalTop, newTop, 1.01f)
    }

    @Test fun pageActionsStillReachRightEdgeWhenClockIsDisabled() {
        rule.setContent {
            AmbientTvTheme {
                PageHeader(
                    showClock = false,
                    modifier = Modifier.testTag("page-header"),
                    actions = { TvChip("Search", modifier = Modifier.testTag("page-action"), onClick = {}) },
                ) { Text("Library") }
            }
        }
        rule.waitForIdle()
        val page = rule.onNodeWithTag("page-header").fetchSemanticsNode().boundsInRoot
        val action = rule.onNodeWithTag("page-action").fetchSemanticsNode().boundsInRoot
        assertEquals("No empty clock gap beyond TvChip's focus inset", page.right - tvChipInsetPx, action.right, 1.01f)
        assertEquals("No vertical shift beyond TvChip's focus inset", page.top + tvChipInsetPx, action.top, 1.01f)
    }
}
