@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.ambient.tv.R
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.common.UnifiedPlayerSidePanel
import com.ambient.tv.ui.mobile.LocalHandheldUi
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
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
    private var counterReads = 0
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
            "getVideoDecoderCounters" -> { counterReads++; null }
            "getVideoFormat", "getAudioFormat", "getPlayerError" -> null
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
                    if (displayed == UnifiedPlayerPanel.OSD_INFO) UnifiedPlayerSidePanel(
                        title = stringResource(R.string.player_osd_info),
                        onBack = { panel = UnifiedPlayerPanel.NONE },
                    ) {
                        PlayerOsdInfoPanelControls(session, hasVideo = true, onOpenButtons = {})
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

    private fun clickPanelRow(title: String) {
        val autoAdvance = rule.mainClock.autoAdvance
        try {
            // performScrollTo repeats until layout acknowledges each ScrollBy action.
            rule.mainClock.autoAdvance = true
            rule.onNodeWithText(title).performScrollTo()
        } finally {
            rule.mainClock.autoAdvance = autoAdvance
        }
        settle()
        rule.onNodeWithText(title).assertIsDisplayed().performClick()
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
        clickPanelRow(toggleTitle)
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
        clickPanelRow(toggleTitle)
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
        clickPanelRow(toggleTitle)
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

    @Test fun displayChoiceIsIndependentOfVisibilityAndUsesNoReaderAfterTheOverlayCloses() {
        mount()
        val presentationTitle = rule.activity.getString(R.string.player_frame_drop_presentation)
        val compact = rule.activity.getString(R.string.player_frame_drop_compact)
        val withHistory = rule.activity.getString(R.string.player_frame_drop_with_history)
        val graphTitle = rule.activity.getString(R.string.player_frame_drop_history_title, 60)
        rule.runOnIdle {
            assertFalse(session.showFrameDropHistory)
            assertFalse(session.showFrameDropOverlay)
        }
        val hiddenReads = counterReads
        clickPanelRow(presentationTitle)
        settle()
        rule.onNodeWithText(withHistory).assertIsDisplayed().performClick()
        settle()
        rule.runOnIdle {
            assertTrue(session.showFrameDropHistory)
            assertFalse(session.showFrameDropOverlay)
            assertEquals("Choosing the graph alone must not start a hidden sampler", hiddenReads, counterReads)
        }
        rule.onNodeWithText(graphTitle).assertDoesNotExist()

        clickPanelRow(toggleTitle)
        settle()
        rule.onNodeWithText(graphTitle).assertExists()
        rule.onNodeWithText(overlayText).assertExists()
        rule.runOnIdle {
            assertTrue(counterReads > hiddenReads)
            panel = UnifiedPlayerPanel.NONE
        }
        settle()
        rule.onNodeWithText(graphTitle).assertExists()

        rule.runOnIdle { panel = UnifiedPlayerPanel.OSD_INFO }
        settle()
        clickPanelRow(presentationTitle)
        settle()
        rule.onNodeWithText(compact).assertIsDisplayed().performClick()
        settle()
        rule.onNodeWithText(graphTitle).assertDoesNotExist()
        rule.onNodeWithText(overlayText).assertExists()
        rule.runOnIdle {
            assertTrue(session.showFrameDropOverlay)
            assertFalse(session.showFrameDropHistory)
        }

        clickPanelRow(toggleTitle)
        rule.runOnIdle { panel = UnifiedPlayerPanel.NONE }
        settle()
        val closedReads = counterReads
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
        rule.onNodeWithText(overlayText).assertDoesNotExist()
        rule.runOnIdle { assertEquals("Hidden overlay must stop polling the renderer", closedReads, counterReads) }
    }
}
