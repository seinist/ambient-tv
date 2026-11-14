package com.ambient.tv.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ambient.tv.R
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.player.PlayerPanelVisibility
import com.ambient.tv.ui.player.PlaybackSeekStopTest
import com.ambient.tv.ui.player.SleepTimerConfig
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real shared player audio settings (SmartTube, Plex, Jellyfin, LiveTV, MPV/VLC/Media3).
 * Changing a track closes only the anchored selection popup, never its AUDIO page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackSeekStopTest.PreferencesApplication::class)
class AudioTrackPanelSelectionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun verifySelectionKeepsAudioSettings(nested: Boolean) {
        val selected = mutableStateOf("1")
        lateinit var nav: PlayerPanelNavigation
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides false) {
                AmbientTvTheme {
                    nav = rememberPlayerPanelNavigation()
                    CompositionLocalProvider(LocalPlayerPanelNavigation provides nav) {
                        Box(Modifier.fillMaxSize()) {
                            PlayerPanelVisibility(nav.panel) { level ->
                                if (level != UnifiedPlayerPanel.NONE) UnifiedBackendPlayerPanel(
                                    panel = level,
                                    onPanelChange = nav::open,
                                    hasVideo = true,
                                    isLive = false,
                                    scalingKey = "fit",
                                    aspectKey = "auto",
                                    zoomFactor = 1f,
                                    speed = 1f,
                                    onScalingChanged = null,
                                    onAspectChanged = null,
                                    onZoomChanged = null,
                                    onSpeedChanged = null,
                                    audioOptions = listOf("1" to "Track One", "2" to "Track Two"),
                                    selectedAudio = selected.value,
                                    onAudioSelected = { selected.value = it },
                                    subtitleOptions = listOf("off" to "Off"),
                                    selectedSubtitle = "off",
                                    onSubtitleSelected = {},
                                    sleepTimer = SleepTimerConfig(),
                                    onSleepChanged = {},
                                    infoLines = emptyList(),
                                    showVideo = false,
                                    showSubtitles = false,
                                    showRenderer = false,
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.runOnIdle {
            if (nested) nav.open(UnifiedPlayerPanel.SETTINGS)
            nav.open(UnifiedPlayerPanel.AUDIO)
        }
        rule.waitForIdle()

        val selectorLabel = rule.activity.getString(R.string.player_audio_track_title)
        rule.onNodeWithText(selectorLabel).assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Track Two").assertIsDisplayed().performClick()
        rule.waitForIdle()

        rule.runOnIdle {
            assertEquals("2", selected.value)
            assertEquals(UnifiedPlayerPanel.AUDIO, nav.panel)
            assertEquals(if (nested) 2 else 1, nav.depth)
            assertNull(nav.restoreKey)
        }
        // The closed selector displays Track Two. Track One exists only in an open popup,
        // so this also catches an unintended immediate reopen after selection.
        rule.onNodeWithText(selectorLabel).assertIsDisplayed()
        rule.onNodeWithText("Track Two").assertIsDisplayed()
        rule.onAllNodesWithText("Track One").assertCountEquals(0)

        // Explicit Back still leaves exactly one level after selection.
        rule.runOnIdle { nav.back(UnifiedPlayerPanel.AUDIO) }
        rule.runOnIdle {
            assertEquals(if (nested) UnifiedPlayerPanel.SETTINGS else UnifiedPlayerPanel.NONE, nav.panel)
        }
    }

    @Test fun selectingTrackFromDirectAudioPanelKeepsAudioSettingsOpen() {
        verifySelectionKeepsAudioSettings(nested = false)
    }

    @Test fun selectingTrackFromPlaybackSettingsKeepsAudioSettingsOpen() {
        verifySelectionKeepsAudioSettings(nested = true)
    }
}
