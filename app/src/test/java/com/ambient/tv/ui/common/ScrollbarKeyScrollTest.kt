package com.ambient.tv.ui.common

import android.app.Application
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drive the real scrollbar with separate press/release events and an explicit frame clock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ScrollbarKeyScrollTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var state: LazyListState
    private val scrollbar = FocusRequester()
    private val outside = FocusRequester()

    private fun showList(reverseLayout: Boolean = false, initialIndex: Int = if (reverseLayout) 20 else 0) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AmbientTvTheme {
                Column(Modifier.acceleratedDpadNavigation()) {
                    Box(Modifier.size(32.dp).focusRequester(outside).testTag("outside").focusable())
                    state = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
                    Box(Modifier.fillMaxWidth().height(240.dp)) {
                        LazyColumn(state = state, reverseLayout = reverseLayout, modifier = Modifier.fillMaxWidth()) {
                            items(200) { Box(Modifier.fillMaxWidth().height(40.dp)) }
                        }
                        OverlayLazyScrollbar(
                            state,
                            Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                                .focusRequester(scrollbar).testTag("scrollbar"),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.runOnIdle { assertTrue("Scrollbar attaches after layout", scrollbar.requestFocus()) }
        rule.waitForIdle()
        rule.onNodeWithTag("scrollbar").assertIsFocused()
    }

    private fun key(action: Int, code: Int, repeatCount: Int = 0) {
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, action, code, repeatCount))
        }
        rule.waitForIdle()
    }

    private fun tap(code: Int) {
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, code, 0))
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_UP, code, 0))
        }
        rule.waitForIdle()
    }

    private fun advance(milliseconds: Long) {
        rule.mainClock.advanceTimeBy(milliseconds)
        rule.waitForIdle()
    }

    private fun offset(): Int = rule.runOnIdle {
        val itemHeight = state.layoutInfo.visibleItemsInfo.first().size
        state.firstVisibleItemIndex * itemHeight + state.firstVisibleItemScrollOffset
    }

    private fun viewport(): Int = rule.runOnIdle {
        state.layoutInfo.viewportEndOffset - state.layoutInfo.viewportStartOffset
    }

    @Test fun quickDpadTapMovesASmallSectionWhilePageKeysMoveAViewport() {
        showList()
        tap(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        advance(600)
        val tapped = offset()
        assertTrue("A quick tap must move even when released before the next frame", tapped > 0)
        assertTrue("D-pad must not jump an entire page", tapped < viewport() / 2)

        tap(AndroidKeyEvent.KEYCODE_PAGE_DOWN)
        advance(1_200)
        assertEquals("Page Down retains a full viewport step", viewport().toFloat(), (offset() - tapped).toFloat(), 2f)
        tap(AndroidKeyEvent.KEYCODE_PAGE_UP)
        advance(1_200)
        assertEquals("Page Up returns the same distance", tapped.toFloat(), offset().toFloat(), 2f)
        rule.onNodeWithTag("scrollbar").assertIsFocused()
    }

    @Test fun holdingScrollSurvivesAcceleratedRepeatsAndStopsOnRelease() {
        showList(initialIndex = 30)
        key(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_UP)
        advance(1_200)
        val first = offset()
        advance(500)
        val second = offset()
        assertTrue("Holding continues without native repeat events", second < first)

        // The page-level accelerator must not move focus to the button above the scrollbar.
        key(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_UP, repeatCount = 15)
        advance(500)
        val third = offset()
        assertTrue("An accelerated repeat must not interrupt continuous scrolling", third < second)
        rule.onNodeWithTag("scrollbar").assertIsFocused()

        key(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_UP)
        val released = offset()
        advance(800)
        assertEquals("No animation backlog after releasing a held key", released, offset())
        rule.onNodeWithTag("scrollbar").assertIsFocused()
    }

    @Test fun losingFocusStopsHeldScrollingWithoutWaitingForKeyUp() {
        showList()
        key(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        advance(800)
        assertTrue(offset() > 0)
        rule.runOnIdle { assertTrue(outside.requestFocus()) }
        rule.waitForIdle()
        rule.onNodeWithTag("outside").assertIsFocused()
        val stopped = offset()
        advance(800)
        assertEquals("Focus loss must cancel the ongoing scroll", stopped, offset())
        key(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN)
    }

    @Test fun reverseLayoutKeepsTheVisualUpAndDownDirections() {
        showList(reverseLayout = true)
        val initial = offset()
        tap(AndroidKeyEvent.KEYCODE_DPAD_UP)
        advance(600)
        val afterUp = offset()
        assertTrue("Visual Up advances the reversed list", afterUp > initial)
        tap(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        advance(600)
        assertTrue("Visual Down moves the reversed list back", offset() < afterUp)
        rule.onNodeWithTag("scrollbar").assertIsFocused()
    }
}
