package com.ambient.tv.ui.common.projectm

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProjectMPresetNameOverlayTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun advance(milliseconds: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(milliseconds)
        rule.waitForIdle()
    }

    @Test fun newPresetGetsItsOwnTimeoutAndControlsDoNotRestartIt() {
        val preset = mutableStateOf(ProjectMPreset("classic/First.milk"))
        val controls = mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme {
                ProjectMPresetNameOverlay(
                    preset.value, true,
                    Modifier.padding(top = if (controls.value) 80.dp else 24.dp),
                )
            }
        }
        advance(1_500)
        rule.onNodeWithText("First").assertIsDisplayed()
        rule.runOnIdle { preset.value = ProjectMPreset("classic/Second.milk") }
        advance(800)
        rule.onNodeWithText("First").assertDoesNotExist()
        rule.onNodeWithText("Second").assertIsDisplayed()
        rule.runOnIdle { controls.value = true }
        advance(800)
        rule.onNodeWithText("Second").assertIsDisplayed()
        advance(800)
        rule.onNodeWithText("Second").assertDoesNotExist()
        rule.runOnIdle { preset.value = ProjectMPreset("classic/Third.milk") }
        advance(250)
        rule.onNodeWithText("Third").assertIsDisplayed()
    }

    @Test fun disabledNamesStayHiddenAcrossChangesAndCanBeEnabledAgain() {
        val enabled = mutableStateOf(false)
        val preset = mutableStateOf<ProjectMPreset?>(null)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme { ProjectMPresetNameOverlay(preset.value, enabled.value) }
        }
        rule.runOnIdle { preset.value = ProjectMPreset("classic/First.milk") }
        advance(250)
        rule.onNodeWithText("First").assertDoesNotExist()
        rule.runOnIdle { enabled.value = true }
        advance(250)
        rule.onNodeWithText("First").assertIsDisplayed()
        rule.runOnIdle { enabled.value = false }
        advance(32)
        rule.onNodeWithText("First").assertDoesNotExist()
        rule.runOnIdle { preset.value = ProjectMPreset("classic/Second.milk") }
        advance(250)
        rule.onNodeWithText("Second").assertDoesNotExist()
        rule.runOnIdle { enabled.value = true }
        advance(250)
        rule.onNodeWithText("Second").assertIsDisplayed()
        advance(2_200)
        rule.onNodeWithText("Second").assertDoesNotExist()
    }
}
