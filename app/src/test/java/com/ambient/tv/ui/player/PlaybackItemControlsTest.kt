package com.ambient.tv.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.ambient.tv.companion.CompanionQueuePlayback
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
        val seeks = mutableListOf<Long>()
        override fun status() = PlaybackStatus(positionMs = position, durationMs = 120_000, playing = playing)
        override fun canSeekNow() = seekable
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun seekTo(positionMs: Long) { seeks += positionMs; position = positionMs }
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
        }
    }

    @After fun tearDown() {
        rule.runOnUiThread {
            CompanionQueuePlayback.pending = false
            videoImagePreferences(rule.activity).pauseRewindSeconds = 0
            BackgroundPlaybackSession.stop()
            BackgroundPlaybackSession.takeCompleted()
        }
    }

    private fun mount(
        metadata: PlaybackMetadata = metadata(),
        clock: Clock = Clock(),
        onTimelineSeek: ((Long) -> Unit)? = null,
        hasPrevious: Boolean = false,
    ): Fixture {
        val fixture = Fixture(metadata, clock).apply { previousAvailable.value = hasPrevious }
        rule.runOnUiThread {
            BackgroundPlaybackSession.acquire(rule.activity, metadata, "fixture", Any()) { clock }
        }
        rule.setContent {
            CompositionLocalProvider(
                LocalPlayerChangingItem provides fixture.changing.value,
                LocalHandheldUi provides true,
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
            requireNotNull(fixture.controls?.previous).invoke()
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

    @Test fun mediaPreviousUsesHistoryAndFallsBackToStartOverWhenNoneIsAvailable() {
        val fixture = mount()
        rule.runOnIdle { fixture.focus.requestFocus() }
        rule.onRoot().performKeyInput { pressKey(Key.MediaPrevious) }
        rule.runOnIdle {
            assertEquals(listOf(0L), fixture.clock.seeks)
            assertEquals(0, fixture.previousCalls)
            fixture.previousAvailable.value = true
        }
        rule.onRoot().performKeyInput { pressKey(Key.MediaPrevious) }
        rule.runOnIdle {
            assertEquals(listOf(0L), fixture.clock.seeks)
            assertEquals(1, fixture.previousCalls)
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

    @Test fun liveVideoTrailersAndAudioDoNotPublishVideoItemControls() {
        val fixture = mount(metadata().copy(isLive = true))
        rule.runOnIdle {
            assertNull(fixture.controls)
            fixture.metadata.value = metadata().copy(isTrailer = true)
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
}
