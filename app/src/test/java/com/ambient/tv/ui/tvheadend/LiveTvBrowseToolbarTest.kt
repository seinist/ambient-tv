package com.ambient.tv.ui.tvheadend

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.data.local.ClockSettings
import com.ambient.tv.livetv.LiveTvBrowseState
import com.ambient.tv.livetv.LiveTvCapabilities
import com.ambient.tv.livetv.LiveTvSource
import com.ambient.tv.ui.common.FilterBar
import com.ambient.tv.ui.common.FilterBarModel
import com.ambient.tv.ui.common.FilterBarSetting
import com.ambient.tv.ui.common.LocalClockSettings
import com.ambient.tv.ui.common.LocalFilterBarSetting
import com.ambient.tv.ui.common.LocalFilterFocusState
import com.ambient.tv.ui.common.PanelOption
import com.ambient.tv.ui.common.PanelOptionGroup
import com.ambient.tv.ui.common.rememberPageFocusChain
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Render the production header and panel: options must not move into the scrollable filter row. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class LiveTvBrowseToolbarTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Fixture(
        val handheld: Boolean = false,
        val pageWidth: Dp = 1000.dp,
        val includeBrowseControls: Boolean = true,
        tab: TvheadendTab = TvheadendTab.LIVE,
        sourceName: String = "Test television",
        filtersShown: Boolean = true,
    ) {
        val browseState = LiveTvBrowseState()
        lateinit var controls: LiveTvBrowseControls
        var filtersShown by mutableStateOf(filtersShown)
        var state by mutableStateOf(
            TvheadendUiState(
                configured = true,
                source = LiveTvSource("fixture", "iptv", sourceName),
                capabilities = LiveTvCapabilities(available = true, guide = true, recordings = true, timers = true),
                tab = tab,
            ),
        )
        var refreshes = 0
        // Wider than either device: this exercises the real horizontally scrolling FilterBar.
        val filters = FilterBarModel(
            groups = (1..8).map { index ->
                PanelOptionGroup(
                    id = "filter-$index",
                    title = "Channel filter $index",
                    options = listOf(PanelOption("all", "All"), PanelOption("one", "First"), PanelOption("two", "Second")),
                    selectedKey = if (index <= 2) "one" else "all",
                )
            },
        )
    }

    private fun mount(fixture: Fixture) {
        rule.setContent {
            CompositionLocalProvider(
                LocalHandheldUi provides fixture.handheld,
                LocalClockSettings provides ClockSettings(display = ClockSettings.DISPLAY_OFF),
                LocalFilterBarSetting provides FilterBarSetting(fixture.filtersShown) { fixture.filtersShown = it },
            ) {
                AmbientTvTheme {
                    val controls = rememberLiveTvBrowseControls(fixture.browseState, "toolbar-test")
                    val focusChain = rememberPageFocusChain()
                    val tabs = remember { TvheadendTab.entries.associateWith { FocusRequester() } }
                    SideEffect { fixture.controls = controls }
                    CompositionLocalProvider(LocalFilterFocusState provides controls.filterFocusState) {
                        Box(Modifier.fillMaxSize()) {
                            Column(Modifier.width(fixture.pageWidth).testTag("browse-page")) {
                                TvheadendHeader(
                                    state = fixture.state,
                                    tabFocusRequesters = tabs,
                                    onTab = { fixture.state = fixture.state.copy(tab = it) },
                                    onRefresh = {
                                        fixture.refreshes++
                                        fixture.state = fixture.state.copy(refreshing = true)
                                    },
                                    focusChain = focusChain,
                                    browseControls = controls.takeIf { fixture.includeBrowseControls },
                                    activeFilterCount = fixture.filters.activeCount,
                                    visibleChannelCount = 120.takeIf { fixture.includeBrowseControls },
                                )
                                if (fixture.filtersShown) FilterBar(
                                    model = fixture.filters,
                                    modifier = Modifier.testTag("filter-bar"),
                                    focusRequester = controls.filterBarFocus,
                                    up = focusChain.chips,
                                )
                            }
                            LiveTvBrowseOptionsPanel(controls, fixture.filters)
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun action(label: String, substring: Boolean = false): SemanticsMatcher =
        (hasText(label, substring = substring) or hasContentDescription(label, substring = substring)) and hasClickAction()

    private fun viewMatcher() = action(rule.activity.getString(R.string.action_view), substring = true)
    private fun viewAction(): SemanticsNodeInteraction = rule.onNode(viewMatcher())
    private fun reloadAction() = rule.onNode(action(rule.activity.getString(R.string.action_reload)))
    private fun guideTab() = rule.onNode(action(rule.activity.getString(R.string.tvh_guide)))
    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot
    private fun SemanticsNodeInteraction.activateOnRemote() {
        performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        assertIsFocused()
        performKeyInput { pressKey(Key.DirectionCenter) }
    }

    @Test fun viewStaysAboveTabsAndOutsideTheFilterRowWhenTheBarChanges() {
        val fixture = Fixture()
        mount(fixture)

        rule.onAllNodes(viewMatcher()).assertCountEquals(1)
        viewAction().assertIsDisplayed()
        val initial = viewAction().bounds()
        assertTrue("View follows Reload", initial.left >= reloadAction().bounds().right)
        assertTrue("View belongs above the tabs", initial.bottom <= guideTab().bounds().top)
        assertTrue("View is outside the long filter row", initial.bottom <= rule.onNodeWithTag("filter-bar").bounds().top)

        rule.runOnIdle { fixture.filtersShown = false }
        rule.waitForIdle()
        rule.onNodeWithTag("filter-bar").assertDoesNotExist()
        rule.onAllNodes(viewMatcher()).assertCountEquals(1)
        viewAction().assertIsDisplayed()
        val withoutFilters = viewAction().bounds()
        assertEquals("View keeps its header height", initial.top, withoutFilters.top, 1f)
        assertEquals("The active-filter label keeps the same right edge", initial.right, withoutFilters.right, 1f)
        assertTrue("View still follows Reload", withoutFilters.left >= reloadAction().bounds().right)

        rule.runOnIdle {
            fixture.filtersShown = true
            fixture.state = fixture.state.copy(tab = TvheadendTab.GUIDE)
        }
        rule.waitForIdle()
        rule.onAllNodes(viewMatcher()).assertCountEquals(1)
        assertEquals(initial.top, viewAction().bounds().top, 1f)
        assertEquals(initial.right, viewAction().bounds().right, 1f)
        assertTrue(viewAction().bounds().bottom <= guideTab().bounds().top)
    }

    @Test fun refreshKeepsTheViewActionAndClosingItsPanelReturnsFocusToIt() {
        val fixture = Fixture()
        mount(fixture)
        val initial = viewAction().bounds()

        reloadAction().activateOnRemote()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1, fixture.refreshes) }
        reloadAction().activateOnRemote()
        rule.runOnIdle { assertEquals("Refreshing ignores repeat activation", 1, fixture.refreshes) }
        assertEquals(initial.top, viewAction().bounds().top, 1f)
        assertEquals(initial.right, viewAction().bounds().right, 1f)
        rule.runOnIdle {
            fixture.state = fixture.state.copy(refreshing = false)
            fixture.controls.optionsFocus.requestFocus()
        }
        rule.waitForIdle()
        viewAction().assertIsFocused()

        viewAction().activateOnRemote()
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(fixture.controls.showOptions) }
        rule.onNodeWithText(rule.activity.getString(R.string.library_options)).assertIsDisplayed()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.runOnIdle { assertFalse(fixture.controls.showOptions) }
        rule.onNodeWithText(rule.activity.getString(R.string.library_options)).assertDoesNotExist()
        viewAction().assertIsFocused()
    }

    @Test fun recordingAndTimerHeadersDoNotInventAViewActionWithoutBrowseControls() {
        val fixture = Fixture(includeBrowseControls = false, tab = TvheadendTab.RECORDINGS, filtersShown = false)
        mount(fixture)
        rule.onAllNodes(viewMatcher()).assertCountEquals(0)
        reloadAction().assertIsDisplayed()

        rule.runOnIdle { fixture.state = fixture.state.copy(tab = TvheadendTab.TIMERS) }
        rule.waitForIdle()
        rule.onAllNodes(viewMatcher()).assertCountEquals(0)
        reloadAction().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    fun narrowPhoneKeepsBothActionsVisibleWithALongSourceNameAndActiveFilters() {
        val sourceName = "A very long IPTV source name that must leave room for the page actions"
        mount(Fixture(handheld = true, pageWidth = 320.dp, sourceName = sourceName, filtersShown = false))
        viewAction().assertIsDisplayed()
        reloadAction().assertIsDisplayed()
        val page = rule.onNodeWithTag("browse-page").bounds()
        val view = viewAction().bounds()
        val reload = reloadAction().bounds()
        for ((label, bounds) in listOf("View" to view, "Reload" to reload)) {
            assertTrue("$label stays inside the narrow page", bounds.left >= page.left - 1f && bounds.right <= page.right + 1f)
            assertTrue("$label has a usable touch target", bounds.width >= 48f && bounds.height >= 48f)
        }
        assertTrue("The actions do not overlap", reload.right <= view.left)
        val title = rule.onNodeWithText("$sourceName · ${rule.activity.getString(R.string.tvh_live)}").bounds()
        assertFalse("The long title does not cover the actions", title.overlaps(view) || title.overlaps(reload))
    }
}
