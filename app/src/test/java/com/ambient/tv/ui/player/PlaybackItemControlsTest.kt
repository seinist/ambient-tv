package com.ambient.tv.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.ambient.tv.companion.CompanionQueuePlayback
import com.ambient.tv.R
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.mobile.LocalHandheldUi
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackSeekStopTest.PreferencesApplication::class)
class PlaybackItemControlsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Clock(var seekable: Boolean = true) : PlaybackHandle {
        var position = 60_000L
        var playing = true
        var ended = false
        var acknowledgeSeeks = true
        val seeks = mutableListOf<Long>()
        override fun status() = PlaybackStatus(positionMs = position, durationMs = 120_000, playing = playing, ended = ended)
        override fun canSeekNow() = seekable
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun seekTo(positionMs: Long) {
            seeks += positionMs
            if (acknowledgeSeeks) { position = positionMs; ended = false }
        }
        override fun release() = Unit
    }

    private class Fixture(metadata: PlaybackMetadata, val clock: Clock) {
        val metadata = mutableStateOf(metadata)
        val changing = mutableStateOf(false)
        val previousAvailable = mutableStateOf(false)
        val focus = FocusRequester()
        var controls: PlaybackItemControls? = null
        var previousCalls = 0
        val advances = mutableListOf<NextUpCandidate>()
    }

    private val successor = NextUpCandidate("next", "Next video", source = "youtube")

    private fun metadata(next: NextUpCandidate? = successor) = PlaybackMetadata(
        route = "youtube/current", title = "Current video", source = "youtube", mediaId = "current",
        durationMs = 120_000, nextUp = next,
        nextUpConfig = NextUpConfig(enabled = false, autoplay = false),
    )

    @Before fun setUp() {
        rule.runOnUiThread {
            BackgroundPlaybackSession.stop()
            BackgroundPlaybackSession.takeCompleted()
            CompanionQueuePlayback.pending = false
            videoImagePreferences(rule.activity).pauseRewindSeconds = 0
            videoImagePreferences(rule.activity).nextUpMinimalEnabled = false
        }
    }

    @After fun tearDown() {
        rule.runOnUiThread {
            CompanionQueuePlayback.pending = false
            videoImagePreferences(rule.activity).pauseRewindSeconds = 0
            videoImagePreferences(rule.activity).nextUpMinimalEnabled = false
            BackgroundPlaybackSession.stop()
            BackgroundPlaybackSession.takeCompleted()
        }
    }

    private fun mount(
        metadata: PlaybackMetadata = metadata(),
        clock: Clock = Clock(),
        onTimelineSeek: ((Long) -> Unit)? = null,
        hasPrevious: Boolean = false,
        chrome: PlayerChromeState? = null,
    ): Fixture {
        val fixture = Fixture(metadata, clock).apply { previousAvailable.value = hasPrevious }
        rule.runOnUiThread {
            BackgroundPlaybackSession.acquire(rule.activity, metadata, "fixture", Any()) { clock }
        }
        rule.setContent {
            CompositionLocalProvider(
                LocalPlayerChangingItem provides fixture.changing.value,
                LocalHandheldUi provides true,
                LocalPlayerChromeState provides chrome,
            ) {
                PlaybackSessionContent(
                    metadata = fixture.metadata.value,
                    onStopped = {},
                    onPlayNext = fixture.advances::add,
                    onTimelineSeek = onTimelineSeek,
                    onPlayPrevious = if (fixture.previousAvailable.value) ({ fixture.previousCalls++ }) else null,
                ) {
                    val controls = LocalPlaybackItemControls.current
                    SideEffect { fixture.controls = controls }
                    Box(Modifier.fillMaxSize().focusRequester(fixture.focus).focusable())
                }
            }
        }
        rule.waitForIdle()
        return fixture
    }

    @Test fun lateSuccessorEnablesManualNextWithAutoplayOffAndAcceptsRepeatedPressesOnlyOnce() {
        val fixture = mount(metadata(next = null))
        rule.runOnIdle {
            assertNotNull(fixture.controls?.restart)
            assertNull(fixture.controls?.next)
            BackgroundPlaybackSession.recordAutomaticNextUp(successor)
            assertTrue(BackgroundPlaybackSession.shouldConfirmNextUp(1))
            fixture.metadata.value = fixture.metadata.value.copy(nextUp = successor)
        }
        rule.runOnIdle {
            val next = requireNotNull(fixture.controls?.next)
            next()
            next()
            assertEquals(listOf(successor), fixture.advances)
            assertFalse(BackgroundPlaybackSession.shouldConfirmNextUp(1))
        }
    }

    @Test fun restartSeeksExactlyToZeroAndCancelsThePausedResumeRewind() {
        val fixture = mount()
        rule.runOnIdle {
            videoImagePreferences(rule.activity).apply {
                pauseRewindSeconds = 5
                pauseRewindMinPauseSeconds = 10
            }
            BackgroundPlaybackSession.userPause()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
            val seekVersion = BackgroundPlaybackSession.externalSeekVersion
            assertFalse(requireNotNull(fixture.controls).hasPreviousItem)
            assertNull(fixture.controls?.previous)
            requireNotNull(fixture.controls?.restart).invoke()
            assertEquals(listOf(0L), fixture.clock.seeks)
            assertEquals(seekVersion + 1, BackgroundPlaybackSession.externalSeekVersion)
            BackgroundPlaybackSession.userPlay()
            assertEquals(listOf(0L), fixture.clock.seeks)
            assertTrue(fixture.clock.playing)
        }
    }

    @Test fun restartUsesTheAbsoluteTimelineWhenTheRendererMustReopenTheSource() {
        val timelineSeeks = mutableListOf<Long>()
        val fixture = mount(
            metadata = metadata().copy(timelineOffsetMs = 30_000),
            clock = Clock(seekable = false),
            onTimelineSeek = timelineSeeks::add,
        )
        rule.runOnIdle {
            requireNotNull(fixture.controls?.restart).invoke()
            assertEquals(listOf(0L), timelineSeeks)
            assertTrue(fixture.clock.seeks.isEmpty())
        }
    }

    @Test fun unseekableVideoKeepsNextAvailableWithoutOfferingRestart() {
        val fixture = mount(clock = Clock(seekable = false))
        rule.runOnIdle {
            assertNull(fixture.controls?.restart)
            assertNull(fixture.controls?.previous)
            assertNotNull(fixture.controls?.next)
        }
    }

    @Test fun realPreviousDoesNotNeedASeekableRendererAndRunsOnlyOnce() {
        val fixture = mount(clock = Clock(seekable = false), hasPrevious = true)
        rule.runOnIdle {
            val controls = requireNotNull(fixture.controls)
            assertTrue(controls.hasPreviousItem)
            assertNull(controls.restart)
            val previous = requireNotNull(controls.previous)
            previous()
            previous()
            assertEquals(1, fixture.previousCalls)
            assertTrue(fixture.clock.seeks.isEmpty())
        }
        rule.runOnIdle {
            assertTrue(requireNotNull(fixture.controls).hasPreviousItem)
            assertNull(fixture.controls?.previous)
        }
    }

    @Test fun youtubeMediaPreviousDoesNothingWithoutHistoryAndUsesHistoryWhenAvailable() {
        val fixture = mount()
        rule.runOnIdle { fixture.focus.requestFocus() }
        rule.onRoot().performKeyInput { pressKey(Key.MediaPrevious) }
        rule.runOnIdle {
            assertNull(fixture.controls?.previous)
            assertTrue(fixture.clock.seeks.isEmpty())
            assertEquals(0, fixture.previousCalls)
            fixture.previousAvailable.value = true
        }
        rule.onRoot().performKeyInput { pressKey(Key.MediaPrevious) }
        rule.runOnIdle {
            assertTrue(fixture.clock.seeks.isEmpty())
            assertEquals(1, fixture.previousCalls)
        }
    }

    @Test fun otherVideoSourcesKeepTheirStartOverFallback() {
        val fixture = mount(metadata = metadata().copy(source = "plex", route = "plex/current"))
        rule.runOnIdle { fixture.focus.requestFocus() }
        rule.onRoot().performKeyInput { pressKey(Key.MediaPrevious) }
        rule.runOnIdle {
            assertEquals(listOf(0L), fixture.clock.seeks)
            assertEquals(0, fixture.previousCalls)
        }
    }

    @Test fun changingItemDisablesBothActionsAndTheSessionTransitionRejectsCapturedCallbacks() {
        val fixture = mount(hasPrevious = true)
        rule.runOnIdle { fixture.changing.value = true }
        rule.runOnIdle {
            assertNotNull(fixture.controls)
            assertNull(fixture.controls?.restart)
            assertNull(fixture.controls?.next)
            assertTrue(requireNotNull(fixture.controls).hasPreviousItem)
            assertNull(fixture.controls?.previous)
            fixture.changing.value = false
        }
        rule.runOnIdle {
            val beforeTransition = requireNotNull(fixture.controls)
            assertNotNull(beforeTransition.restart)
            assertNotNull(beforeTransition.next)
            BackgroundPlaybackSession.beginItemTransition()
            // The old OSD can still be mounted until Compose processes this session update.
            beforeTransition.restart!!.invoke()
            beforeTransition.next!!.invoke()
            beforeTransition.previous!!.invoke()
            assertTrue(fixture.clock.seeks.isEmpty())
            assertTrue(fixture.advances.isEmpty())
            assertEquals(0, fixture.previousCalls)
        }
        rule.runOnIdle {
            assertNull(fixture.controls?.restart)
            assertNull(fixture.controls?.next)
            assertNull(fixture.controls?.previous)
        }
    }

    @Test fun outgoingRendererAndOutgoingRouteCannotControlTheReplacementSession() {
        val fixture = mount(hasPrevious = true)
        val replacement = Clock()
        val otherItem = Clock()
        rule.runOnIdle {
            val oldRenderer = requireNotNull(fixture.controls)
            BackgroundPlaybackSession.acquire(rule.activity, fixture.metadata.value, "replacement", Any()) { replacement }
            // Replacing the renderer restores its clock before any outgoing OSD action runs.
            assertEquals(listOf(60_000L), replacement.seeks)
            oldRenderer.restart!!.invoke()
            oldRenderer.next!!.invoke()
            oldRenderer.previous!!.invoke()
            assertEquals(listOf(60_000L), replacement.seeks)
            assertTrue(fixture.advances.isEmpty())
            assertEquals(0, fixture.previousCalls)
        }
        rule.runOnIdle {
            val oldRoute = requireNotNull(fixture.controls)
            BackgroundPlaybackSession.acquire(rule.activity,
                metadata().copy(route = "youtube/other", mediaId = "other"), "replacement", Any()) { otherItem }
            oldRoute.restart!!.invoke()
            oldRoute.next!!.invoke()
            oldRoute.previous!!.invoke()
            assertTrue(fixture.clock.seeks.isEmpty())
            assertEquals(listOf(60_000L), replacement.seeks)
            assertTrue(otherItem.seeks.isEmpty())
            assertTrue(fixture.advances.isEmpty())
            assertEquals(0, fixture.previousCalls)
        }
    }

    @Test fun newlyPendingCompanionQueueBlocksAlreadyPublishedActionsWithoutConsumingNext() {
        val fixture = mount(hasPrevious = true)
        rule.runOnIdle {
            val published = requireNotNull(fixture.controls)
            CompanionQueuePlayback.pending = true
            published.restart!!.invoke()
            published.next!!.invoke()
            published.previous!!.invoke()
            assertTrue(fixture.clock.seeks.isEmpty())
            assertTrue(fixture.advances.isEmpty())
            assertEquals(0, fixture.previousCalls)
        }
        rule.runOnIdle {
            assertNull(fixture.controls?.restart)
            assertNull(fixture.controls?.next)
            assertNull(fixture.controls?.previous)
            CompanionQueuePlayback.pending = false
        }
        rule.runOnIdle {
            requireNotNull(fixture.controls?.next).invoke()
            assertEquals(listOf(successor), fixture.advances)
        }
    }

    @Test fun liveVideoStandaloneTrailersAndAudioDoNotPublishVideoItemControls() {
        val fixture = mount(metadata().copy(isLive = true))
        rule.runOnIdle {
            assertNull(fixture.controls)
            fixture.metadata.value = metadata(next = null).copy(isTrailer = true)
        }
        rule.runOnIdle {
            assertNull(fixture.controls)
            fixture.metadata.value = metadata().copy(hasVideo = false)
        }
        rule.runOnIdle {
            assertNull(fixture.controls)
            fixture.metadata.value = metadata()
        }
        rule.runOnIdle {
            assertNotNull(fixture.controls?.restart)
            assertNotNull(fixture.controls?.next)
        }
    }

    @Test fun trailerSourceQueuePublishesManualNextAndPreviousWithNextUpDisabled() {
        val fixture = mount(metadata().copy(isTrailer = true), hasPrevious = true)
        rule.runOnIdle {
            assertFalse(fixture.metadata.value.nextUpConfig.enabled)
            val controls = requireNotNull(fixture.controls)
            assertTrue(controls.hasPreviousItem)
            requireNotNull(controls.previous).invoke()
            requireNotNull(controls.next).invoke()
            assertEquals(1, fixture.previousCalls)
            assertEquals(listOf(successor), fixture.advances)
        }
    }

    @Test fun lastTrailerKeepsPreviousAvailableWithoutInventingANextVideo() {
        val fixture = mount(metadata(next = null).copy(isTrailer = true), hasPrevious = true)
        rule.runOnIdle {
            val controls = requireNotNull(fixture.controls)
            assertTrue(controls.hasPreviousItem)
            assertNull(controls.next)
            requireNotNull(controls.previous).invoke()
            assertEquals(1, fixture.previousCalls)
        }
    }

    @Test fun unansweredStillWatchingPromptClosesTheEndedPlayerAfterItsDeadline() {
        val fixture = mount(metadata().copy(nextUpConfig = NextUpConfig(
            enabled = true, autoplay = true, stillWatchingEvery = 1,
        )))
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            BackgroundPlaybackSession.recordAutomaticNextUp(successor)
            fixture.clock.position = 120_000L
            fixture.clock.ended = true
            fixture.clock.playing = false
            BackgroundPlaybackSession.tick()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue("Manual confirmation must remain briefly actionable after EOF",
                BackgroundPlaybackSession.snapshot.value.active)
            assertTrue(fixture.advances.isEmpty())
        }
        rule.mainClock.advanceTimeBy(NEXT_UP_CONFIRMATION_TIMEOUT_MS + 1_000L)
        rule.waitForIdle()
        rule.runOnIdle {
            assertFalse("Unanswered confirmation must not retain an ended player",
                BackgroundPlaybackSession.snapshot.value.active)
            assertTrue(fixture.advances.isEmpty())
        }
    }

    @Test fun manualNextUpSelectionCancelsTheDelayedEofStop() {
        val fixture = mount(metadata().copy(nextUpConfig = NextUpConfig(
            enabled = true, autoplay = true, stillWatchingEvery = 1,
        )))
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            BackgroundPlaybackSession.recordAutomaticNextUp(successor)
            fixture.clock.position = 120_000L
            fixture.clock.ended = true
            fixture.clock.playing = false
            BackgroundPlaybackSession.tick()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.runOnIdle { requireNotNull(fixture.controls?.next).invoke() }
        rule.mainClock.advanceTimeBy(NEXT_UP_CONFIRMATION_TIMEOUT_MS + 1_000L)
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf(successor), fixture.advances)
            assertTrue("A chosen successor must not be stopped by the old prompt's timer",
                BackgroundPlaybackSession.snapshot.value.active)
        }
    }

    @Test fun aSeekDuringEofGraceCancelsTheOutgoingAutoplayDeadlineBeforeDecoderAck() {
        val fixture = mount(metadata().copy(nextUpConfig = NextUpConfig(
            enabled = true, autoplay = true, showOverlay = false,
        )), clock = Clock().apply { acknowledgeSeeks = false })
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            fixture.clock.position = 120_000L
            BackgroundPlaybackSession.tick()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(NEXT_UP_EOF_GRACE_MS - 500L)
        rule.runOnIdle { BackgroundPlaybackSession.seekTo(10_000L) }
        // The decoder has accepted the seek but still publishes the old end position.
        rule.mainClock.advanceTimeBy(1_000L)
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf(10_000L), fixture.clock.seeks)
            assertTrue("A pre-seek EOF deadline must not advance the title", fixture.advances.isEmpty())
            fixture.clock.position = 10_000L
            BackgroundPlaybackSession.tick()
        }
        rule.mainClock.advanceTimeBy(NEXT_UP_EOF_GRACE_MS + 1_000L)
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue(fixture.advances.isEmpty())
            assertTrue(BackgroundPlaybackSession.snapshot.value.active)
        }
    }

    @Test fun aSeekDuringStillWatchingCancelsTheOldStopDeadlineBeforeDecoderAck() {
        val fixture = mount(metadata().copy(nextUpConfig = NextUpConfig(
            enabled = true, autoplay = true, stillWatchingEvery = 1,
        )), clock = Clock().apply { acknowledgeSeeks = false })
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            BackgroundPlaybackSession.recordAutomaticNextUp(successor)
            fixture.clock.position = 120_000L
            fixture.clock.ended = true
            fixture.clock.playing = false
            BackgroundPlaybackSession.tick()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(NEXT_UP_CONFIRMATION_TIMEOUT_MS - 500L)
        rule.runOnIdle { BackgroundPlaybackSession.seekTo(10_000L) }
        rule.mainClock.advanceTimeBy(1_000L)
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue("A pre-seek confirmation deadline must not stop the title",
                BackgroundPlaybackSession.snapshot.value.active)
            fixture.clock.position = 10_000L
            fixture.clock.ended = false
            BackgroundPlaybackSession.tick()
        }
        rule.mainClock.advanceTimeBy(NEXT_UP_CONFIRMATION_TIMEOUT_MS + 1_000L)
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue(BackgroundPlaybackSession.snapshot.value.active)
            assertTrue(fixture.advances.isEmpty())
        }
    }

    @Test fun trailerSourceQueueStopsAtEofEvenIfTheGlobalAutoplayPreferenceIsOn() {
        val fixture = mount(metadata().copy(
            isTrailer = true,
            nextUpConfig = NextUpConfig(enabled = false, autoplay = true),
        ))
        rule.runOnIdle {
            assertNotNull(fixture.controls?.next)
            fixture.clock.position = 120_000
            fixture.clock.playing = false
            fixture.clock.ended = true
            BackgroundPlaybackSession.tick()
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertFalse(BackgroundPlaybackSession.snapshot.value.active)
            assertTrue(fixture.advances.isEmpty())
        }
    }

    @Test fun minimalCloseHidesTheCardButAutoplayStillAdvancesOnlyOnceAtTheEnd() {
        rule.runOnUiThread { videoImagePreferences(rule.activity).nextUpMinimalEnabled = true }
        val fixture = mount(metadata().copy(nextUpConfig = NextUpConfig(
            enabled = true, autoplay = true, stillWatchingEvery = 0, minimal = true,
        )), clock = Clock().apply { position = 100_000L })
        rule.onNodeWithText(rule.activity.getString(R.string.next_up_close)).performClick()
        rule.onNodeWithText(successor.title).assertDoesNotExist()
        rule.runOnIdle {
            assertTrue(fixture.advances.isEmpty())
            assertTrue(BackgroundPlaybackSession.snapshot.value.metadata!!.nextUpConfig.autoplay)
            fixture.clock.position = 120_000L
            fixture.clock.ended = true
            fixture.clock.playing = false
            BackgroundPlaybackSession.tick()
        }
        rule.waitForIdle()
        rule.runOnIdle { BackgroundPlaybackSession.tick() }
        rule.runOnIdle { assertEquals(listOf(successor), fixture.advances) }
    }

    @Test fun minimalPlayAndAnAlreadyPublishedNextActionCannotStartTheSuccessorTwice() {
        rule.runOnUiThread { videoImagePreferences(rule.activity).nextUpMinimalEnabled = true }
        val fixture = mount(metadata().copy(nextUpConfig = NextUpConfig(
            enabled = true, autoplay = false, minimal = true,
        )), clock = Clock().apply { position = 100_000L })
        val next = rule.runOnIdle { requireNotNull(fixture.controls?.next) }
        rule.onNodeWithText(rule.activity.getString(R.string.player_play)).performClick()
        rule.runOnIdle {
            next()
            next()
            assertEquals(listOf(successor), fixture.advances)
        }
        rule.onNodeWithText(successor.title).assertDoesNotExist()
    }

    @Test fun minimalStillWatchingRemainsABoundedManualChoiceEvenWithTheOverlayDisabled() {
        rule.runOnUiThread { videoImagePreferences(rule.activity).nextUpMinimalEnabled = true }
        val fixture = mount(metadata().copy(nextUpConfig = NextUpConfig(
            enabled = true, autoplay = true, stillWatchingEvery = 1, showOverlay = false, minimal = true,
        )))
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            BackgroundPlaybackSession.recordAutomaticNextUp(successor)
            fixture.clock.position = 120_000L
            fixture.clock.ended = true
            fixture.clock.playing = false
            BackgroundPlaybackSession.tick()
        }
        rule.mainClock.advanceTimeBy(300L)
        rule.waitForIdle()
        rule.onNodeWithText(rule.activity.getString(R.string.next_up_still_watching)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.next_up_continue)).assertIsDisplayed()
        rule.runOnIdle { assertTrue(fixture.advances.isEmpty()) }
        rule.mainClock.advanceTimeBy(NEXT_UP_CONFIRMATION_TIMEOUT_MS + 1_000L)
        rule.waitForIdle()
        rule.runOnIdle {
            assertFalse(BackgroundPlaybackSession.snapshot.value.active)
            assertTrue(fixture.advances.isEmpty())
        }
    }

    @Test fun minimalCardYieldsToTheOsdWithoutCancellingTheAutoplayTransition() {
        rule.runOnUiThread { videoImagePreferences(rule.activity).nextUpMinimalEnabled = true }
        val chrome = PlayerChromeState().apply { osd.hideImmediately() }
        val fixture = mount(metadata().copy(nextUpConfig = NextUpConfig(
            enabled = true, autoplay = true, stillWatchingEvery = 0, minimal = true,
        )), clock = Clock().apply { position = 100_000L }, chrome = chrome)
        rule.onNodeWithText(successor.title).assertIsDisplayed()
        rule.runOnIdle { chrome.osd.expand() }
        rule.onNodeWithText(successor.title).assertDoesNotExist()
        rule.runOnIdle {
            assertTrue(chrome.osd.visible.value)
            fixture.clock.position = 120_000L
            fixture.clock.ended = true
            fixture.clock.playing = false
            BackgroundPlaybackSession.tick()
        }
        rule.runOnIdle { assertEquals(listOf(successor), fixture.advances) }
    }

    @Test fun minimalTerminalConfirmationHidesThePassiveOsdBeforeItsBoundedDeadline() {
        rule.runOnUiThread { videoImagePreferences(rule.activity).nextUpMinimalEnabled = true }
        val chrome = PlayerChromeState().apply { osd.expand() }
        val fixture = mount(metadata().copy(nextUpConfig = NextUpConfig(
            enabled = true, autoplay = true, stillWatchingEvery = 1, minimal = true,
        )), chrome = chrome)
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            BackgroundPlaybackSession.recordAutomaticNextUp(successor)
            fixture.clock.position = 120_000L
            fixture.clock.ended = true
            fixture.clock.playing = false
            BackgroundPlaybackSession.tick()
        }
        rule.mainClock.advanceTimeBy(300L)
        rule.waitForIdle()
        rule.runOnIdle {
            assertFalse("An ended player's passive OSD must not hide the entire confirmation", chrome.osd.visible.value)
            assertTrue(BackgroundPlaybackSession.snapshot.value.active)
            assertTrue(fixture.advances.isEmpty())
        }
        rule.onNodeWithText(rule.activity.getString(R.string.next_up_still_watching)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.next_up_continue)).assertIsDisplayed()
        rule.mainClock.advanceTimeBy(NEXT_UP_CONFIRMATION_TIMEOUT_MS + 1_000L)
        rule.waitForIdle()
        rule.runOnIdle {
            assertFalse(BackgroundPlaybackSession.snapshot.value.active)
            assertTrue(fixture.advances.isEmpty())
        }
    }

    @Test fun minimalTerminalConfirmationWaitsForAnActiveScrubBeforeTakingTheOsd() {
        rule.runOnUiThread { videoImagePreferences(rule.activity).nextUpMinimalEnabled = true }
        val chrome = PlayerChromeState().apply { osd.expand() }
        val fixture = mount(metadata().copy(nextUpConfig = NextUpConfig(
            enabled = true, autoplay = true, stillWatchingEvery = 1, minimal = true,
        )), chrome = chrome)
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            BackgroundPlaybackSession.recordAutomaticNextUp(successor)
            chrome.osd.progressiveSeek.press(-1, 119_000L, 120_000L)
            fixture.clock.position = 120_000L
            fixture.clock.ended = true
            fixture.clock.playing = false
            BackgroundPlaybackSession.tick()
        }
        rule.mainClock.advanceTimeBy(300L)
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue("Held seek input retains its OSD", chrome.osd.visible.value)
            assertTrue(chrome.osd.progressiveSeek.active)
            assertTrue(fixture.advances.isEmpty())
        }
        rule.onNodeWithText(rule.activity.getString(R.string.next_up_still_watching)).assertDoesNotExist()
        rule.runOnIdle { chrome.osd.progressiveSeek.cancel() }
        rule.mainClock.advanceTimeBy(300L)
        rule.waitForIdle()
        rule.runOnIdle { assertFalse(chrome.osd.visible.value) }
        rule.onNodeWithText(rule.activity.getString(R.string.next_up_continue)).assertIsDisplayed()
    }

    @Test fun pendingDecoderAckReleasesTerminalConfirmationWithoutAnotherSnapshotOrANewDeadline() {
        rule.runOnUiThread { videoImagePreferences(rule.activity).nextUpMinimalEnabled = true }
        var awaitingDecoderAck = true // Deliberately not Compose state: only the poll can observe completion.
        val chrome = PlayerChromeState().apply {
            osd.expand()
            osdModel.value = object : PlayerOsdSpec {
                override fun hasPendingSeek() = awaitingDecoderAck
                @Composable override fun Content(visible: Boolean, panel: UnifiedPlayerPanel) = Unit
            }
        }
        val fixture = mount(metadata().copy(nextUpConfig = NextUpConfig(
            enabled = true, autoplay = true, stillWatchingEvery = 1, minimal = true,
        )), chrome = chrome)
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            BackgroundPlaybackSession.recordAutomaticNextUp(successor)
            fixture.clock.position = 120_000L
            fixture.clock.ended = true
            fixture.clock.playing = false
            BackgroundPlaybackSession.tick()
        }
        val start = rule.mainClock.currentTime
        rule.mainClock.advanceTimeBy(NEXT_UP_CONFIRMATION_TIMEOUT_MS / 2)
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue("An unacknowledged decoder seek retains the OSD", chrome.osd.visible.value)
            assertTrue(BackgroundPlaybackSession.snapshot.value.active)
        }
        rule.onNodeWithText(rule.activity.getString(R.string.next_up_continue)).assertDoesNotExist()
        // No tick, model publication, seek-version change or observable state mutation follows.
        rule.runOnIdle { awaitingDecoderAck = false }
        rule.mainClock.advanceTimeBy(300L)
        rule.waitForIdle()
        rule.runOnIdle {
            assertFalse("The live decoder guard must be rechecked even with a stopped clock", chrome.osd.visible.value)
            assertTrue(BackgroundPlaybackSession.snapshot.value.active)
            assertEquals(120_000L, BackgroundPlaybackSession.snapshot.value.status.positionMs)
            assertTrue(fixture.advances.isEmpty())
        }
        rule.onNodeWithText(rule.activity.getString(R.string.next_up_continue)).assertIsDisplayed()
        // This is after the original deadline but before a deadline restarted on acknowledgement.
        rule.mainClock.advanceTimeBy(NEXT_UP_CONFIRMATION_TIMEOUT_MS + 1_000L - (rule.mainClock.currentTime - start))
        rule.waitForIdle()
        rule.runOnIdle {
            assertFalse("Showing the delayed prompt must not extend its original deadline",
                BackgroundPlaybackSession.snapshot.value.active)
            assertTrue(fixture.advances.isEmpty())
        }
    }
}
