package com.ambient.tv.ui.common

import android.app.Application
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the shared TV alphabet rail with the same outer D-pad acceleration as library pages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AlphabetBarFocusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val alphabet = FocusRequester()
    private val content = FocusRequester()
    private val header = FocusRequester()
    private val selected = mutableStateOf<Char?>('M')
    private var returns = 0

    private fun show() {
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides false) {
                AmbientTvTheme {
                    Column(Modifier.fillMaxSize().acceleratedDpadNavigation()) {
                        Box(Modifier.size(40.dp).focusRequester(header).testTag("header").focusable())
                        Row {
                            Box(Modifier.size(100.dp, 280.dp).focusRequester(content).testTag("content").focusable())
                            AlphabetBar(
                                letters = listOf('A', 'M', 'Z'),
                                selected = selected.value,
                                onSelect = { selected.value = it },
                                gridFocus = content,
                                modifier = Modifier.height(280.dp),
                                onReturnToContent = { returns++; content.requestFocus() },
                                firstEntryFocusRequester = alphabet,
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun enterRail() {
        rule.runOnIdle { assertTrue("Alphabet focus target must be attached", alphabet.requestFocus()) }
        rule.waitForIdle()
    }

    private fun tap(code: Int) {
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, code, 0))
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_UP, code, 0))
        }
        rule.waitForIdle()
    }

    private fun acceleratedRight() {
        // The outer page modifier executes moveFocus() directly on sufficiently large repeats,
        // bypassing the rail's preview-key handler. FocusRequester.Cancel must still block it.
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_RIGHT, 19))
        }
        rule.waitForIdle()
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_RIGHT, 0))
        }
        rule.waitForIdle()
    }

    @Test fun rightDoesNotJumpToHeaderEvenUnderAccelerationAndVerticalBoundariesStayInRail() {
        show()
        enterRail()
        rule.onNodeWithTag("alphabet-letter-M").assertIsFocused()
        tap(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        rule.onNodeWithTag("alphabet-letter-M").assertIsFocused()
        acceleratedRight()
        rule.onNodeWithTag("alphabet-letter-M").assertIsFocused()

        tap(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("alphabet-letter-A").assertIsFocused()
        tap(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("alphabet-letter-A").assertIsFocused()
        tap(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("alphabet-letter-M").assertIsFocused()
        tap(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("alphabet-letter-Z").assertIsFocused()
        tap(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("alphabet-letter-Z").assertIsFocused()

        tap(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        rule.onNodeWithTag("content").assertIsFocused()
        assertEquals(1, returns)
    }

    @Test fun reentryUsesLastRailLetterUntilContentSelectionChanges() {
        show()
        enterRail()
        rule.onNodeWithTag("alphabet-letter-M").assertIsFocused()
        tap(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("alphabet-letter-Z").assertIsFocused()
        tap(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        rule.onNodeWithTag("content").assertIsFocused()

        enterRail()
        rule.onNodeWithTag("alphabet-letter-Z").assertIsFocused()
        tap(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        rule.onNodeWithTag("content").assertIsFocused()

        rule.runOnIdle { selected.value = 'A' }
        rule.waitForIdle()
        enterRail()
        rule.onNodeWithTag("alphabet-letter-A").assertIsFocused()
    }
}
