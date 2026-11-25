package com.ambient.tv.ui.games

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import com.ambient.tv.R
import com.ambient.tv.games.GameCoreOption
import com.ambient.tv.games.GameCoreValue
import com.ambient.tv.games.GameOptionOrigin
import com.ambient.tv.games.GameShader
import com.ambient.tv.games.GameSystems
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.player.LocalPlayerChromeState
import com.ambient.tv.ui.player.LocalPlayerControlFocus
import com.ambient.tv.ui.player.PANEL_ENTRANCE_SETTLE_FRAMES
import com.ambient.tv.ui.player.PLAYER_PANEL_ENTER_MS
import com.ambient.tv.ui.player.PLAYER_PANEL_EXIT_MS
import com.ambient.tv.ui.player.PlayerChromeState
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.util.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real shared buttons/panels exercise origin return without starting a native game core. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1080dp-h720dp-land-mdpi")
@OptIn(ExperimentalTestApi::class)
class GamePanelNavigationUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val chrome = PlayerChromeState()
    private lateinit var panels: GamePanelNavigation
    private lateinit var inputModeManager: InputModeManager
    private val continueRequester = FocusRequester()
    private val settingsRequester = FocusRequester()
    private var showSettings by mutableStateOf(true)
    private var showContinue by mutableStateOf(true)
    private var showMutation by mutableStateOf(true)
    private var renderer by mutableStateOf("software")
    private var shader by mutableStateOf(GameShader.SHARP)
    private var disc by mutableStateOf(0)

    private fun mount(handheld: Boolean = false) {
        rule.mainClock.autoAdvance = false
        chrome.osd.expand()
        rule.setContent {
            inputModeManager = LocalInputModeManager.current
            panels = rememberGamePanelNavigation(chrome)
            CompositionLocalProvider(
                LocalHandheldUi provides handheld,
                LocalPlayerChromeState provides chrome,
                LocalPlayerControlFocus provides chrome.focus,
                LocalPlayerPanelNavigation provides panels.navigation,
            ) {
                AmbientTvTheme {
                    RequestGameOsdFocus(true, panels, continueRequester, settingsRequester)
                    Box(Modifier.fillMaxSize().testTag("game-player-viewport")) {
                        if (panels.panel == null) Column {
                            Row {
                                if (showContinue) PlayerIconButton(Icons.Filled.PlayArrow, "Continue", {},
                                    primary = true, modifier = Modifier.testTag("continue").focusRequester(continueRequester))
                                PlayerIconButton(Icons.Filled.Bookmarks, "States", panels::toggleStates,
                                    modifier = Modifier.testTag("states"))
                                if (showMutation) PlayerIconButton(Icons.Filled.Save, "Mutation", {
                                    showMutation = false
                                    showContinue = false
                                }, modifier = Modifier.testTag("mutation"))
                                if (showSettings) PlayerIconButton(Icons.Filled.Settings, "Settings", {
                                    panels.open(GamePanel.SETTINGS)
                                }, modifier = Modifier.testTag("settings").focusRequester(settingsRequester))
                            }
                            if (panels.statesOpen) TvActionButton("Close states", { panels.closeStates() },
                                modifier = Modifier.testTag("close-states"))
                        }
                        val page = panels.page
                        if (page != null) GamePanelOverlay(page, onOutsideTap = panels::close) {
                            // Production supplies no Box alignment: the game drawer owns it.
                            GameSidePanel(panels, title = "Options", modifier = Modifier.testTag("game-side-panel")) {
                                when {
                                    page.panel == GamePanel.SETTINGS -> {
                                        GamePanelChoice("Picture", false, { panels.open(GamePanel.PICTURE) },
                                            modifier = Modifier.testTag("picture-entry"))
                                        GamePanelChoice("Core", false, { panels.open(GamePanel.CORE) },
                                            modifier = Modifier.testTag("core-entry"))
                                        GamePanelChoice("Unavailable", false, {}, enabled = false,
                                            modifier = Modifier.testTag("unavailable"))
                                        GameDiscSelector(3, disc) { disc = it }
                                    }
                                    page.panel == GamePanel.CORE && page.optionCategory == null -> {
                                        GamePanelChoice("Video", false, { panels.openCategory("video") },
                                            modifier = Modifier.testTag("category-entry"), focusKey = "category:video")
                                    }
                                    page.panel == GamePanel.CORE -> {
                                        GameCoreOptionSelector(GameCoreOption("renderer", "Renderer", listOf(
                                            GameCoreValue("software", "Software"), GameCoreValue("hardware", "Hardware")),
                                            renderer, info = "Select the renderer."), GameOptionOrigin.DEFAULT) { renderer = it }
                                    }
                                    page.panel == GamePanel.PICTURE -> {
                                        GamePictureFilterSelector(GameSystems.byId("snes")!!, shader, null, emptyList(),
                                            onShaderSelected = { shader = it }, onPackSelected = {})
                                    }
                                    else -> GamePanelChoice("Value", false, {}, modifier = Modifier.testTag("value-entry"))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!handheld) rule.runOnIdle { assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard)) }
        settle()
    }

    private fun frame() {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun settle() {
        rule.waitForIdle()
        frame()
        rule.mainClock.advanceTimeBy(PLAYER_PANEL_EXIT_MS.toLong())
        rule.waitForIdle()
        frame()
        repeat(PANEL_ENTRANCE_SETTLE_FRAMES + 1) { frame() }
        rule.mainClock.advanceTimeBy(PLAYER_PANEL_ENTER_MS.toLong())
        rule.waitForIdle()
        // Also cover a deliberately removed origin's bounded retry and its fallback.
        rule.mainClock.advanceTimeBy(800)
        rule.waitForIdle()
    }

    private fun click(tag: String) {
        rule.onNodeWithTag(tag).assertIsDisplayed().performClick()
        settle()
    }

    private fun back() {
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    private fun panelBounds(): Rect = rule.onNodeWithTag("game-side-panel", useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot

    private fun handheldPanelContentBounds(): Rect {
        // On handheld the modifier/tag belongs to the full-screen outside-tap Row. The
        // visible drawer is its inner column: the unscrolled title and trailing footer
        // button expose its content edges without depending on body scrollbar overflow.
        val title = rule.onNodeWithText("Options").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val footer = rule.onNodeWithText(rule.activity.getString(R.string.action_close))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        return Rect(title.left, title.top, footer.right, footer.bottom)
    }

    @Test fun tvDrawerEntersAndLeavesAtTheRightEdgeWithoutCrossingThePlayer() {
        mount()
        val viewport = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val restingLeft = viewport.right - viewport.width * .38f
        rule.onNodeWithTag("settings").performClick()
        var previousLeft = Float.POSITIVE_INFINITY
        repeat(PANEL_ENTRANCE_SETTLE_FRAMES + 22) {
            frame()
            val bounds = panelBounds()
            if (bounds.width > 0f) {
                assertTrue("A TV drawer must never paint to the left of its right-side slot: $bounds",
                    bounds.left >= restingLeft - 1f)
                assertTrue("Entrance travels left towards the right-side slot", bounds.left <= previousLeft + 1f)
                previousLeft = bounds.left
            }
        }
        val opened = panelBounds()
        assertEquals(restingLeft, opened.left, 1f)
        assertEquals(viewport.right, opened.right, 1f)

        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        previousLeft = opened.left
        repeat(18) {
            frame()
            val bounds = rule.onAllNodesWithTag("game-side-panel", useUnmergedTree = true)
                .fetchSemanticsNodes().singleOrNull()?.boundsInRoot
            if (bounds != null && bounds.width > 0f) {
                assertTrue("Exit must move towards the right edge", bounds.left >= previousLeft - 1f)
                previousLeft = bounds.left
            }
        }
        rule.onNodeWithTag("game-side-panel", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun phoneLandscapeKeepsTheSharedNarrowPanelAtTheRightEdge() {
        mount(handheld = true)
        click("settings")
        val viewport = rule.onNodeWithTag("game-player-viewport").fetchSemanticsNode().boundsInRoot
        val content = handheldPanelContentBounds()
        // mdpi: the shared drawer has an 8dp outer end gap and 16dp/24dp content insets.
        assertEquals(viewport.right - 8f - 24f, content.right, 1f)
        assertEquals(viewport.right - viewport.width * .38f - 8f + 16f, content.left, 1f)
        assertEquals(viewport.width * .38f - 16f - 24f, content.width, 1f)
        assertTrue("Landscape leaves the game visible beside the panel: $content in $viewport",
            content.left > viewport.center.x)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    fun phonePortraitKeepsTheExistingFullWidthPanel() {
        mount(handheld = true)
        click("settings")
        val viewport = rule.onNodeWithTag("game-player-viewport").fetchSemanticsNode().boundsInRoot
        val content = handheldPanelContentBounds()
        assertEquals(viewport.left + 16f, content.left, 1f)
        assertEquals(viewport.right - 8f - 24f, content.right, 1f)
        assertEquals(viewport.width - 8f - 16f - 24f, content.width, 1f)
    }

    @Test fun nestedCoreBackRestoresEachOpeningRowAndFinallyTheSettingsButton() {
        mount()
        rule.onNodeWithTag("continue").assertIsFocused()
        click("settings")
        val drawerBounds = panelBounds()
        rule.onNodeWithTag("unavailable").assertDoesNotExist()
        click("core-entry")
        click("category-entry")
        assertEquals("Subpages stay inside the same anchored drawer", drawerBounds, panelBounds())
        rule.onNodeWithText("Renderer").performClick()
        settle()
        rule.runOnIdle {
            assertEquals(GamePanelPage(GamePanel.CORE, optionCategory = "video"), panels.page)
            assertEquals(3, panels.navigation.depth)
        }
        back()
        rule.onNodeWithText("Renderer").assertIsFocused()
        back()
        rule.onNodeWithTag("category-entry").assertIsFocused()
        back()
        rule.onNodeWithTag("core-entry").assertIsFocused()
        assertEquals("Back preserves the drawer position", drawerBounds, panelBounds())
        back()
        rule.onNodeWithTag("settings").assertIsFocused()
    }

    @Test fun selectingACoreValueByRemoteAppliesItWithoutOpeningAnotherPage() {
        mount()
        click("settings")
        click("core-entry")
        click("category-entry")
        rule.runOnIdle { assertTrue(panels.navigation.requestFocus("selector:null:game-core-option:renderer")) }
        rule.onNodeWithText("Renderer").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        settle()
        val defaultValue = "Software · ${rule.activity.getString(R.string.game_core_value_default)}"
        rule.onNodeWithText(defaultValue).assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        settle()
        rule.onNodeWithText("Hardware").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        settle()
        rule.runOnIdle {
            assertEquals("hardware", renderer)
            assertEquals(GamePanelPage(GamePanel.CORE, optionCategory = "video"), panels.page)
            assertEquals(3, panels.navigation.depth)
        }
        rule.onNodeWithText(defaultValue).assertDoesNotExist()
        rule.onNodeWithText("Renderer").assertIsFocused()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    fun phoneDiscAndFilterSelectionsApplyInTheirExistingSettingsPages() {
        mount(handheld = true)
        click("settings")
        val discLabel = rule.activity.getString(R.string.game_panel_disc)
        rule.onNodeWithText(discLabel).assertIsDisplayed().performTouchInput { click() }
        settle()
        rule.onNodeWithText(rule.activity.getString(R.string.game_disc, 2))
            .assertIsDisplayed().performTouchInput { click() }
        settle()
        rule.runOnIdle {
            assertEquals(1, disc)
            assertEquals(GamePanelPage(GamePanel.SETTINGS), panels.page)
            assertEquals(1, panels.navigation.depth)
        }
        click("picture-entry")
        val filterLabel = rule.activity.getString(R.string.game_picture_filter)
        rule.onNodeWithText(filterLabel).assertIsDisplayed().performTouchInput { click() }
        settle()
        rule.onNodeWithText(rule.activity.getString(GameShader.SMOOTH.titleRes))
            .performScrollTo().assertIsDisplayed().performTouchInput { click() }
        settle()
        rule.runOnIdle {
            assertEquals(GameShader.SMOOTH, shader)
            assertEquals(GamePanelPage(GamePanel.PICTURE), panels.page)
            assertEquals(2, panels.navigation.depth)
        }
        back()
        rule.runOnIdle { assertEquals(GamePanel.SETTINGS, panels.panel) }
    }

    @Test fun closingStateStripReturnsToStatesRatherThanContinue() {
        mount()
        click("states")
        click("close-states")
        rule.onNodeWithTag("states").assertIsFocused()
    }

    @Test fun aRemovedPanelOriginFallsBackToContinue() {
        mount()
        click("settings")
        rule.runOnIdle { showSettings = false }
        back()
        rule.onNodeWithTag("settings").assertDoesNotExist()
        rule.onNodeWithTag("continue").assertIsFocused()
    }

    @Test fun disappearingBusyControlsRecoverToAlwaysAvailableSettings() {
        mount()
        rule.runOnIdle { assertTrue(panels.navigation.requestFocus("bar:Mutation")) }
        rule.onNodeWithTag("mutation").assertIsFocused()
        click("mutation")
        rule.onNodeWithTag("mutation").assertDoesNotExist()
        rule.onNodeWithTag("continue").assertDoesNotExist()
        rule.onNodeWithTag("settings").assertIsFocused()
        // Recovery is one hand-back. Subsequent intentional navigation stays where it went.
        rule.runOnIdle { assertTrue(panels.navigation.requestFocus("bar:States")) }
        settle()
        rule.onNodeWithTag("states").assertIsFocused()
    }
}
