package com.ambient.tv.ui.player

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.common.PlayerOsdTimeout
import com.ambient.tv.ui.common.PlayerPanelNavigation
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayerControlsAutoHideTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val previousTimeout = PlayerOsdTimeout.seconds

    @After fun restoreTimeout() { PlayerOsdTimeout.seconds = previousTimeout }

    private fun advance(milliseconds: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(milliseconds)
        rule.waitForIdle()
    }

    @Test fun nestedPanelsHideOsdWithoutLosingItsReturnStateAndRestartDeadlineOnClose() {
        PlayerOsdTimeout.seconds = 5
        val controls = mutableStateOf(true)
        val navigation = PlayerPanelNavigation()
        val focus = PlayerControlFocus()
        val owner = Any()
        focus.register("settings", FocusRequester(), owner)
        focus.focused("settings", owner, true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalPlayerPanelNavigation provides navigation, LocalPlayerControlFocus provides focus) {
                PlayerControlsAutoHide(controls.value, true, navigation.panel != UnifiedPlayerPanel.NONE) {
                    controls.value = it
                }
                PlayerOverlayVisibility(controls.value) { BasicText("OSD") }
            }
        }
        advance(4_000)
        rule.onNodeWithText("OSD").assertIsDisplayed()
        rule.runOnIdle { navigation.open(UnifiedPlayerPanel.AUDIO) }
        advance(10_000)
        rule.onNodeWithText("OSD").assertDoesNotExist()
        rule.runOnIdle {
            assertTrue(controls.value)
            assertEquals("settings", focus.lastKey)
            navigation.open(UnifiedPlayerPanel.VIDEO)
        }
        advance(300)
        rule.runOnIdle { navigation.back() }
        advance(6_000)
        rule.onNodeWithText("OSD").assertDoesNotExist()
        rule.runOnIdle { navigation.close() }
        advance(4_000)
        rule.onNodeWithText("OSD").assertIsDisplayed()
        // Dismissal stows the button bar first (380 ms), then fades out (220 ms).
        advance(2_000)
        rule.onNodeWithText("OSD").assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(false, controls.value)
            assertNull(focus.lastKey)
        }
    }

    @Test fun panelOpenedFromHiddenControlsStillReturnsToOsd() {
        PlayerOsdTimeout.seconds = 5
        val controls = mutableStateOf(false)
        val navigation = PlayerPanelNavigation()
        navigation.open(UnifiedPlayerPanel.AUDIO)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalPlayerPanelNavigation provides navigation) {
                PlayerControlsAutoHide(controls.value, true, navigation.panel != UnifiedPlayerPanel.NONE) {
                    controls.value = it
                }
                PlayerOverlayVisibility(controls.value) { BasicText("OSD") }
            }
        }
        advance(8_000)
        rule.onNodeWithText("OSD").assertDoesNotExist()
        rule.runOnIdle { navigation.close() }
        advance(300)
        rule.onNodeWithText("OSD").assertIsDisplayed()
    }

    @Test fun pauseAndFurtherInputEachCancelThePreviousDeadline() {
        PlayerOsdTimeout.seconds = 5
        val controls = mutableStateOf(true)
        val playing = mutableStateOf(true)
        val input = mutableIntStateOf(0)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PlayerControlsAutoHide(controls.value, playing.value, false, interactionVersion = input.intValue) {
                controls.value = it
            }
            PlayerOverlayVisibility(controls.value) { BasicText("OSD") }
        }
        advance(4_000)
        rule.runOnIdle { playing.value = false }
        advance(10_000)
        rule.onNodeWithText("OSD").assertIsDisplayed()
        rule.runOnIdle { playing.value = true }
        advance(4_000)
        rule.runOnIdle { input.intValue++ }
        advance(4_000)
        rule.onNodeWithText("OSD").assertIsDisplayed()
        // Dismissal stows the button bar first (380 ms), then fades out (220 ms).
        advance(2_000)
        rule.onNodeWithText("OSD").assertDoesNotExist()
    }
}
