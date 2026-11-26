package com.ambient.tv.ui.games

import android.app.Application
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.data.local.ClockSettings
import com.ambient.tv.games.library.LibraryGame
import com.ambient.tv.ui.common.LocalClockSettings
import com.ambient.tv.ui.common.FilterBarSetting
import com.ambient.tv.ui.common.LocalFilterBarSetting
import com.ambient.tv.ui.common.pageContentUp
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

/** Exercise the production controls through pointer events and Android native down/up pairs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class GameLibraryPresentationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun game(id: String = "snes-game", system: String = "snes") = LibraryGame(
        fileId = id, folderId = "folder", sourceId = "storage", path = "$id.sfc", filename = "$id.sfc",
        systemId = system, title = "Super Mario World", internalTitle = null,
        size = 123, modified = 1, favorite = false, lastPlayed = 0, metadataYear = 1990,
    )

    private class Actions {
        var selections = 0
        var details = 0
        var favorites = 0
        lateinit var requester: FocusRequester
    }

    private fun press(code: Int, repeat: Boolean = false) {
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, code, 0))
            if (repeat) rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 1L, AndroidKeyEvent.ACTION_DOWN, code, 1))
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 2L, AndroidKeyEvent.ACTION_UP, code, 0))
        }
        rule.waitForIdle()
    }

    private fun mountItem(handheld: Boolean, list: Boolean = false): Actions {
        val actions = Actions()
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides handheld) {
                AmbientTvTheme {
                    Box(Modifier.width(if (list) 800.dp else 240.dp)) {
                        if (list) GameListEntry(game(),
                            onPlay = { actions.selections++ }, onInfo = { actions.details++ },
                            onFavorite = { actions.favorites++ }, onFocus = {},
                            onRequester = { actions.requester = it }, onDisposed = {})
                        else GameTile(game(), artworkSlotAspectRatio = 1.5f,
                            onPlay = { actions.selections++ }, onInfo = { actions.details++ },
                            onFocus = {},
                            onRequester = { actions.requester = it }, onDisposed = {})
                    }
                }
            }
        }
        rule.waitForIdle()
        return actions
    }

    @Test fun gridAcceptsNativeActivationOnceAndKeepsInfoSeparate() {
        val actions = mountItem(handheld = false)
        rule.runOnIdle { actions.requester.requestFocus() }
        rule.waitForIdle()
        rule.onNodeWithTag("game-select:snes-game").assertIsFocused()
        for (code in listOf(AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, AndroidKeyEvent.KEYCODE_SPACE)) press(code, repeat = true)
        rule.runOnIdle { assertEquals(4, actions.selections) }
        press(AndroidKeyEvent.KEYCODE_INFO)
        press(AndroidKeyEvent.KEYCODE_MENU)
        rule.runOnIdle {
            assertEquals(4, actions.selections)
            assertEquals(2, actions.details)
        }
    }

    @Test fun phoneGridHandlesRealTapsAndLongPressWithoutAFavoriteButton() {
        val actions = mountItem(handheld = true)
        // Like Radio Browser's cards: no heart button on a card; list and detail change it.
        rule.onAllNodesWithTag("game-favorite:snes-game").assertCountEquals(0)
        rule.onNodeWithTag("game-select:snes-game").performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, actions.selections) }
        rule.onNodeWithTag("game-select:snes-game").performTouchInput { longClick() }
        rule.runOnIdle {
            assertEquals(1, actions.selections)
            assertEquals(1, actions.details)
        }
    }

    @Test fun favoriteCardShowsOnlyAStatusBadge() {
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true) {
                AmbientTvTheme {
                    Box(Modifier.width(240.dp)) {
                        GameTile(game().copy(favorite = true), artworkSlotAspectRatio = 1.5f,
                            onPlay = {}, onInfo = {}, onFocus = {}, onRequester = {}, onDisposed = {})
                    }
                }
            }
        }
        rule.onNodeWithTag("game-favorite-badge:snes-game", useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithTag("game-favorite:snes-game").assertCountEquals(0)
    }

    @Test fun listHasOneMainTargetAndFavoriteIsIndependentOnRemote() {
        val actions = mountItem(handheld = false, list = true)
        // Like the other libraries' lists: a small cover (or its fallback) on the left.
        rule.onNodeWithTag("game-list-cover:snes-game", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("SNES · 1990", useUnmergedTree = true).assertIsDisplayed()
        rule.runOnIdle { actions.requester.requestFocus() }
        rule.waitForIdle()
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER, repeat = true)
        rule.onNodeWithTag("game-favorite:snes-game")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.waitForIdle()
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER, repeat = true)
        rule.runOnIdle {
            assertEquals(1, actions.selections)
            assertEquals(1, actions.favorites)
            assertEquals(0, actions.details)
        }
    }

    @Test fun phoneListRowUsesRealTapAndKeepsFavoriteSeparate() {
        val actions = mountItem(handheld = true, list = true)
        rule.onNodeWithTag("game-select:snes-game").performTouchInput { click() }
        rule.onNodeWithTag("game-favorite:snes-game").performTouchInput { click() }
        rule.runOnIdle {
            assertEquals(1, actions.selections)
            assertEquals(1, actions.favorites)
        }
    }

    @Test fun mixedGridPreservesEachCoverShapeAndSharedTitleBaseline() {
        val landscape = game("wide").copy(coverWidth = 600, coverHeight = 400)
        val portrait = game("tall", "psp").copy(coverWidth = 240, coverHeight = 400)
        rule.setContent {
            AmbientTvTheme {
                Row {
                    for (entry in listOf(landscape, portrait)) GameTile(entry,
                        modifier = Modifier.width(220.dp), artworkSlotAspectRatio = .6f,
                        onPlay = {}, onInfo = {}, onFocus = {}, onRequester = {}, onDisposed = {})
                }
            }
        }
        rule.waitForIdle()
        val wide = rule.onNodeWithTag("game-cover:wide", useUnmergedTree = true).bounds()
        val tall = rule.onNodeWithTag("game-cover:tall", useUnmergedTree = true).bounds()
        assertEquals(1.5f, wide.width / wide.height, .02f)
        assertEquals(.6f, tall.width / tall.height, .02f)
        assertEquals(wide.bottom, tall.bottom, 1f)
        val wideTitle = rule.onNodeWithTag("game-title:wide", useUnmergedTree = true).bounds()
        val tallTitle = rule.onNodeWithTag("game-title:tall", useUnmergedTree = true).bounds()
        assertEquals(wideTitle.top, tallTitle.top, 1f)
        val fallbacks = rule.onAllNodesWithTag("game-cover-fallback", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(2, fallbacks.size)
        // The emblem sits centered on the shared card face, above the title when there is room.
        for ((index, cover) in listOf(wide, tall).withIndex()) {
            val emblem = fallbacks[index].boundsInRoot
            assertEquals(cover.center.x, emblem.center.x, 1f)
            assertTrue(emblem.top >= cover.top && emblem.bottom <= cover.bottom)
        }
    }

    @Test fun unusualArtworkFitsItsStageWithoutChangingTheSourceRatio() {
        for (ratio in listOf(1f / 8192f, .61f, 1f, 1.4f, 8192f)) {
            val size = gameCoverFrameSize(200.dp, 150.dp, ratio)
            assertTrue(size.width <= 200.dp)
            assertTrue(size.height <= 150.dp)
            assertEquals(ratio, size.width.value / size.height.value, ratio * .0001f)
        }
    }

    private class HeaderFixture {
        lateinit var controls: GameLibraryBrowseControls
        lateinit var content: FocusRequester
        var state by mutableStateOf(GameLibraryUiState(section = "all", ready = true))
        var filterBarShown by mutableStateOf(false)
        var searches = 0
        var refreshes = 0
    }

    private fun mountHeader(handheld: Boolean = false, width: Dp = 1100.dp): HeaderFixture {
        val fixture = HeaderFixture()
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides handheld,
                LocalClockSettings provides ClockSettings(display = ClockSettings.DISPLAY_OFF),
                LocalFilterBarSetting provides FilterBarSetting(fixture.filterBarShown) { fixture.filterBarShown = it }) {
                AmbientTvTheme {
                    val controls = rememberGameLibraryBrowseControls()
                    SideEffect { fixture.controls = controls }
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.width(width).testTag("games-page")) {
                            GameLibraryHeader(fixture.state, false, false, controls,
                                onManage = {}, onSearch = { fixture.searches++ },
                                onRefresh = { fixture.refreshes++ })
                            GameLibrarySections(listOf("all", "favorites", "snes"), fixture.state.section,
                                controls, onSelect = { fixture.state = fixture.state.copy(section = it) })
                            GameLibrarySortBar(fixture.state, controls,
                                onSort = { fixture.state = fixture.state.copy(sortKey = it) },
                                onSortDescending = { fixture.state = fixture.state.copy(sortDescending = it) },
                                modifier = Modifier.testTag("games-sort-bar"))
                            Box(Modifier.fillMaxWidth().pageContentUp(controls.focusChain).focusGroup()) {
                                GameTile(game(), Modifier.width(220.dp), artworkSlotAspectRatio = 1.5f,
                                    onPlay = {}, onInfo = {}, onFocus = {},
                                    onRequester = { fixture.content = it }, onDisposed = {})
                            }
                        }
                        GameLibraryViewOptionsPanel(fixture.state, controls,
                            onView = { fixture.state = fixture.state.copy(view = it) },
                            onGridColumns = { fixture.state = fixture.state.copy(gridColumns = it) },
                            onListRows = { fixture.state = fixture.state.copy(listRows = it) },
                            onSort = { fixture.state = fixture.state.copy(sortKey = it) },
                            onSortDescending = { fixture.state = fixture.state.copy(sortDescending = it) },
                            onTitleStyle = { fixture.state = fixture.state.copy(titleStyle = it) })
                    }
                }
            }
        }
        rule.waitForIdle()
        return fixture
    }

    private fun action(id: Int): SemanticsMatcher {
        val label = rule.activity.getString(id)
        return (hasText(label) or hasContentDescription(label)) and hasClickAction()
    }

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    @Test fun commonHeaderOrderAndPositionRemainStableWhileScanning() {
        val fixture = mountHeader()
        val ordered = listOf(R.string.game_library_manage_action, R.string.action_search,
            R.string.action_reload, R.string.action_view).map { rule.onNode(action(it)).bounds() }
        ordered.zipWithNext().forEach { (previous, next) -> assertTrue(previous.right <= next.left) }
        val view = ordered.last()
        rule.runOnIdle { fixture.state = fixture.state.copy(scanning = true, busy = true) }
        rule.waitForIdle()
        assertEquals(view.top, rule.onNode(action(R.string.action_view)).bounds().top, 1f)
        assertEquals(view.right, rule.onNode(action(R.string.action_view)).bounds().right, 1f)
        rule.onAllNodes(action(R.string.game_library_scan_cancel)).assertCountEquals(0)
    }

    @Test fun nativeNavigationVisitsCategoriesAndContentThenPanelReturnsToView() {
        val fixture = mountHeader()
        rule.runOnIdle { fixture.controls.optionsFocus.requestFocus() }
        rule.waitForIdle()
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNode(action(R.string.game_library_all)).assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("game-select:snes-game").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNode(action(R.string.game_library_all)).assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNode(action(R.string.action_view)).assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        rule.runOnIdle { assertTrue(fixture.controls.showOptions) }
        rule.mainClock.advanceTimeBy(500)
        rule.runOnIdle { fixture.state = fixture.state.copy(view = "list", gridColumns = 6) }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.runOnIdle { assertFalse(fixture.controls.showOptions) }
        rule.onNode(action(R.string.action_view)).assertIsFocused()
    }

    @Test fun sortingStaysAvailableWhenTheGlobalFilterBarIsEnabled() {
        val fixture = mountHeader()
        rule.onNodeWithTag("games-sort-bar").assertDoesNotExist()
        rule.runOnIdle { fixture.controls.optionsFocus.requestFocus() }
        rule.waitForIdle()
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        // Sorting follows layout, density and title controls in the scrollable common panel.
        rule.onNodeWithText(rule.activity.getString(R.string.library_sort)).performScrollTo().assertIsDisplayed()
        rule.onNode(action(R.string.library_filter_bar)).performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.waitForIdle()
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        rule.runOnIdle { assertTrue(fixture.filterBarShown) }
        rule.onNodeWithTag("games-sort-bar").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag("games-sort-bar").assertIsDisplayed()
        rule.onNode(action(R.string.action_view)).assertIsFocused()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    fun narrowPhoneKeepsAllFourHeaderActionsReachable() {
        mountHeader(handheld = true, width = 320.dp)
        val page = rule.onNodeWithTag("games-page").bounds()
        for (label in listOf(R.string.game_library_manage_action, R.string.action_search,
            R.string.action_reload, R.string.action_view)) {
            val action = rule.onNode(action(label))
            action.assertIsDisplayed()
            val bounds = action.bounds()
            assertTrue(bounds.left >= page.left - 1f && bounds.right <= page.right + 1f)
            assertTrue(bounds.width >= 48f && bounds.height >= 48f)
        }
    }
}
