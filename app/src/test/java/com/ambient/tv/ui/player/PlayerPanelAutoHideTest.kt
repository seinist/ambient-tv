package com.ambient.tv.ui.player

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.common.rememberPlayerPanelNavigation
import com.ambient.tv.ui.mobile.LocalHandheldUi
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayerPanelAutoHideTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Fixture {
        lateinit var chrome: PlayerChromeState
        val actionFocus = FocusRequester()
        var renderer by mutableIntStateOf(0)
        var statsTick by mutableIntStateOf(0)
        var changing by mutableStateOf(false)
        var dialog by mutableStateOf(false)
        var selected by mutableStateOf("first")
        var keys = 0
        var dragMoves = 0
        var clicks = 0
        var visualPanelComposed = false
        var panelInteractive = false
    }

    private fun mount(): Fixture {
        val fixture = Fixture()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PersistentPlayerChrome(fixture, changingItem = fixture.changing) {
                val chrome = LocalPlayerChromeState.current!!
                val navigation = rememberPlayerPanelNavigation()
                SideEffect { fixture.chrome = chrome }
                CompositionLocalProvider(LocalPlayerPanelNavigation provides navigation, LocalHandheldUi provides true) {
                    PlayerControlsAutoHide(chrome.visible.value, true, navigation.panel != UnifiedPlayerPanel.NONE) {
                        chrome.visible.value = it
                    }
                    Box(Modifier.fillMaxSize()) {
                        BasicText("Engine", Modifier.focusRequester(chrome.engineFocus).focusable())
                        PlayerOverlayVisibility(chrome.visible.value) { BasicText("OSD") }
                        PlayerPanelVisibility(navigation.panel) {
                            DisposableEffect(Unit) {
                                fixture.visualPanelComposed = true
                                onDispose { fixture.visualPanelComposed = false }
                            }
                            val panelInteractive = LocalPlayerPanelInteractive.current
                            SideEffect { fixture.panelInteractive = panelInteractive }
                            key(fixture.renderer) {
                                Column {
                                    BasicText("Action ${fixture.statsTick}", Modifier.size(200.dp, 48.dp).testTag("action")
                                        .focusRequester(fixture.actionFocus)
                                        .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown) fixture.keys++; true }
                                        .clickable { fixture.clicks++ })
                                    BasicText("Drag", Modifier.size(200.dp, 48.dp).testTag("drag")
                                        .pointerInput(Unit) {
                                            detectHorizontalDragGestures { change, _ ->
                                                change.consume()
                                                fixture.dragMoves++
                                            }
                                        })
                                    PlayerDropUpSelector("Options", fixture.selected,
                                        listOf("first" to "First option", "second" to "Second option"), fixture.selected) {
                                        fixture.selected = it
                                    }
                                    if (fixture.dialog) AppDialog(
                                        onDismiss = { fixture.dialog = false }, title = "Unfinished input",
                                    ) { BasicText("Editing") }
                                }
                            }
                        }
                    }
                }
            }
        }
        advance(16)
        rule.runOnIdle {
            fixture.chrome.osd.expand()
            fixture.chrome.panels.fromOrigin("bar:settings", true) {
                fixture.chrome.panels.open(UnifiedPlayerPanel.SETTINGS)
            }
        }
        advance(300)
        return fixture
    }

    private fun advance(milliseconds: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(milliseconds)
        rule.waitForIdle()
    }

    private fun assertClosed(fixture: Fixture) {
        rule.runOnIdle {
            assertEquals(UnifiedPlayerPanel.NONE, fixture.chrome.panels.panel)
            assertEquals(PlayerOsdMode.HIDDEN, fixture.chrome.osd.mode)
            assertFalse(fixture.chrome.osd.closing)
            assertNull(fixture.chrome.panels.restoreKey)
        }
        rule.onNodeWithTag("action").assertDoesNotExist()
        rule.onNodeWithText("OSD").assertDoesNotExist()
    }

    @Test fun manualCloseFinishesItsVisualExitButStopsPanelInteractionImmediately() {
        val fixture = mount()
        rule.runOnIdle {
            assertTrue(fixture.visualPanelComposed)
            assertTrue(fixture.panelInteractive)
            fixture.chrome.panels.close()
        }
        advance(16)
        rule.runOnIdle {
            assertEquals(UnifiedPlayerPanel.NONE, fixture.chrome.panels.panel)
            assertTrue(fixture.visualPanelComposed)
            assertFalse(fixture.panelInteractive)
        }
        advance(PLAYER_PANEL_EXIT_MS + 32L)
        rule.runOnIdle { assertFalse(fixture.visualPanelComposed) }
    }

    @Test fun idleClosesEverythingAndRendererStatsOrProgrammaticFocusDoNotRestartDeadline() {
        val fixture = mount()
        advance(28_000)
        rule.runOnIdle {
            fixture.renderer++
            fixture.statsTick++
            fixture.chrome.panels.noteFocused("entry:automatic-focus")
        }
        advance(2_200)
        assertClosed(fixture)
        advance(5_000)
        assertClosed(fixture)
    }

    @Test fun keyInputAndChildNavigationEachStartAFullDeadlineWhileManualBackStillReturnsToOsd() {
        val fixture = mount()
        rule.runOnIdle { fixture.actionFocus.requestFocus() }
        advance(28_000)
        rule.onNodeWithTag("action").performKeyInput { pressKey(Key.DirectionRight) }
        advance(28_000)
        rule.runOnIdle {
            assertTrue(fixture.keys > 0)
            assertEquals(UnifiedPlayerPanel.SETTINGS, fixture.chrome.panels.panel)
            fixture.chrome.panels.open(UnifiedPlayerPanel.AUDIO)
        }
        advance(28_000)
        rule.runOnIdle {
            assertEquals(UnifiedPlayerPanel.AUDIO, fixture.chrome.panels.panel)
            fixture.chrome.panels.back()
        }
        advance(28_000)
        rule.runOnIdle {
            assertEquals(UnifiedPlayerPanel.SETTINGS, fixture.chrome.panels.panel)
            fixture.chrome.panels.back()
        }
        advance(300)
        rule.onNodeWithText("OSD").assertExists()
        rule.runOnIdle { assertTrue(fixture.chrome.visible.value) }
    }

    @Test fun anActiveSliderGestureCannotTimeoutAndItsReleaseStartsAFreshDeadline() {
        val fixture = mount()
        advance(28_000)
        rule.onNodeWithTag("drag").performTouchInput { down(center) }
        advance(32_000)
        rule.onNodeWithTag("drag").assertExists()
        rule.onNodeWithTag("drag").performTouchInput {
            moveBy(Offset(40f, 0f))
            moveBy(Offset(30f, 0f))
            up()
        }
        advance(28_000)
        rule.runOnIdle {
            assertTrue(fixture.dragMoves > 0)
            assertEquals(UnifiedPlayerPanel.SETTINGS, fixture.chrome.panels.panel)
        }
        advance(2_200)
        assertClosed(fixture)
    }

    @Test fun dropdownInputResetsTheTimerButAnIdlePopupClosesWithItsPanel() {
        val fixture = mount()
        rule.onNodeWithText("Options").performClick()
        advance(28_000)
        val before = fixture.chrome.panels.activity.version
        rule.onNodeWithText("Second option").performKeyInput { pressKey(Key.DirectionDown) }
        advance(28_000)
        rule.runOnIdle { assertTrue(fixture.chrome.panels.activity.version > before) }
        rule.onNodeWithText("Second option").assertExists()
        advance(2_200)
        assertClosed(fixture)
        rule.onNodeWithText("Second option").assertDoesNotExist()
    }

    @Test fun dialogAndSourceHandoffPauseTheDeadlineUntilThePanelIsAvailableAgain() {
        val fixture = mount()
        advance(28_000)
        rule.runOnIdle { fixture.dialog = true }
        advance(32_000)
        rule.onNodeWithText("Unfinished input").assertExists()
        rule.runOnIdle {
            assertEquals(UnifiedPlayerPanel.SETTINGS, fixture.chrome.panels.panel)
            fixture.dialog = false
        }
        advance(28_000)
        rule.runOnIdle { fixture.changing = true }
        advance(32_000)
        rule.runOnIdle {
            assertEquals(UnifiedPlayerPanel.SETTINGS, fixture.chrome.panels.panel)
            fixture.changing = false
        }
        advance(28_000)
        rule.onNodeWithTag("action").assertExists()
        advance(2_200)
        assertClosed(fixture)
    }
}
