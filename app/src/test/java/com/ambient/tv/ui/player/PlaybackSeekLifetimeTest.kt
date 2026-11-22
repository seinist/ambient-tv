package com.ambient.tv.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackSeekStopTest.PreferencesApplication::class)
class PlaybackSeekLifetimeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Clock : PlaybackHandle {
        var position = 100_000L
        override fun status() = PlaybackStatus(positionMs = position, durationMs = 600_000)
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun release() = Unit
    }

    private class Fixture(val owner: Clock) {
        val mounted = mutableStateOf(true)
        val stateKey = mutableIntStateOf(0)
        val callbackKey = mutableStateOf("first")
        val commands = mutableListOf<Pair<String, Long>>()
        var reads = 0
        lateinit var seek: PlaybackSeekState
    }

    @After fun cleanup() {
        BackgroundPlaybackSession.stop()
        BackgroundPlaybackSession.takeCompleted()
    }

    private fun metadata(id: String) = PlaybackMetadata(id, id, source = "audit", mediaId = id)

    private fun mount(): Fixture {
        lateinit var fixture: Fixture
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread {
            val owner = BackgroundPlaybackSession.acquire(rule.activity, metadata("first"), "fixture", Any()) { Clock() }
            fixture = Fixture(owner)
        }
        rule.setContent {
            if (fixture.mounted.value) {
                val callbackKey = fixture.callbackKey.value
                val seek = rememberPlaybackSeekState(
                    key = fixture.stateKey.intValue,
                    enginePositionMs = { fixture.reads++; fixture.owner.position },
                    commit = { fixture.commands += callbackKey to it },
                    pollEnabled = false,
                )
                SideEffect { fixture.seek = seek }
            }
        }
        advance()
        return fixture
    }

    private fun advance(milliseconds: Long = 48L) {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(milliseconds)
        rule.waitForIdle()
    }

    @Test fun gaplessReuseResetsSeekStateWithoutFlushingIntoTheNextItem() {
        val fixture = mount()
        val outgoing = fixture.seek
        rule.runOnIdle {
            outgoing.seekTo(110_000)
            outgoing.seekTo(120_000)
            fixture.owner.position = 0L
            assertTrue(BackgroundPlaybackSession.continueWithNextItem("first", metadata("second")))
            fixture.callbackKey.value = "second"
        }
        advance()
        rule.runOnIdle {
            assertNotSame(outgoing, fixture.seek)
            assertEquals(0L, fixture.seek.positionMs)
            assertEquals(listOf("first" to 110_000L), fixture.commands)
            outgoing.flushPending()
            outgoing.seekTo(300_000)
            fixture.seek.seekTo(10_000)
            assertEquals(listOf("first" to 110_000L, "second" to 10_000L), fixture.commands)
        }
    }

    @Test fun leavingFullscreenStillFlushesTheCurrentItemsLastTarget() {
        val fixture = mount()
        rule.runOnIdle {
            fixture.seek.seekTo(110_000)
            fixture.seek.seekTo(120_000)
            fixture.mounted.value = false
        }
        advance()
        rule.runOnIdle {
            assertTrue(BackgroundPlaybackSession.owns(fixture.owner))
            assertEquals(listOf("first" to 110_000L, "first" to 120_000L), fixture.commands)
        }
    }

    @Test fun replacingAStateDoesNotReplaceItsDisposalCallback() {
        val fixture = mount()
        rule.runOnIdle {
            fixture.seek.seekTo(110_000)
            fixture.seek.seekTo(120_000)
            fixture.stateKey.intValue++
            fixture.callbackKey.value = "updated"
        }
        advance()
        rule.runOnIdle {
            // The same session may flush, but the outgoing state retains its own callback cell.
            assertEquals(listOf("first" to 110_000L, "first" to 120_000L), fixture.commands)
            fixture.seek.seekTo(130_000)
            assertEquals("updated" to 130_000L, fixture.commands.last())
        }
    }

    @Test fun hiddenClockPollsOnlyUntilItsPendingSeekIsAcknowledged() {
        val fixture = mount()
        val initialReads = fixture.reads
        advance(1_000)
        rule.runOnIdle {
            assertEquals(initialReads, fixture.reads)
            fixture.seek.seekTo(150_000)
        }
        advance(300)
        rule.runOnIdle {
            assertTrue(fixture.reads > initialReads + 1)
            assertTrue(fixture.seek.awaitingSeekAck)
            fixture.owner.position = 150_000
        }
        repeat(4) {
            rule.runOnUiThread { ShadowSystemClock.advanceBy(Duration.ofMillis(300)) }
            advance(300)
        }
        val acknowledgedReads = fixture.reads
        rule.runOnIdle { assertFalse(fixture.seek.awaitingSeekAck) }
        advance(1_000)
        rule.runOnIdle {
            assertEquals(acknowledgedReads, fixture.reads)
            fixture.owner.position = 300_000
            fixture.seek.seekBy(10_000, 600_000)
            assertEquals("first" to 310_000L, fixture.commands.last())
        }
    }
}
