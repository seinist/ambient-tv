@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.ambient.tv.R
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.mobile.LocalHandheldUi
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackSeekStopTest.PreferencesApplication::class)
class PlaybackFrameDropOverlayTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var panel by mutableStateOf(UnifiedPlayerPanel.OSD_INFO)
    private lateinit var session: AudioSession
    private val metadata = PlaybackMetadata("frame-drops", "Frame-drop test", source = "audit", mediaId = "frame-drops")
    private val overlayText get() = rule.activity.getString(R.string.player_frame_drop_measured_fps, "—", "—")
    private val toggleTitle get() = rule.activity.getString(R.string.player_frame_drop_overlay)

    /** No decoder samples: a visible overlay must say unavailable instead of displaying zero. */
    private fun playerFixture(): ExoPlayer = Proxy.newProxyInstance(
        ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "toString" -> "Frame-drop overlay fixture"
            "getVideoDecoderCounters", "getVideoFormat", "getAudioFormat", "getPlayerError" -> null
            "getCurrentTracks" -> Tracks.EMPTY
            "getCurrentTimeline" -> Timeline.EMPTY
            "getCurrentPosition" -> 1_000L
            "getDuration" -> 600_000L
            "getPlaybackState" -> Player.STATE_READY
            "isPlaying", "getPlayWhenReady" -> true
            "isCurrentMediaItemLive" -> false
            "getPlaybackParameters" -> PlaybackParameters.DEFAULT
            "getMediaItemCount" -> 0
            "getTrackSelectionParameters" -> TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
            "addListener", "removeListener", "setTrackSelectionParameters", "clearVideoSurface",
            "clearMediaItems", "setPlayWhenReady", "release" -> null
            else -> error("Unexpected player call: ${method.name}")
        }
    } as ExoPlayer

    private fun mount() {
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread {
            session = AudioSession(videoImagePreferences(rule.activity), "media3", false)
            val player = playerFixture()
            Media3AudioProcessing.attach(player, session, rule.activity)
            BackgroundPlaybackSession.acquire(rule.activity, metadata, "media3", Any()) {
                Media3PlaybackHandle(player)
            }
        }
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true) {
                // The real host stays composed with NONE and owns exactly one popup.
                PlayerPanelVisibility(panel) { displayed ->
                    if (displayed == UnifiedPlayerPanel.OSD_INFO) Column {
                        PlayerOsdInfoPanelControls(session, onOpenButtons = {})
                    }
                }
            }
        }
        settle()
    }

    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(300)
        rule.waitForIdle()
    }

    @After fun cleanup() {
        rule.runOnUiThread {
            BackgroundPlaybackSession.stop()
            BackgroundPlaybackSession.takeCompleted()
        }
    }

    @Test fun informationToggleKeepsOneOverlayAfterClosingPanelsAndRetainsAutomaticMode() {
        mount()
        rule.onNodeWithText(rule.activity.getString(R.string.player_frame_drop_overlay_auto)).assertExists()
        rule.onNodeWithText(overlayText).assertDoesNotExist()
        rule.onNodeWithText(toggleTitle).performClick()
        settle()
        rule.runOnIdle { assertTrue(session.showFrameDropOverlay) }
        rule.onNodeWithText(rule.activity.getString(R.string.player_frame_drop_overlay_always)).assertExists()
        rule.onNodeWithText(overlayText).assertExists()

        rule.runOnIdle { panel = UnifiedPlayerPanel.NONE }
        settle()
        rule.onNodeWithText(overlayText).assertExists()

        rule.runOnIdle { panel = UnifiedPlayerPanel.VIDEO }
        settle()
        rule.onAllNodesWithText(overlayText).assertCountEquals(1)
        rule.runOnIdle { session.showFrameDropOverlay = false }
        settle()
        rule.onNodeWithText(overlayText).assertExists()
        rule.runOnIdle { panel = UnifiedPlayerPanel.NONE }
        settle()
        rule.onNodeWithText(overlayText).assertDoesNotExist()

        rule.runOnIdle { panel = UnifiedPlayerPanel.RENDERER }
        settle()
        rule.onNodeWithText(overlayText).assertExists()
    }

    @Test fun audioOnlyAndStopHideTheOverlayEvenWhenAlwaysShowIsSelected() {
        mount()
        rule.onNodeWithText(toggleTitle).performClick()
        settle()
        rule.onNodeWithText(overlayText).assertExists()
        rule.runOnIdle { session.updateAudioOnly(true) }
        settle()
        rule.onNodeWithText(toggleTitle).assertDoesNotExist()
        rule.onNodeWithText(overlayText).assertDoesNotExist()

        rule.runOnIdle {
            session.updateAudioOnly(false)
            panel = UnifiedPlayerPanel.NONE
        }
        settle()
        rule.onNodeWithText(overlayText).assertExists()
        rule.runOnIdle { BackgroundPlaybackSession.updateMetadata(metadata.copy(hasVideo = false)) }
        settle()
        rule.onNodeWithText(overlayText).assertDoesNotExist()
        rule.runOnIdle { BackgroundPlaybackSession.updateMetadata(metadata) }
        settle()
        rule.onNodeWithText(overlayText).assertExists()

        rule.runOnIdle { BackgroundPlaybackSession.stop() }
        settle()
        rule.onNodeWithText(overlayText).assertDoesNotExist()
        rule.runOnIdle {
            assertTrue(session.showFrameDropOverlay)
            assertFalse(BackgroundPlaybackSession.snapshot.value.active)
        }
    }

    @Test fun unsupportedPlaybackCannotReuseThePreviousVideosOverlay() {
        mount()
        rule.onNodeWithText(toggleTitle).performClick()
        settle()
        rule.onNodeWithText(overlayText).assertExists()
        rule.runOnIdle {
            panel = UnifiedPlayerPanel.RENDERER
            BackgroundPlaybackSession.acquire(rule.activity, metadata.copy(route = "other", mediaId = "other"),
                "unsupported", Any()) {
                object : PlaybackHandle {
                    override fun status() = PlaybackStatus(playing = true)
                    override fun play() = Unit
                    override fun pause() = Unit
                    override fun seekTo(positionMs: Long) = Unit
                    override fun release() = Unit
                }
            }
        }
        settle()
        rule.onNodeWithText(overlayText).assertDoesNotExist()
        rule.runOnIdle { assertTrue(session.showFrameDropOverlay) }
    }
}
