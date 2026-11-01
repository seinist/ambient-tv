package com.ambient.tv.ui.common

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Compose turns an unconsumed Back into FocusDirection.Exit, which moves focus onto a focusable
 * ancestor (a drawer, the player's root) and uses the key up. Overlays hand it on instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DispatchBackOnReleaseTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun setContent(backs: AtomicInteger, dispatch: Boolean) {
        rule.setContent {
            BackHandler { backs.incrementAndGet() }
            val row = remember { FocusRequester() }
            // A focusable container, like the drawer column or the player's root.
            Box(Modifier.size(200.dp).then(if (dispatch) Modifier.dispatchBackOnRelease() else Modifier).focusable()) {
                Box(Modifier.size(40.dp).testTag("row").focusRequester(row).focusable())
            }
            LaunchedEffect(Unit) { row.requestFocus() }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("row").assertIsFocused()
    }

    @Test fun backReachesTheBackHandlerAndKeepsTheFocusedRow() {
        val backs = AtomicInteger(0)
        setContent(backs, dispatch = true)
        rule.onRoot().performKeyInput { pressKey(Key.Back) }
        rule.waitForIdle()
        assertEquals(1, backs.get())
        rule.onNodeWithTag("row").assertIsFocused()
    }

    @Test fun withoutItBackOnlyMovesFocusToTheContainer() {
        // Documents the Compose behaviour the modifier exists for.
        val backs = AtomicInteger(0)
        setContent(backs, dispatch = false)
        rule.onRoot().performKeyInput { pressKey(Key.Back) }
        rule.waitForIdle()
        assertEquals(0, backs.get())
    }
}
