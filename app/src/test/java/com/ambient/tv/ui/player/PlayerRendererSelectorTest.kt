package com.ambient.tv.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TitleVideoSettings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackSeekStopTest.PreferencesApplication::class)
class PlayerRendererSelectorTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var preferences: AppPreferences
    private val metadata = PlaybackMetadata("renderer-menu/current", "Current video", source = "plex", mediaId = "renderer-menu")

    @Before fun setUp() {
        rule.runOnUiThread {
            BackgroundPlaybackSession.stop()
            BackgroundPlaybackSession.takeCompleted()
            rule.activity.getSharedPreferences("test", 0).edit().clear().commit()
            preferences = videoImagePreferences(rule.activity)
            preferences.playbackBackendKey = "mpv"
            preferences.titleVideoSettings = mapOf("plex:renderer-menu" to TitleVideoSettings(rendererKey = "mpv"))
            TitleVideoSettingsStore.endSession()
        }
    }

    @After fun tearDown() {
        rule.runOnUiThread {
            BackgroundPlaybackSession.stop()
            BackgroundPlaybackSession.takeCompleted()
        }
    }

    @Test fun lateDolbyVisionDisablesAnOpenMenuAndCannotSaveOrSwitchToVlc() {
        var source by mutableStateOf(metadata)
        val selected = mutableListOf<String>()
        rule.setContent {
            CompositionLocalProvider(LocalPlaybackMetadata provides source) {
                Column(Modifier.fillMaxWidth()) {
                    PlayerRendererSelector("media3") { key, _ -> selected += key }
                }
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.player_renderer_current)).performClick()
        rule.onNodeWithText("VLC").assertIsEnabled()
        rule.runOnIdle { source = source.copy(dolbyVisionSource = true) }
        rule.onNodeWithText("VLC").assertIsNotEnabled().performTouchInput { click() }
        rule.onNodeWithText(rule.activity.getString(R.string.player_renderer_dolby_vision_only_media3)).assertExists()
        rule.runOnIdle {
            assertTrue(selected.isEmpty())
            assertEquals("mpv", preferences.playbackBackendKey)
            assertEquals("mpv", preferences.titleVideoSettings["plex:renderer-menu"]?.rendererKey)
        }
    }

    @Test fun hdr10StillAllowsExplicitVlcSelectionAndPersistsItForTheTitleOnly() {
        val selected = mutableListOf<Pair<String, Boolean>>()
        rule.setContent {
            CompositionLocalProvider(LocalPlaybackMetadata provides metadata.copy(handheldHdrStandard = HdrStandard.HDR10)) {
                Column(Modifier.fillMaxWidth()) {
                    PlayerRendererSelector("media3") { key, global -> selected += key to global }
                }
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.player_renderer_current)).performClick()
        rule.onNodeWithText("VLC").assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(listOf("vlc" to false), selected)
            assertEquals("vlc", preferences.titleVideoSettings["plex:renderer-menu"]?.rendererKey)
            assertEquals("mpv", preferences.playbackBackendKey)
        }
    }

    @Test fun openMenuUsesTheReplacementSdrSourceDespiteRetainedOriginalDolbyVisionMetadata() {
        val original = metadata.copy(dolbyVisionSource = true, handheldHdrStandard = HdrStandard.DOLBY_VISION)
        val selected = mutableListOf<String>()
        rule.runOnUiThread {
            BackgroundPlaybackSession.acquire(rule.activity, original, "fixture", Any()) {
                object : PlaybackHandle {
                    override fun status() = PlaybackStatus(positionMs = 30_000L, durationMs = 300_000L)
                    override fun play() = Unit
                    override fun pause() = Unit
                    override fun seekTo(positionMs: Long) = Unit
                    override fun release() = Unit
                }
            }
        }
        rule.setContent {
            CompositionLocalProvider(LocalPlaybackMetadata provides original) {
                Column(Modifier.fillMaxWidth()) {
                    PlayerRendererSelector("media3") { key, _ -> selected += key }
                }
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.player_renderer_current)).performClick()
        rule.onNodeWithText("VLC").assertIsNotEnabled()
        rule.runOnIdle { BackgroundPlaybackSession.refreshMetadata(metadata) }
        rule.onNodeWithText("VLC").assertIsEnabled()
        rule.runOnIdle {
            val snapshot = BackgroundPlaybackSession.snapshot.value
            assertFalse(snapshot.replacingSource)
            assertEquals(metadata, snapshot.metadata)
            assertTrue(currentPlayerRendererAvailability(original).canSelect("vlc"))
        }
        rule.onNodeWithText("VLC").performClick()
        rule.runOnIdle {
            assertEquals(listOf("vlc"), selected)
            assertEquals("vlc", preferences.titleVideoSettings["plex:renderer-menu"]?.rendererKey)
            assertEquals("mpv", preferences.playbackBackendKey)
        }
    }
}
