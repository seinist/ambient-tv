package com.ambient.tv.ui.common

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** D-pad navigation between actual page stages, including dynamically hidden controls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PageFocusTraversalTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val header = FocusRequester()
    private val filterVisible = mutableStateOf(true)

    private fun press(code: Int) {
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, code, 0))
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_UP, code, 0))
        }
        rule.waitForIdle()
    }

    private fun page() {
        rule.setContent {
            AmbientTvTheme {
                val chain = rememberPageFocusChain()
                val search = remember { FocusRequester() }
                val filter = remember { FocusRequester() }
                Column(Modifier.fillMaxSize()) {
                    PageHeader(focusChain = chain, actions = {
                        Box(Modifier.size(64.dp).focusRequester(header).testTag("header").focusable())
                    }) { androidx.tv.material3.Text("Navigation") }
                    Row(Modifier.pageFocusStage(chain, PageFocusLevel.SEARCH_TOP, search)) {
                        Box(Modifier.size(64.dp).testTag("search").focusable())
                    }
                    PageChipRow(chain) {
                        item {
                            Box(Modifier.size(64.dp).focusRequester(chain.selectedChip)
                                .testTag("chips").focusable())
                        }
                    }
                    if (filterVisible.value) {
                        Row(Modifier.pageFocusStage(chain, PageFocusLevel.FILTER, filter)) {
                            Box(Modifier.size(64.dp).testTag("filter").focusable())
                        }
                    }
                    Column(Modifier.pageContentUp(chain).focusGroup()) {
                        Box(Modifier.size(64.dp).testTag("content").focusable())
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { header.requestFocus() }
        rule.waitForIdle()
        rule.onNodeWithTag("header").assertIsFocused()
    }

    @Test fun downAndUpVisitAllPresentStagesInOrder() {
        page()
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("search").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("chips").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("filter").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("content").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("filter").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("chips").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("search").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("header").assertIsFocused()
    }

    @Test fun hiddenFilterStageIsSkippedInBothDirections() {
        filterVisible.value = true
        page()
        rule.runOnIdle { filterVisible.value = false }
        rule.waitForIdle()
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("search").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("chips").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("content").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("chips").assertIsFocused()
    }

    @Test fun rightAtEndOfRegisteredHorizontalRowWrapsWithoutHeader() {
        val last = FocusRequester()
        rule.setContent {
            AmbientTvTheme {
                val rowFocus = rememberRowColumnFocus()
                val state = rememberLazyListState()
                RegisterRowColumnFocus(rowFocus, "row", state, listOf("a", "b", "c"))
                Column(Modifier.rowColumnFocusWrapRight(rowFocus)) {
                    Box(Modifier.size(64.dp).testTag("header").focusable())
                    LazyRow(state = state, modifier = Modifier.containRowFocusExit()) {
                        items(listOf("a", "b", "c")) { key ->
                            Box(Modifier.size(96.dp).rowColumnFocusTarget(rowFocus, "row", key)
                                .then(if (key == "c") Modifier.focusRequester(last) else Modifier)
                                .testTag(key).focusable())
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { last.requestFocus() }
        rule.waitForIdle()
        rule.onNodeWithTag("c").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        rule.onNodeWithTag("a").assertIsFocused()
    }
}
