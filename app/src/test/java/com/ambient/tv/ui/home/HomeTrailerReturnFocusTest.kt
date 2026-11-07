package com.ambient.tv.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.ambient.tv.data.local.ClockSettings
import com.ambient.tv.data.local.HomeHeroStyle
import com.ambient.tv.ui.common.BrowseFocusReturnScope
import com.ambient.tv.ui.common.ContentRowData
import com.ambient.tv.ui.common.LocalClockSettings
import com.ambient.tv.ui.common.MediaTileStyle
import com.ambient.tv.ui.common.NavReselect
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.navigation.SidebarContentEntry
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.performance.PerformanceMode
import com.ambient.tv.ui.performance.PerformanceProfile
import com.ambient.tv.ui.theme.AmbientTvTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise Home's real nested lazy rows and shared return owner, without any live TMDB calls. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class HomeTrailerReturnFocusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Fixture(rowIds: List<String>) {
        val rows = rowIds.map { row -> ContentRowData(
            id = row, title = row, style = MediaTileStyle.LANDSCAPE, showDescription = false,
            items = List(24) { homeTrailerTestItem("video-$it", "$row $it") },
        ) }
        var focus by mutableStateOf(HomeFocusSnapshot("trailer-video:video-0", rowIds.first()))
        var state by mutableStateOf(HomeUiState.Loaded(
            rows = rows, rowKeys = rowIds, focusedRatingKey = focus.ratingKey, focusedRowId = focus.rowId,
        ))
        val hero = MutableStateFlow<HomeHeroContent?>(null)
        var lastFocus: HomeFocusSnapshot? = null
        var played: Pair<String, String>? = null
        var unrelatedClicks = 0

        fun returned(row: String, index: Int) {
            focus = HomeFocusSnapshot("trailer-video:video-$index", row)
            state = state.copy(focusedRatingKey = focus.ratingKey, focusedRowId = row,
                playbackReturnRevision = state.playbackReturnRevision + 1)
        }
    }

    @Before fun clearReselect() { NavReselect.restoreTop.value = false }

    private fun mount(rows: List<String> = listOf("trailers.CINEMA"), handheld: Boolean = false): Fixture {
        val fixture = Fixture(rows)
        rule.setContent {
            CompositionLocalProvider(
                LocalHandheldUi provides handheld,
                LocalClockSettings provides ClockSettings(display = ClockSettings.DISPLAY_OFF),
                LocalPerformanceProfile provides PerformanceProfile.resolve(PerformanceMode.LOW, lowEndDevice = false),
            ) {
                AmbientTvTheme {
                    BrowseFocusReturnScope {
                        LibraryContent(
                            state = fixture.state, loadingRowStyles = listOf(MediaTileStyle.LANDSCAPE),
                            heroContent = fixture.hero, homeHeroStyleKey = HomeHeroStyle.STATIC,
                            homeHeroIntensityPercent = 0, homeHeroSpreadKey = HomeHeroStyle.SOFT,
                            focusSnapshot = fixture.focus,
                            onItemClick = { fixture.unrelatedClicks++ },
                            onItemFocused = { row, item -> fixture.lastFocus = HomeFocusSnapshot(item.ratingKey, row) },
                            onTrailerHomeVideoClick = { video, row -> fixture.played = video to row },
                            onOpenRelevantSettings = {}, onWatchLiveChannel = {}, onLiveTvFocused = {},
                            onShortcutFocused = {}, onOpenHomeContent = {}, tvheadendImageHeaders = emptyMap(),
                            entranceProgress = { 1f },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        if (!handheld) {
            rule.runOnIdle { assertTrue(SidebarContentEntry.requestFocus() == true) }
            rule.waitForIdle()
        }
        return fixture
    }

    private fun tile(row: String, index: Int) = rule.onNode(hasText("$row $index") and hasClickAction())

    private fun focused(fixture: Fixture, row: String, index: Int) {
        try {
            tile(row, index).assertIsFocused()
        } catch (failure: AssertionError) {
            val nodes = rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
                .joinToString { it.config.toString() }
            throw AssertionError("${failure.message}\nLast Home focus: ${fixture.lastFocus}; focused nodes: $nodes", failure)
        }
    }

    @Test fun nextAndPreviousRetargetAnAlreadyComposedHomeBeyondItsViewport() {
        val row = "trailers.CINEMA"
        val fixture = mount()
        tile(row, 0).assertIsFocused()
        rule.runOnIdle { fixture.returned(row, 16) }
        rule.waitForIdle()
        focused(fixture, row, 16)
        rule.runOnIdle { fixture.returned(row, 15) }
        rule.waitForIdle()
        focused(fixture, row, 15)

        tile(row, 15).performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        tile(row, 14).assertIsFocused()
        // Updating Home's normal focus snapshot must never replay the player-return request.
        rule.runOnIdle { fixture.focus = checkNotNull(fixture.lastFocus) }
        rule.waitForIdle()
        tile(row, 14).assertIsFocused()
    }

    @Test fun duplicateVideoIdsReturnToTheirOriginatingRow() {
        val row = "trailers.SERIES"
        val fixture = mount(listOf("trailers.CINEMA", row))
        rule.runOnIdle { fixture.returned(row, 12) }
        rule.waitForIdle()
        focused(fixture, row, 12)
        rule.runOnIdle { assertEquals(HomeFocusSnapshot("trailer-video:video-12", row), fixture.lastFocus) }
    }

    @Test fun touchPlaybackPassesTheClickedRowWithoutAnyPriorFocus() {
        val row = "trailers.SERIES"
        val fixture = mount(listOf(row), handheld = true)
        tile(row, 1).performClick()
        rule.runOnIdle {
            assertEquals("video-1" to row, fixture.played)
            assertEquals(0, fixture.unrelatedClicks)
        }
    }
}
