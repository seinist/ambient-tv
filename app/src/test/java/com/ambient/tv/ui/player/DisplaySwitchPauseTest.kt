package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AppPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class DisplaySwitchPauseTest {
    private class FakePlayer(var playing: Boolean = true, var position: Long = 60_000) : DisplaySwitchPlayer {
        val events = mutableListOf<String>()
        override fun wantsToPlay() = playing
        override fun positionMs() = position
        override fun play() { playing = true; events += "play" }
        override fun pause() { playing = false; events += "pause" }
        override fun seekTo(positionMs: Long) { position = positionMs; events += "seek:$positionMs" }
        override fun setOutputMuted(muted: Boolean) { events += if (muted) "mute" else "unmute" }
    }

    @Test fun holdOnlyForARealModeChangeWithADelay() {
        assertEquals(DisplaySwitchHold.NONE, displaySwitchHoldFor("a", "a", 3_000, isLive = false, timeshift = false))
        assertEquals(DisplaySwitchHold.NONE, displaySwitchHoldFor("b", "a", 0, isLive = false, timeshift = false))
        assertEquals(DisplaySwitchHold.NONE, displaySwitchHoldFor(null, "a", 3_000, isLive = false, timeshift = false))
        assertEquals(DisplaySwitchHold.PAUSE, displaySwitchHoldFor("b", "a", 500, isLive = false, timeshift = false))
        assertEquals(DisplaySwitchHold.PAUSE, displaySwitchHoldFor("b", null, 500, isLive = false, timeshift = false))
    }

    @Test fun liveTvPausesWithTimeshiftAndOnlyMutesWithout() {
        assertEquals(DisplaySwitchHold.PAUSE, displaySwitchHoldFor("b", "a", 3_000, isLive = true, timeshift = true))
        assertEquals(DisplaySwitchHold.MUTE, displaySwitchHoldFor("b", "a", 3_000, isLive = true, timeshift = false))
        assertEquals(DisplaySwitchHold.NONE, displaySwitchHoldFor("a", "a", 3_000, isLive = true, timeshift = false))
    }

    @Test fun earlyPositionsRestartButLiveIsNeverRewound() {
        assertEquals(0L, displaySwitchResumeSeekMs(2_999, isLive = false))
        assertNull(displaySwitchResumeSeekMs(3_000, isLive = false))
        assertNull(displaySwitchResumeSeekMs(500, isLive = true))
    }

    @Test fun valuesSnapToHalfSecondStepsBetweenOffAndTwentySeconds() {
        assertEquals(0, AppPreferences.normalizeDisplaySwitchPauseMs(-5))
        assertEquals(0, AppPreferences.normalizeDisplaySwitchPauseMs(249))
        assertEquals(500, AppPreferences.normalizeDisplaySwitchPauseMs(250))
        assertEquals(3_000, AppPreferences.normalizeDisplaySwitchPauseMs(3_100))
        assertEquals(20_000, AppPreferences.normalizeDisplaySwitchPauseMs(99_000))
        assertEquals(41, (0..AppPreferences.DISPLAY_SWITCH_PAUSE_MAX_MS step AppPreferences.DISPLAY_SWITCH_PAUSE_STEP_MS).count())
        assertEquals(3_000, AppPreferences.DISPLAY_SWITCH_PAUSE_RECOMMENDED_MS)
        assertEquals("3.0", displaySwitchPauseSeconds(3_000, Locale.ROOT))
        assertEquals("0,5", displaySwitchPauseSeconds(500, Locale.GERMANY))
    }

    @Test fun transitionWindowCoversConfirmationPlusDelay() {
        assertEquals(0L, displaySwitchTransitionExtraMs(0))
        assertEquals(9_000L, displaySwitchTransitionExtraMs(3_000))
        val window = PlaybackOutputChangeWindow(10_000)
        val key = "plex" to "1"
        window.begin(key, 0, television = true, personalOutput = false, extraMs = displaySwitchTransitionExtraMs(20_000))
        assertTrue(window.active(key, 35_999))
        assertFalse(window.active(key, 36_000))
        // A later plain request for the same title must not shorten the widened window.
        window.begin(key, 1_000, television = true, personalOutput = false)
        assertTrue(window.active(key, 35_999))
    }

    @Test fun visibleOnlyWhenTheAppCanSwitchModes() {
        assertFalse(displaySwitchPauseVisible(1, "start_stop", AppPreferences.DISPLAY_MODE_AUTO_CONTENT))
        assertFalse(displaySwitchPauseVisible(4, AppPreferences.DISPLAY_REFRESH_OFF, "system"))
        assertTrue(displaySwitchPauseVisible(4, AppPreferences.DISPLAY_REFRESH_OFF, "3840x2160@50.000"))
        assertTrue(displaySwitchPauseVisible(4, "start", "system"))
    }

    @Test fun pausesRequestsWaitsForConfirmationThenDelayAndResumes() = runTest {
        val player = FakePlayer()
        val confirmed = CompletableDeferred<Boolean>()
        var requestedAt = -1L
        val result = async {
            holdForDisplaySwitch(player, DisplaySwitchHold.PAUSE, 3_000, isLive = false,
                request = { requestedAt = currentTime; player.events += "request" },
                awaitConfirmed = { timeout -> withTimeoutOrNull(timeout) { confirmed.await() } ?: false },
                stillOwned = { true })
        }
        runCurrent()
        assertEquals(listOf("pause", "request"), player.events)
        assertEquals(0L, requestedAt)
        advanceTimeBy(1_200); confirmed.complete(true); runCurrent()
        advanceTimeBy(2_900); runCurrent()
        assertEquals("still paused during the delay", listOf("pause", "request"), player.events)
        advanceUntilIdle()
        assertEquals(DisplaySwitchOutcome.RESUMED, result.await())
        assertEquals(listOf("pause", "request", "play"), player.events)
        assertTrue(currentTime in 4_200L..4_300L)
    }

    @Test fun missingConfirmationResumesAfterTimeoutPlusDelay() = runTest {
        val player = FakePlayer()
        val result = async {
            holdForDisplaySwitch(player, DisplaySwitchHold.PAUSE, 500, isLive = false, request = {},
                awaitConfirmed = { timeout -> delay(timeout); false }, stillOwned = { true })
        }
        advanceTimeBy(6_400); runCurrent()
        assertFalse(player.playing)
        advanceUntilIdle()
        assertEquals(DisplaySwitchOutcome.RESUMED_AFTER_TIMEOUT, result.await())
        assertTrue(player.playing)
        assertTrue(currentTime in 6_500L..6_600L)
    }

    @Test fun earlyPositionRestartsFromTheBeginning() = runTest {
        val player = FakePlayer(position = 1_200)
        holdForDisplaySwitch(player, DisplaySwitchHold.PAUSE, 500, isLive = false, request = {},
            awaitConfirmed = { true }, stillOwned = { true })
        assertEquals(listOf("pause", "seek:0", "play"), player.events)
    }

    @Test fun userPlayOrSeekCancelsTheAutoResume() = runTest {
        val player = FakePlayer()
        val played = async {
            holdForDisplaySwitch(player, DisplaySwitchHold.PAUSE, 5_000, isLive = false, request = {},
                awaitConfirmed = { true }, stillOwned = { true })
        }
        advanceTimeBy(1_000); player.playing = true
        assertEquals(DisplaySwitchOutcome.USER_TOOK_OVER, played.await())
        assertEquals(listOf("pause"), player.events)
        assertTrue(currentTime < 2_000)

        val seeker = FakePlayer()
        val seeked = async {
            holdForDisplaySwitch(seeker, DisplaySwitchHold.PAUSE, 5_000, isLive = false, request = {},
                awaitConfirmed = { true }, stillOwned = { true })
        }
        advanceTimeBy(1_000); seeker.position += 30_000
        assertEquals(DisplaySwitchOutcome.USER_TOOK_OVER, seeked.await())
        assertFalse(seeker.playing)
    }

    @Test fun titleChangeCancelsWithoutResumingAndOtherCancellationResumesAtOnce() = runTest {
        val player = FakePlayer()
        var owned = true
        val job = launch {
            holdForDisplaySwitch(player, DisplaySwitchHold.PAUSE, 5_000, isLive = false, request = {},
                awaitConfirmed = { true }, stillOwned = { owned })
        }
        advanceTimeBy(1_000); owned = false; job.cancel(); advanceUntilIdle()
        assertEquals(listOf("pause"), player.events)

        val leaving = FakePlayer()
        val other = launch {
            holdForDisplaySwitch(leaving, DisplaySwitchHold.PAUSE, 5_000, isLive = false, request = {},
                awaitConfirmed = { true }, stillOwned = { true })
        }
        advanceTimeBy(1_000); other.cancel(); advanceUntilIdle()
        assertEquals(listOf("pause", "play"), leaving.events)
    }

    @Test fun liveWithoutTimeshiftMutesAndUnmutesWithoutPausing() = runTest {
        val player = FakePlayer(position = 1_000)
        val outcome = holdForDisplaySwitch(player, DisplaySwitchHold.MUTE, 3_000, isLive = true,
            request = { player.events += "request" }, awaitConfirmed = { true }, stillOwned = { true })
        assertEquals(DisplaySwitchOutcome.RESUMED, outcome)
        assertEquals(listOf("mute", "request", "unmute"), player.events)
    }

    @Test fun alreadyPausedOrNoHoldOnlyRequests() = runTest {
        val paused = FakePlayer(playing = false)
        assertEquals(DisplaySwitchOutcome.NOT_HELD, holdForDisplaySwitch(paused, DisplaySwitchHold.PAUSE, 3_000, false,
            request = { paused.events += "request" }, awaitConfirmed = { true }, stillOwned = { true }))
        assertEquals(listOf("request"), paused.events)
        val none = FakePlayer()
        assertEquals(DisplaySwitchOutcome.NOT_HELD, holdForDisplaySwitch(none, DisplaySwitchHold.NONE, 3_000, false,
            request = { none.events += "request" }, awaitConfirmed = { true }, stillOwned = { true }))
        assertEquals(listOf("request"), none.events)
    }
}
