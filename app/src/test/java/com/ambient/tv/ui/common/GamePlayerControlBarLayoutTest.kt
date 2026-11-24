package com.ambient.tv.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    private var openedSettings = 0

    private fun mount(handheld: Boolean, extraOptions: Int = 0, viewportWidth: Dp? = null) {
        rule.runOnUiThread {
            PlayerControlBarScroll.save(null, "fullscreen", 0)
            val preferences = videoImagePreferences(rule.activity)
            setOsdButtonVisible(preferences, OsdPlayer.GAMES, OsdButton.EXIT, true)
            setOsdButtonVisible(preferences, OsdPlayer.GAMES, OsdButton.GAME_SAVE, true)
        }
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides handheld, LocalHandheldFullscreen provides null) {
                AmbientTvTheme {
                    Box((viewportWidth?.let { Modifier.width(it) } ?: Modifier.fillMaxWidth()).testTag("viewport")) {
                        PlayerControlBar(
                            modifier = Modifier.testTag("bar"), player = OsdPlayer.GAMES,
                            leftContent = {
                                PlayerIconButton(Icons.Filled.PlayArrow, "Continue", {}, primary = true,
                                    modifier = Modifier.testTag("continue"))
                                PlayerIconButton(Icons.Filled.Stop, "Stop", { stopped++ }, osd = OsdButton.EXIT,
                                    modifier = Modifier.testTag("stop"))
                                PlayerIconButton(Icons.Filled.Save, "Save", {}, osd = OsdButton.GAME_SAVE,
                                    modifier = Modifier.testTag("save"))
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

    private fun assertGroupsFillAvailableWidth() {
        val viewport = rule.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val bar = rule.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot
        val play = rule.onNodeWithTag("continue").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val stop = rule.onNodeWithTag("stop").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val save = rule.onNodeWithTag("save").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val info = rule.onNodeWithTag("info").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val settings = rule.onNodeWithTag("settings").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(viewport.width, bar.width, 1f)
        assertEquals(bar.left, play.left, 1f)
        assertTrue(play.right <= stop.left && stop.right <= save.left)
        assertTrue("Options must stay separated from playback controls", info.left > save.right + 24f)
        assertEquals(bar.right, settings.right, 1f)
    }

    @Test fun phoneGameBarAlignsPlaybackLeftAndOptionsRightWithoutAVideoFullscreenController() {
        mount(handheld = true)
        assertGroupsFillAvailableWidth()
        rule.onNodeWithTag("stop").performTouchInput { click() }
        rule.onNodeWithTag("settings").performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, stopped); assertEquals(1, openedSettings) }
    }

    @Test fun televisionKeepsTheSharedFullWidthGroupingAndRemoteActivation() {
        mount(handheld = false)
        assertGroupsFillAvailableWidth()
        rule.onNodeWithTag("continue").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.onNodeWithTag("continue").performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithTag("stop").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals(1, stopped) }
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
