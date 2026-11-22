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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real shared buttons/panels exercise origin return without starting a native game core. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1080dp-h720dp-land-mdpi")
class GamePanelNavigationUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val chrome = PlayerChromeState()
    private lateinit var panels: GamePanelNavigation
    private val continueRequester = FocusRequester()
    private val settingsRequester = FocusRequester()
    private var showSettings by mutableStateOf(true)
    private var showContinue by mutableStateOf(true)
    private var showMutation by mutableStateOf(true)

    private fun mount() {
        rule.mainClock.autoAdvance = false
        chrome.osd.expand()
        rule.setContent {
            panels = rememberGamePanelNavigation(chrome)
            CompositionLocalProvider(
                LocalHandheldUi provides false,
                LocalPlayerChromeState provides chrome,
                LocalPlayerControlFocus provides chrome.focus,
                LocalPlayerPanelNavigation provides panels.navigation,
            ) {
                AmbientTvTheme {
                    RequestGameOsdFocus(true, panels, continueRequester, settingsRequester)
                    Box(Modifier.fillMaxSize()) {
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
                            GameSidePanel(panels, title = "Options", modifier = Modifier.align(Alignment.TopEnd)) {
                                when {
                                    page.panel == GamePanel.SETTINGS -> {
                                        GamePanelChoice("Picture", false, { panels.open(GamePanel.PICTURE) },
                                            modifier = Modifier.testTag("picture-entry"))
                                        GamePanelChoice("Core", false, { panels.open(GamePanel.CORE) },
                                            modifier = Modifier.testTag("core-entry"))
                                        GamePanelChoice("Unavailable", false, {}, enabled = false,
                                            modifier = Modifier.testTag("unavailable"))
                                    }
                                    page.panel == GamePanel.CORE && page.optionCategory == null -> {
                                        GamePanelChoice("Video", false, { panels.openCategory("video") },
                                            modifier = Modifier.testTag("category-entry"), focusKey = "category:video")
                                    }
                                    page.panel == GamePanel.CORE && page.optionKey == null -> {
                                        GamePanelChoice("Renderer", false, { panels.openOption("renderer") },
                                            modifier = Modifier.testTag("option-entry"), focusKey = "option:renderer")
                                    }
                                    else -> GamePanelChoice("Value", false, {}, modifier = Modifier.testTag("value-entry"))
                                }
                            }
                        }
                    }
                }
            }
        }
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

    @Test fun nestedCoreBackRestoresEachOpeningRowAndFinallyTheSettingsButton() {
        mount()
        rule.onNodeWithTag("continue").assertIsFocused()
        click("settings")
        rule.onNodeWithTag("unavailable").assertDoesNotExist()
        click("core-entry")
        click("category-entry")
        click("option-entry")
        back()
        rule.onNodeWithTag("option-entry").assertIsFocused()
        back()
        rule.onNodeWithTag("category-entry").assertIsFocused()
        back()
        rule.onNodeWithTag("core-entry").assertIsFocused()
        back()
        rule.onNodeWithTag("settings").assertIsFocused()
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
    }
}
