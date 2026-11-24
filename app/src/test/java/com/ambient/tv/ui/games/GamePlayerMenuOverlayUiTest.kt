package com.ambient.tv.ui.games

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.ambient.tv.games.GameTouchMode
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.player.LocalPlayerChromeState
import com.ambient.tv.ui.player.LocalPlayerControlFocus
import com.ambient.tv.ui.player.PlayerControlsAutoHide
import com.ambient.tv.ui.player.ReferencePlayerBottomContent
import com.ambient.tv.ui.player.ReferencePlayerOverlayBackground
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.util.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual shared overlay and game touch layers, without starting a native libretro core. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h720dp-port-mdpi")
class GamePlayerMenuOverlayUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Fixture {
        val chrome = createGamePlayerChrome()
        var menuOpen by mutableStateOf(false)
        val aTransitions = mutableListOf<Boolean>()

        fun openMenu() { menuOpen = true; chrome.osd.expand() }
        fun resume() { menuOpen = false; chrome.osd.hideImmediately() }
        fun button(button: RetroPadButton, down: Boolean) {
            if (button == RetroPadButton.A) aTransitions += down
        }
    }

    private fun mount(handheld: Boolean = true): Fixture {
        val fixture = Fixture()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(
                LocalHandheldUi provides handheld,
                LocalPlayerChromeState provides fixture.chrome,
                LocalPlayerControlFocus provides fixture.chrome.focus,
                LocalPlayerPanelNavigation provides fixture.chrome.panels,
            ) {
                AmbientTvTheme {
                    BackHandler {
                        if (fixture.menuOpen) fixture.resume() else fixture.openMenu()
                    }
                    PlayerControlsAutoHide(fixture.chrome.visible.value, playing = false, panelOpen = false) {
                        fixture.chrome.visible.value = it
                    }
                    Box(Modifier.fillMaxSize()) {
                        GamePlayerTouchLayout(
                            handheld = handheld, directPointer = false, touchEnabled = true,
                            touchMode = GameTouchMode.DPAD, playing = !fixture.menuOpen,
                            menuVisible = fixture.menuOpen, onMenu = fixture::openMenu,
                            onButton = fixture::button, onStick = { _, _, _ -> },
                            surface = { Box(it) },
                        )
                        GamePlayerMenuOverlay(fixture.menuOpen, panelOpen = false) {
                            ReferencePlayerOverlayBackground()
                            ReferencePlayerBottomContent(onTogglePlayback = fixture::resume) {
                                BasicText("Game menu", Modifier.testTag("game-menu"))
                                TvActionButton("Continue", fixture::resume, modifier = Modifier.testTag("continue"))
                            }
                        }
                    }
                }
            }
        }
        advance(400)
        rule.runOnIdle { fixture.aTransitions.clear() }
        return fixture
    }

    private fun advance(milliseconds: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(milliseconds)
        rule.waitForIdle()
    }

    private fun tapA(fixture: Fixture) {
        rule.runOnIdle { fixture.aTransitions.clear() }
        rule.onNodeWithTag("game-touch-a", useUnmergedTree = true).assertIsDisplayed().performTouchInput { click() }
        rule.runOnIdle { assertEquals(listOf(true, false), fixture.aTransitions) }
    }

    @Test fun startupAndContinueLeaveTheTouchPadUsableAndTheGameMenuStillOpens() {
        val fixture = mount()
        rule.runOnIdle { assertFalse(fixture.chrome.visible.value) }
        rule.onNodeWithTag("game-menu").assertDoesNotExist()
        tapA(fixture)

        rule.onNodeWithTag("game-player-menu", useUnmergedTree = true).performTouchInput { click() }
        advance(6_000) // A deliberately paused game menu must not auto-hide.
        rule.onNodeWithTag("game-menu").assertIsDisplayed()
        rule.onNodeWithTag("game-touch-a", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("continue").performTouchInput { click() }
        advance(16) // No media stow/fade timeout may block the newly active pad.
        rule.onNodeWithTag("game-menu").assertDoesNotExist()
        tapA(fixture)
    }

    @Test fun staleCompactOrClosingMediaChromeCannotCoverGameplay() {
        val fixture = mount()
        rule.runOnIdle { fixture.chrome.osd.reveal() }
        advance(16)
        rule.onNodeWithTag("game-menu").assertDoesNotExist()
        tapA(fixture)

        rule.runOnIdle { fixture.openMenu() }
        advance(400)
        rule.onNodeWithTag("game-menu").assertIsDisplayed()
        rule.runOnIdle {
            fixture.chrome.osd.close()
            fixture.menuOpen = false
        }
        advance(16)
        rule.runOnIdle { assertTrue(fixture.chrome.osd.closing) }
        rule.onNodeWithTag("game-menu").assertDoesNotExist()
        tapA(fixture)
    }

    @Test
    @Config(qualifiers = "w1080dp-h720dp-land-mdpi")
    fun televisionStartsCleanAndBackStillOpensAndClosesTheGameMenu() {
        val fixture = mount(handheld = false)
        rule.onNodeWithTag("game-menu").assertDoesNotExist()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        advance(400)
        rule.onNodeWithTag("game-menu").assertIsDisplayed()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        advance(16)
        rule.onNodeWithTag("game-menu").assertDoesNotExist()
        rule.runOnIdle { assertFalse(fixture.menuOpen); assertFalse(fixture.chrome.visible.value) }
    }
}
