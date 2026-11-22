package com.ambient.tv.ui.tvheadend

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.ambient.tv.R
import com.ambient.tv.data.local.ClockSettings
import com.ambient.tv.livetv.LiveTvTimerEdit
import com.ambient.tv.ui.common.LocalClockSettings
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real dialog/remote input: recovery must never turn a cached draft or a second OK into a write. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class LiveTvTimerOfflineDialogTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun anOpenEditorKeepsItsChangedDraftAndRetryNeverSavesIt() {
        val connectionRequired = mutableStateOf(false)
        val retrying = mutableStateOf(false)
        val writes = mutableListOf<LiveTvTimerEdit>()
        var retries = 0
        var backs = 0
        rule.setContent {
            CompositionLocalProvider(
                LocalHandheldUi provides false,
                LocalClockSettings provides ClockSettings(display = ClockSettings.DISPLAY_OFF),
            ) {
                AmbientTvTheme {
                    LiveTvTimerEditDialog(
                        title = "Evening programme", series = false,
                        paddingBefore = 5, paddingAfter = null, priority = null, enabled = null,
                        zeroIsServerDefault = true, busy = false, message = null,
                        connectionRequired = connectionRequired.value, retrying = retrying.value,
                        onRetry = { retries++; retrying.value = true },
                        onSave = { writes += it }, onDismiss = { backs++ },
                    )
                }
            }
        }
        settleDialogFocus()

        // Change the real editor before the connection fails; no test-only state injection.
        action("−").assertIsFocused()
        press(Key.DirectionRight)
        action("+").assertIsFocused()
        press(Key.DirectionCenter)
        val changedPadding = rule.activity.getString(R.string.tvh_padding_minutes, 10)
        rule.onNodeWithText(changedPadding).assertIsDisplayed()
        rule.runOnIdle { assertEquals(emptyList<LiveTvTimerEdit>(), writes) }

        rule.runOnIdle { connectionRequired.value = true }
        settleDialogFocus()
        action(R.string.tvh_save).assertDoesNotExist()
        action("−").assertIsNotEnabled()
        action("+").assertIsNotEnabled()
        action(R.string.home_retry).assertIsFocused()
        rule.onNodeWithText(changedPadding).assertIsDisplayed()
        press(Key.DirectionCenter)
        // A repeated OK while the read is pending must not retry again or submit the draft.
        action(R.string.home_retry).assertIsFocused()
        press(Key.DirectionCenter)
        rule.runOnIdle {
            assertEquals(1, retries)
            assertEquals(emptyList<LiveTvTimerEdit>(), writes)
            assertEquals(0, backs)
        }

        rule.runOnIdle { retrying.value = false; connectionRequired.value = false }
        settleDialogFocus()
        rule.onNodeWithText(changedPadding).assertIsDisplayed()
        action(R.string.home_retry).assertDoesNotExist()
        action("+").assertIsEnabled()
        action(R.string.tvh_save).assertIsFocused()
        rule.runOnIdle { assertEquals(emptyList<LiveTvTimerEdit>(), writes) }

        // Only a new, explicit remote activation submits the preserved value.
        press(Key.DirectionCenter)
        rule.runOnIdle {
            assertEquals(listOf(LiveTvTimerEdit(paddingBeforeMinutes = 10)), writes)
            assertEquals(1, retries)
        }
    }

    @Test fun aRecoveredDeleteConfirmationFocusesBackSoTheNextOkCannotDelete() {
        val connectionRequired = mutableStateOf(false)
        val retrying = mutableStateOf(false)
        var deletes = 0
        var retries = 0
        var backs = 0
        val delete = rule.activity.getString(R.string.tvh_delete_recording)
        rule.setContent {
            CompositionLocalProvider(
                LocalHandheldUi provides false,
                LocalClockSettings provides ClockSettings(display = ClockSettings.DISPLAY_OFF),
            ) {
                AmbientTvTheme {
                    LiveTvConfirmDialog(
                        title = "Evening programme", question = "Delete this recording?", confirmLabel = delete,
                        busy = false, connectionRequired = connectionRequired.value, retrying = retrying.value,
                        onRetry = { retries++; retrying.value = true },
                        onConfirm = { deletes++ }, onDismiss = { backs++ },
                    )
                }
            }
        }
        settleDialogFocus()
        action(R.string.back).assertIsFocused()

        rule.runOnIdle { connectionRequired.value = true }
        settleDialogFocus()
        action(delete).assertDoesNotExist()
        action(R.string.home_retry).assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionCenter)
        rule.runOnIdle {
            assertEquals(1, retries)
            assertEquals(0, deletes)
            assertEquals(0, backs)
        }

        rule.runOnIdle { retrying.value = false; connectionRequired.value = false }
        settleDialogFocus()
        action(R.string.home_retry).assertDoesNotExist()
        action(delete).assertIsDisplayed()
        action(R.string.back).assertIsFocused()
        // The second OK after recovery is Back, never the reappearing destructive action.
        press(Key.DirectionCenter)
        rule.runOnIdle {
            assertEquals(1, backs)
            assertEquals(0, deletes)
            assertEquals(1, retries)
        }
    }

    private fun action(label: String) = rule.onNode(hasText(label) and hasClickAction())
    private fun action(label: Int) = action(rule.activity.getString(label))

    private fun press(key: Key) {
        // performClick injects touch in Compose 1.12; TV Surface activates through D-pad.
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun settleDialogFocus() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(120)
        rule.waitForIdle()
    }
}
