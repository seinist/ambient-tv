package com.ambient.tv.ui.common

import android.app.Application
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.util.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HoldActionUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Fixture {
        var visible by mutableStateOf(true)
        var parentClicks = 0
        val focus = FocusRequester()
        val changes = mutableListOf<Boolean>()
    }

    private fun mount(): Fixture {
        val fixture = Fixture()
        rule.setContent {
            AmbientTvTheme {
                Box(Modifier.fillMaxSize().testTag("hold-root").clickable { fixture.parentClicks++ }) {
                    if (fixture.visible) PlayerIconButton(Icons.Filled.FastRewind, "Rewind", onClick = {},
                        modifier = Modifier.testTag("hold-button").focusRequester(fixture.focus),
                        onHold = { fixture.changes += it })
                }
            }
        }
        rule.waitForIdle()
        return fixture
    }

    @Test fun pointerHoldAndReleaseNeverActivateAncestorOsd() {
        val fixture = mount()
        val root = rule.onNodeWithTag("hold-root")
        val point = rule.onNodeWithTag("hold-button").fetchSemanticsNode().boundsInRoot.center
        root.performTouchInput { down(point) }
        rule.runOnIdle { assertEquals(listOf(true), fixture.changes) }
        root.performTouchInput { up() }
        rule.runOnIdle {
            assertEquals(listOf(true, false), fixture.changes)
            assertEquals(0, fixture.parentClicks)
        }
    }

    @Test fun slidingOutsideCancelsWithoutLatchingOrOpeningOsd() {
        val fixture = mount()
        val root = rule.onNodeWithTag("hold-root")
        val bounds = rule.onNodeWithTag("hold-button").fetchSemanticsNode().boundsInRoot
        root.performTouchInput { down(bounds.center); moveTo(Offset(bounds.right + 30, bounds.center.y)) }
        rule.runOnIdle { assertEquals(listOf(true, false), fixture.changes) }
        root.performTouchInput { up() }
        rule.runOnIdle { assertEquals(0, fixture.parentClicks) }
    }

    @Test fun unavailableControlDisposalReleasesAnActiveHold() {
        val fixture = mount()
        val root = rule.onNodeWithTag("hold-root")
        val point = rule.onNodeWithTag("hold-button").fetchSemanticsNode().boundsInRoot.center
        root.performTouchInput { down(point) }
        rule.runOnIdle { fixture.visible = false }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(listOf(true, false), fixture.changes) }
        root.performTouchInput { up() }
    }

    @Test fun aRepeatArrivingAfterFocusMovedCannotStartARewindHold() {
        val fixture = mount()
        rule.runOnIdle {
            fixture.focus.requestFocus()
            rule.activity.dispatchKeyEvent(KeyEvent(1L, 2L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 2))
            rule.activity.dispatchKeyEvent(KeyEvent(1L, 3L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            assertTrue(fixture.changes.isEmpty())
        }
    }

    @Test fun releasingEnterDoesNotReleaseAnIndependentSpaceHold() {
        val fixture = mount()
        rule.runOnIdle {
            fixture.focus.requestFocus()
            rule.activity.dispatchKeyEvent(KeyEvent(1L, 1L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
            rule.activity.dispatchKeyEvent(KeyEvent(2L, 2L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 0))
            rule.activity.dispatchKeyEvent(KeyEvent(1L, 3L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
            assertEquals(listOf(true), fixture.changes)
            rule.activity.dispatchKeyEvent(KeyEvent(2L, 4L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE, 0))
            assertEquals(listOf(true, false), fixture.changes)
        }
    }
}
