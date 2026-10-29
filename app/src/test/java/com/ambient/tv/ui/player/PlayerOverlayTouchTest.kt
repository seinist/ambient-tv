package com.ambient.tv.ui.player

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.mobile.LocalHandheldUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayerOverlayTouchTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Fixture {
        val chrome = PlayerChromeState()
        var actionCalls = 0
        var pictureTaps = 0
        var panelCalls = 0
    }

    private fun mount(): Fixture {
        val fixture = Fixture()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(
                LocalPlayerChromeState provides fixture.chrome,
                LocalPlayerPanelNavigation provides fixture.chrome.panels,
                LocalHandheldUi provides true,
            ) {
                Box(Modifier.size(300.dp, 240.dp).testTag("player")) {
                    Box(Modifier.matchParentSize().pointerInput(Unit) {
                        detectTapGestures { fixture.pictureTaps++ }
                    })
                    PlayerOverlayVisibility(fixture.chrome.visible.value) {
                        // Handheld bar actions and clickable header content remain mounted
                        // during the fade. Their normal clickable consumes the Main pass.
                        BasicText("Action", Modifier.size(96.dp).testTag("action")
                            .clickable { fixture.actionCalls++ })
                    }
                    // Same sibling order and visibility wrapper as PersistentPlayerOverlays.
                    PlayerPanelVisibility(fixture.chrome.panels.panel) {
                        BasicText("Panel", Modifier.size(96.dp).testTag("panel")
                            .clickable { fixture.panelCalls++ })
                    }
                }
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

    @Test fun touchOnFadingChildReopensControlsAndConsumesTheWholeGesture() {
        val fixture = mount()
        rule.runOnIdle { fixture.chrome.osd.close() }
        advance(16)
        rule.runOnIdle { assertTrue(fixture.chrome.osd.closing) }

        rule.onNodeWithTag("action").performTouchInput { down(0, center) }
        advance(16) // Recomposition has made the controls active again, with the finger held.
        rule.runOnIdle {
            assertTrue(fixture.chrome.osd.show)
            assertFalse(fixture.chrome.osd.closing)
        }
        rule.onNodeWithTag("action").performTouchInput {
            // A second finger must not start a click on the now-visible child either.
            down(1, center + Offset(10f, 10f))
            up(1)
            up(0)
        }
        advance(300)
        rule.runOnIdle {
            assertEquals(0, fixture.actionCalls)
            assertEquals(0, fixture.pictureTaps)
        }

        // Once that gesture ends, ordinary controls and free-picture taps work again.
        rule.onNodeWithTag("action").performTouchInput { click() }
        rule.onNodeWithTag("player").performTouchInput { click(bottomRight - Offset(8f, 8f)) }
        rule.runOnIdle {
            assertEquals(1, fixture.actionCalls)
            assertEquals(1, fixture.pictureTaps)
        }
    }

    @Test fun panelAboveFadingOsdKeepsItsOwnTouchInput() {
        val fixture = mount()
        val interactionVersion = fixture.chrome.osd.interactionVersion
        rule.runOnIdle { fixture.chrome.panels.open(UnifiedPlayerPanel.AUDIO) }
        advance(16)
        rule.onNodeWithTag("panel").performTouchInput { click() }
        rule.runOnIdle {
            assertEquals(1, fixture.panelCalls)
            assertEquals(0, fixture.actionCalls)
            assertEquals(0, fixture.pictureTaps)
            assertEquals(UnifiedPlayerPanel.AUDIO, fixture.chrome.panels.panel)
            assertEquals(interactionVersion, fixture.chrome.osd.interactionVersion)
        }
    }
}
