package com.ambient.tv.ui.common

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalTestApi::class)
class TvSettingRowLeadingContentTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun checkIconUsesTheRowsToggle(handheld: Boolean) {
        var checked by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        var changes = 0
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides handheld) {
                AmbientTvTheme {
                    Box(Modifier.width(420.dp)) {
                        TvSettingRow(
                            title = "Movies",
                            checked = checked,
                            onCheckedChange = { checked = it; changes++ },
                            modifier = Modifier.testTag("setting-row"),
                            enabled = enabled,
                            leadingContent = {
                                Box(Modifier.testTag("item-icon")) { SettingsItemIcon(librarySectionIcon("movie")) }
                            },
                        )
                    }
                }
            }
        }
        // On handheld the icon shares the row's tap action. TV Material's Surface accepts
        // D-pad and semantics clicks; its pointer hook focuses the row without activating it.
        rule.onNodeWithTag("item-icon", useUnmergedTree = true).performTouchInput { click() }
        if (!handheld) {
            rule.onNodeWithTag("setting-row").assertIsFocused()
            rule.runOnIdle {
                assertFalse(checked)
                assertEquals(0, changes)
            }
            rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        }
        rule.runOnIdle {
            assertTrue(checked)
            assertEquals(1, changes)
            enabled = false
        }
        // A disabled row must not acquire a second, independently active icon target.
        rule.onNodeWithTag("item-icon", useUnmergedTree = true).performTouchInput { click() }
        if (!handheld) {
            rule.onRoot().performKeyInput { pressKey(Key.Enter) }
            // The TV row remains focusable/readable while disabled; its semantic action
            // still passes through the same enabled gate instead of bypassing it.
            rule.onNodeWithTag("setting-row").performClick()
        }
        rule.runOnIdle {
            assertTrue(checked)
            assertEquals(1, changes)
        }
    }

    @Test fun handheldIconUsesTheSameSingleToggleAction() = checkIconUsesTheRowsToggle(handheld = true)

    @Test fun televisionIconKeepsTheRowsFocusAndSingleKeyToggleAction() = checkIconUsesTheRowsToggle(handheld = false)
}
