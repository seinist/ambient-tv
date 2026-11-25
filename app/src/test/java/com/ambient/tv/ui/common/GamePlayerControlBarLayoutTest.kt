package com.ambient.tv.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.games.GameStateEntry
import com.ambient.tv.games.GameStateKind
import com.ambient.tv.ui.games.GameOsdAvailability
import com.ambient.tv.ui.games.GamePlayerTransportControls
import com.ambient.tv.ui.mobile.LocalHandheldFullscreen
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.player.OsdButton
import com.ambient.tv.ui.player.OsdPlayer
import com.ambient.tv.ui.player.PlaybackSeekStopTest
import com.ambient.tv.ui.player.setOsdButtonVisible
import com.ambient.tv.ui.player.videoImagePreferences
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.util.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Games use the real shared video-player bar, without a video fullscreen controller. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackSeekStopTest.PreferencesApplication::class,
    qualifiers = "w960dp-h540dp-land-mdpi")
class GamePlayerControlBarLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var stopped = 0
    private var reset = 0
    private var fastForwarded = 0
    private val rewind = mutableListOf<Boolean>()
    private var openedSettings = 0
    private var availability by mutableStateOf(GameOsdAvailability(
        loaded = true, stateAvailable = true, frameExact = true, rewindAvailable = true,
        states = listOf(GameStateEntry("manual", GameStateKind.MANUAL, 1L, "core", "stamp")),
        coreId = "core", coreStamp = "stamp",
    ))

    private fun mount(handheld: Boolean, extraOptions: Int = 0, viewportWidth: Dp? = null) {
        rule.runOnUiThread {
            PlayerControlBarScroll.save(null, "fullscreen", 0)
            val preferences = videoImagePreferences(rule.activity)
            OsdPlayer.GAMES.buttons.forEach { setOsdButtonVisible(preferences, OsdPlayer.GAMES, it, true) }
        }
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides handheld, LocalHandheldFullscreen provides null) {
                AmbientTvTheme {
                    Box((viewportWidth?.let { Modifier.width(it) } ?: Modifier.fillMaxWidth()).testTag("viewport")) {
                        PlayerControlBar(
                            modifier = Modifier.testTag("bar"), player = OsdPlayer.GAMES,
                            leftContent = {
                                GamePlayerTransportControls(availability = availability,
                                    canResume = !availability.busy, fastForward = false, rewinding = false,
                                    statesOpen = false, onResume = {}, onExit = { stopped++ }, onReset = { reset++ },
                                    onFastForward = { fastForwarded++ }, onRewind = { rewind += it },
                                    onSave = {}, onLoad = {}, onStates = {},
                                    resumeModifier = Modifier.testTag("continue"))
                            },
                            rightContent = {
                                repeat(extraOptions) { PlayerIconButton(Icons.Filled.Info, "Option $it", {}) }
                                PlayerIconButton(Icons.Filled.Info, "Info", {}, modifier = Modifier.testTag("info"))
                                PlayerIconButton(Icons.Filled.Settings, "Settings", { openedSettings++ },
                                    modifier = Modifier.testTag("settings"))
                            },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun action(label: Int) = rule.onNodeWithContentDescription(rule.activity.getString(label))

    private fun assertGroupsFillAvailableWidth() {
        val viewport = rule.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val bar = rule.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot
        val actions = listOf(R.string.game_rewind_hold, R.string.game_continue, R.string.game_reset,
            R.string.game_exit, R.string.game_fast_forward, R.string.game_save_state,
            R.string.game_quick_load, R.string.game_states).map { label ->
            action(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        val info = rule.onNodeWithTag("info").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val settings = rule.onNodeWithTag("settings").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(viewport.width, bar.width, 1f)
        assertEquals(bar.left, actions.first().left, 1f)
        actions.zipWithNext().forEach { (left, right) ->
            assertTrue("Transport controls follow the shared video order before save actions", left.right <= right.left)
        }
        assertTrue("Options must stay separated from playback controls", info.left > actions.last().right + 24f)
        assertEquals(bar.right, settings.right, 1f)
    }

    @Test fun phoneGameBarAlignsPlaybackLeftAndOptionsRightWithoutAVideoFullscreenController() {
        mount(handheld = true)
        assertGroupsFillAvailableWidth()
        action(R.string.game_exit).performTouchInput { click() }
        rule.onNodeWithTag("settings").performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, stopped); assertEquals(1, openedSettings) }
    }

    @Test fun televisionKeepsTheSharedFullWidthGroupingAndRemoteActivation() {
        mount(handheld = false)
        assertGroupsFillAvailableWidth()
        rule.onNodeWithTag("continue").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.onNodeWithTag("continue").performKeyInput { pressKey(Key.DirectionLeft) }
        action(R.string.game_rewind_hold).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionCenter)
            pressKey(Key.DirectionRight)
        }
        rule.onNodeWithTag("continue").assertIsFocused()
        rule.onNodeWithTag("continue").performKeyInput { pressKey(Key.DirectionRight) }
        action(R.string.game_reset).assertIsFocused().performKeyInput {
            pressKey(Key.Enter)
            pressKey(Key.DirectionRight)
        }
        action(R.string.game_exit).assertIsFocused().performKeyInput {
            pressKey(Key.Enter)
            pressKey(Key.DirectionRight)
        }
        action(R.string.game_fast_forward).assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle {
            assertEquals(listOf(true, false), rewind)
            assertEquals(1, reset)
            assertEquals(1, stopped)
            assertEquals(1, fastForwarded)
        }
    }

    @Test fun unavailableOrHiddenGameActionsDoNotLeaveDisabledTransportButtons() {
        mount(handheld = false)
        rule.runOnIdle { availability = availability.copy(netplay = true, rewindAvailable = false) }
        listOf(R.string.game_rewind_hold, R.string.game_reset, R.string.game_fast_forward,
            R.string.game_quick_load).forEach { action(it).assertDoesNotExist() }
        action(R.string.game_continue).assertIsDisplayed()
        action(R.string.game_exit).assertIsDisplayed()
        action(R.string.game_save_state).assertIsDisplayed()
        action(R.string.game_states).assertIsDisplayed()
        rule.runOnIdle {
            availability = availability.copy(netplay = false, rewindAvailable = true)
            setOsdButtonVisible(videoImagePreferences(rule.activity), OsdPlayer.GAMES, OsdButton.GAME_RESET, false)
        }
        action(R.string.game_reset).assertDoesNotExist()
        action(R.string.game_rewind_hold).assertIsDisplayed()
        action(R.string.game_fast_forward).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    fun narrowPhoneKeepsOverflowingOptionsReachableByScrolling() {
        mount(handheld = true, extraOptions = 7, viewportWidth = 360.dp)
        rule.onNodeWithTag("settings").assertIsNotDisplayed()
            .performScrollTo().assertIsDisplayed().performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, openedSettings) }
    }
}
