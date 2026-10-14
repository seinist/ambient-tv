package com.ambient.tv.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ScrollListEntryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun explicitEntryIntoLazyListReachesItsRequestedChild() {
        val first = FocusRequester()
        rule.setContent {
            TvLazyColumn(Modifier.fillMaxWidth().height(260.dp)) {
                items(6) { index ->
                    TvActionButton("Row $index", {}, Modifier.height(100.dp).testTag("row:$index")
                        .then(if (index == 0) Modifier.focusRequester(first) else Modifier))
                }
            }
            LaunchedEffect(Unit) { first.requestFocus() }
        }
        rule.waitForIdle()
        rule.runOnIdle { org.junit.Assert.assertTrue("Request after layout", first.requestFocus()) }
        rule.onNodeWithTag("row:0").assertIsFocused()
        for (index in 1..5) {
            rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitForIdle()
            rule.onNodeWithTag("row:$index").assertIsFocused()
        }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithTag("row:0").assertIsFocused()
    }
}
