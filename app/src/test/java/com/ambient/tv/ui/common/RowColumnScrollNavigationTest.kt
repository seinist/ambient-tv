package com.ambient.tv.ui.common

import android.app.Application
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.mobile.LocalHandheldUi
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A placed neighbouring row can still be hidden by Home's hero until its scroll hook parks it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RowColumnScrollNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Move(val row: String, val release: CompletableDeferred<Unit> = CompletableDeferred())
    private class Fixture {
        val sidebar = FocusRequester()
        var sidebarHasFocus = false
        var contentPermit = false
        val requesters = listOf("a", "b", "c").associateWith { FocusRequester() }
        val requested = mutableListOf<Move>()
        val parked = mutableListOf<String>()
        var hold = true
        suspend fun park(row: String) {
            val move = Move(row).also(requested::add)
            if (!hold) move.release.complete(Unit)
            move.release.await()
            parked += row
        }
    }

    private fun mount(initial: String): Fixture {
        val fixture = Fixture()
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides false) {
                val focus = remember { RowColumnFocus() }
                Row {
                Box(Modifier.size(80.dp).testTag("sidebar").focusRequester(fixture.sidebar)
                    .onFocusChanged { fixture.sidebarHasFocus = it.hasFocus }.focusable())
                // The actual app shell forbids asynchronous content requests from stealing
                // focus from its sidebar unless an explicit shell handoff grants permission.
                Column(Modifier.focusProperties {
                    onEnter = { if (fixture.sidebarHasFocus && !fixture.contentPermit) cancelFocusChange() }
                }.focusGroup().rowColumnFocusNavigation(focus,
                    scrollToTop = { fixture.park("a") },
                    scrollToBottom = { fixture.park("c") },
                    scrollToRow = { fixture.park(it as String) },
                ) { fixture.requesters.keys.toList() }) {
                    fixture.requesters.forEach { (row, requester) ->
                        Box(Modifier.size(160.dp, 80.dp).testTag(row)
                            .focusRequester(requester)
                            .rowColumnFocusTarget(focus, row, row)
                            .focusable())
                    }
                }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            fixture.contentPermit = true
            try { fixture.requesters.getValue(initial).requestFocus() }
            finally { fixture.contentPermit = false }
        }
        rule.waitForIdle()
        return fixture
    }

    private fun press(code: Int) {
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, code, 0))
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_UP, code, 0))
        }
        rule.waitForIdle()
    }

    @Test fun anAlreadyComposedUpperRowIsParkedBeforeItReceivesFocus() {
        val fixture = mount("b")
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        assertEquals(listOf("a"), fixture.requested.map { it.row })
        rule.onNodeWithTag("b").assertIsFocused()
        rule.runOnIdle { fixture.requested.single().release.complete(Unit) }
        rule.waitForIdle()
        rule.onNodeWithTag("a").assertIsFocused()
        assertEquals(listOf("a"), fixture.parked)
    }

    @Test fun rapidReversalsRetargetThePendingRowAndParkTheFinalDestination() {
        val fixture = mount("b")
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        assertEquals(listOf("a", "b", "c", "b"), fixture.requested.map { it.row })
        rule.onNodeWithTag("b").assertIsFocused()
        rule.runOnIdle { fixture.requested.forEach { it.release.complete(Unit) } }
        rule.waitForIdle()
        assertEquals("Cancelled destinations must never finish parking", listOf("b"), fixture.parked)
        rule.onNodeWithTag("b").assertIsFocused()
    }

    @Test fun endpointWrapsUseTheirScrollHooksAndKeepTheExactRowEntry() {
        val fixture = mount("c")
        fixture.hold = false
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("a").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("c").assertIsFocused()
        assertEquals(listOf("a", "c"), fixture.parked)
    }

    @Test fun finishingAnOlderRowMoveCannotStealFocusBackFromTheSidebar() {
        val fixture = mount("b")
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        assertEquals(listOf("a"), fixture.requested.map { it.row })
        rule.runOnIdle {
            fixture.sidebar.requestFocus()
            fixture.requested.single().release.complete(Unit)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("sidebar").assertIsFocused()
        assertEquals(listOf("a"), fixture.parked)
    }
}
