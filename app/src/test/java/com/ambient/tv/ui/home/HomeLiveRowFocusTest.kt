package com.ambient.tv.ui.home

import android.app.Application
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.ui.common.RowColumnFocus
import com.ambient.tv.ui.common.rowColumnFocusNavigation
import com.ambient.tv.ui.common.rowColumnFocusTarget
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A live row must become the visible Home row as soon as its leading shortcut takes focus. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class HomeLiveRowFocusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun checkShortcutEntry(recent: Boolean) {
        val rowKey = if (recent) "recent-tv" else "live-tv"
        val shortcut = rule.activity.getString(if (recent) R.string.home_live_channels else R.string.tvh_guide)
        val previous = FocusRequester()
        var reportedRow: String? = null
        var focusedChannel: String? = null
        val channels = listOf(HomeLiveTvItem(
            channel = LiveTvChannel("one", "Test Channel", 1, null, sourceId = "tvheadend:test"),
            event = null,
        ))
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides false) {
                AmbientTvTheme {
                    val focus = remember { RowColumnFocus() }
                    var activeRow by remember { mutableStateOf(0) }
                    Column(Modifier.rowColumnFocusNavigation(focus) { listOf("before", rowKey) }) {
                        Box(Modifier.size(160.dp, 80.dp)
                            .testTag("previous-row")
                            .focusRequester(previous)
                            .rowColumnFocusTarget(focus, "before", "before")
                            .onFocusChanged { if (it.isFocused) activeRow = 0 }
                            .focusable())
                        Box(Modifier.onlyActiveHeroRow(index = 1, activeIndex = activeRow)) {
                            HomeLiveTvRow(
                                items = channels,
                                title = "TV row",
                                imageHeaders = emptyMap(),
                                onWatch = {},
                                requestInitialFocus = false,
                                onItemFocused = { focusedChannel = it.channel.uuid },
                                onRowFocused = {
                                    activeRow = 1
                                    reportedRow = rowKey
                                },
                                bringContextIntoView = false,
                                columnFocus = focus,
                                rowKey = rowKey,
                                onOpenChannels = if (recent) ({}) else null,
                                onOpenGuide = if (recent) null else ({}),
                                entry = remember { LiveRowEntry() },
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { previous.requestFocus() }
        rule.waitForIdle()
        rule.onNodeWithTag("previous-row").assertIsFocused()

        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithText(shortcut).assertIsFocused().assertIsDisplayed()
        rule.runOnIdle {
            assertEquals("The leading shortcut must activate the live row", rowKey, reportedRow)
            assertEquals("No channel should have taken focus yet", null, focusedChannel)
        }

        // Leaving and re-entering the row must report focus again even though its anchor
        // remains the guide/channels tile; no sideways move is needed to reveal it.
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("previous-row").assertIsFocused()
        rule.runOnIdle { reportedRow = null }
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithText(shortcut).assertIsFocused().assertIsDisplayed()
        rule.runOnIdle { assertEquals(rowKey, reportedRow) }
    }

    private fun press(code: Int) {
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, code, 0))
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_UP, code, 0))
        }
        rule.waitForIdle()
    }

    @Test fun guideTileRevealsTheLiveRowWithoutFocusingAChannel() = checkShortcutEntry(recent = false)

    @Test fun channelsTileRevealsTheRecentRowWithoutFocusingAChannel() = checkShortcutEntry(recent = true)
}
