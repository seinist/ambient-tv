package com.ambient.tv.ui.common

import android.app.Application
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.player.LocalPlayerPanelMotion
import com.ambient.tv.ui.player.PlayerPanelMotionState
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MilkDrop's two-control rows must remain navigable while lazy items enter and leave the viewport. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class RightSideLazyPanelFocusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var focusedRow: String? = null
    private var selectedPreset: Int? = null
    private var favoriteChanges = 0

    private fun mount(count: Int = 1_000) {
        val first = FocusRequester()
        val navigation = PlayerPanelNavigation().apply { open(UnifiedPlayerPanel.VISUALIZER) }
        val indices = (0 until count).toList()
        rule.setContent {
            CompositionLocalProvider(
                LocalHandheldUi provides false,
                LocalPlayerPanelNavigation provides navigation,
                LocalPlayerPanelMotion provides PlayerPanelMotionState(active = true),
            ) {
                AmbientTvTheme {
                    RightSideLazyPanel(
                        title = "Visualisierung",
                        subtitle = "projectM · MilkDrop",
                        onBack = {},
                        requestHeaderFocus = false,
                    ) {
                        items(indices, key = { "classic/preset-$it.milk" }) { index ->
                            // Resolve the panel target inside its content, as a real preset row does.
                            val scrollbar = LocalRightPanelScrollbarFocus.current
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RightPanelChoice(
                                    text = "MilkDrop $index",
                                    subtitle = "Classic",
                                    selected = false,
                                    onClick = { selectedPreset = index },
                                    modifier = Modifier.weight(1f)
                                        .testTag("preset:$index")
                                        .playerPanelFocusKey("projectm:preset:classic/preset-$index.milk")
                                        .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                        .onFocusChanged { if (it.isFocused) focusedRow = "preset:$index" },
                                )
                                PlayerIconButton(
                                    icon = Icons.Filled.FavoriteBorder,
                                    // The real list shares this label across every unfavorited preset.
                                    label = "Favorit",
                                    selected = false,
                                    onClick = { favoriteChanges++ },
                                    modifier = Modifier
                                        .focusProperties { right = scrollbar ?: FocusRequester.Default }
                                        .rightPanelScrollbarReturn()
                                        .testTag("favorite:$index")
                                        .playerPanelFocusKey("projectm:favorite:classic/preset-$index.milk")
                                        .onFocusChanged { if (it.isFocused) focusedRow = "favorite:$index" },
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { first.requestFocus() }
        rule.waitForIdle()
        focused("preset:0")
    }

    private fun press(code: Int, repeatCount: Int = 0) {
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, code, repeatCount))
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_UP, code, 0))
        }
        rule.waitForIdle()
    }

    private fun focused(tag: String) = rule.onNodeWithTag(tag).assertIsFocused().assertIsDisplayed()

    private fun scrollbar() = rule.onNodeWithContentDescription(rule.activity.getString(R.string.common_scrollbar))

    @Test fun upAndDownKeepEachColumnAcrossManyLazyViewports() {
        mount()
        for (index in 1..24) {
            press(AndroidKeyEvent.KEYCODE_DPAD_DOWN, repeatCount = index)
            focused("preset:$index")
        }
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        focused("favorite:24")
        for (index in 23 downTo 0) {
            press(AndroidKeyEvent.KEYCODE_DPAD_UP, repeatCount = 24 - index)
            focused("favorite:$index")
        }
        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        focused("preset:0")
        assertEquals(null, selectedPreset)
        assertEquals(0, favoriteChanges)
    }

    @Test fun rapidRepeatedKeysDuringLazyRecompositionKeepAVisibleRowFocused() {
        mount()
        repeat(20) { press(AndroidKeyEvent.KEYCODE_DPAD_DOWN) }
        rule.runOnUiThread {
            repeat(16) { repeat ->
                rule.activity.dispatchKeyEvent(AndroidKeyEvent(
                    0L, 0L, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_DOWN, repeat + 1,
                ))
            }
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN))
        }
        rule.waitForIdle()
        val row = checkNotNull(focusedRow)
        assertTrue("Held Down left the preset column: $row", row.startsWith("preset:"))
        assertTrue("Held Down jumped backwards: $row", row.substringAfter(':').toInt() > 20)
        focused(row)
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        focused("preset:${row.substringAfter(':').toInt() - 1}")
    }

    @Test fun wrappingBothEndsRepeatedlyReleasesOldLazyFocusBeforeScrolling() {
        mount()
        repeat(3) {
            press(AndroidKeyEvent.KEYCODE_DPAD_UP)
            focused("favorite:999")
            press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
            focused("preset:0")
        }
        assertEquals(null, selectedPreset)
        assertEquals(0, favoriteChanges)
    }

    @Test fun rightReachesOneScrollbarThenFooterAndPagingReturnsToVisibleContent() {
        mount()
        rule.onAllNodesWithContentDescription(rule.activity.getString(R.string.common_scrollbar)).assertCountEquals(1)
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        focused("favorite:0")
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        scrollbar().assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        rule.onNode(hasText(rule.activity.getString(R.string.back)) and hasClickAction()).assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        scrollbar().assertIsFocused()

        repeat(3) {
            press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
            scrollbar().assertIsFocused()
        }
        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        val row = checkNotNull(focusedRow)
        assertTrue("Scrollbar returned to an old offscreen row: $row", row.substringAfter(':').toInt() > 0)
        focused(row)
    }
}
