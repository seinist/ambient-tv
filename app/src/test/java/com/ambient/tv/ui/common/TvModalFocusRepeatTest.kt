package com.ambient.tv.ui.common

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings area list: wrap on a fresh press, stop at the ends while a key is held. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TvModalFocusRepeatTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun setUp(wrapOnRepeat: Boolean, start: Int) {
        val requesters = List(5) { FocusRequester() }
        rule.setContent {
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxWidth().wrapVerticalScrollFocus(scroll, wrapOnRepeat = wrapOnRepeat).verticalScroll(scroll)) {
                repeat(5) { index ->
                    TvActionButton("Area $index", {}, Modifier.height(60.dp).testTag("area:$index").focusRequester(requesters[index]))
                }
            }
            LaunchedEffect(Unit) { requesters[start].requestFocus() }
        }
        rule.waitForIdle()
        rule.runOnIdle { requesters[start].requestFocus() }
        rule.waitForIdle()
        rule.onNodeWithTag("area:$start").assertIsFocused()
    }

    private fun press(code: Int, repeat: Int = 0) {
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, code, repeat))
            if (repeat == 0) rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_UP, code, 0))
        }
        rule.waitForIdle()
    }

    @Test fun nonWrappingDetailListCanReachUtilityActionsWithUp() {
        val content = FocusRequester()
        val utility = FocusRequester()
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalScrollListActionFocus provides utility) {
                Column {
                    TvActionButton("Back", {}, Modifier.testTag("utility").focusRequester(utility))
                    Column(Modifier.tvModalFocus(initialFocus = false, allowUpExit = true, wrapVerticalFocus = false)) {
                        TvActionButton("Play", {}, Modifier.testTag("play").focusRequester(content))
                    }
                }
            }
            LaunchedEffect(Unit) { content.requestFocus() }
        }
        rule.waitForIdle()
        rule.runOnIdle { content.requestFocus() }
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("play").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("utility").assertIsFocused()
    }

    @Test fun freshDownOnTheLastAreaWrapsToTheFirst() {
        setUp(wrapOnRepeat = false, start = 4)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("area:0").assertIsFocused()
    }

    @Test fun freshUpOnTheFirstAreaWrapsToTheLast() {
        setUp(wrapOnRepeat = false, start = 0)
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("area:4").assertIsFocused()
    }

    @Test fun heldKeysStopAtTheEnds() {
        setUp(wrapOnRepeat = false, start = 4)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN, repeat = 3)
        rule.onNodeWithTag("area:4").assertIsFocused()
        setUpAgainAtTop()
    }

    private fun setUpAgainAtTop() {
        // Held Up from the last row walks up normally and then stops at the first row.
        repeat(6) { press(AndroidKeyEvent.KEYCODE_DPAD_UP, repeat = it + 1) }
        rule.onNodeWithTag("area:0").assertIsFocused()
    }

    @Test fun heldMovesBetweenRowsStillWork() {
        setUp(wrapOnRepeat = false, start = 0)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN, repeat = 1)
        rule.onNodeWithTag("area:1").assertIsFocused()
    }

    @Test fun controlOtherListsStillWrapWhileHeld() {
        setUp(wrapOnRepeat = true, start = 4)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN, repeat = 3)
        rule.onNodeWithTag("area:0").assertIsFocused()
    }
}
