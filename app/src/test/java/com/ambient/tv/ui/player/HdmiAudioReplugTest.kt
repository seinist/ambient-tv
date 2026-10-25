package com.ambient.tv.ui.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HdmiAudioReplugTest {
    private class FakeWatch(override val minimumWaitMs: Long = 0L) : HdmiAudioWatch {
        val tracker = HdmiAudioPlugTracker()
        val events = mutableListOf<String>()
        override fun start() { events += "start" }
        override suspend fun awaitReplug(timeoutMs: Long) = tracker.awaitReplug(timeoutMs)
        override fun stop() { events += "stop" }
    }

    private class FakePlayer(var playing: Boolean = true) : DisplaySwitchPlayer {
        val events = mutableListOf<String>()
        override fun wantsToPlay() = playing
        override fun positionMs() = 60_000L
        override fun play() { playing = true; events += "play" }
        override fun pause() { playing = false; events += "pause" }
        override fun seekTo(positionMs: Long) { events += "seek" }
        override fun setOutputMuted(muted: Boolean) { events += if (muted) "mute" else "unmute" }
        override fun reopenAudioOutput() { events += "reopen-audio" }
    }

    @Test fun stickyInitialBroadcastIsNoReplug() = runTest {
        val tracker = HdmiAudioPlugTracker()
        tracker.onPlugState(plugged = true, initialSticky = true)
        assertFalse(tracker.repluggedSeen)
        assertFalse("never unplugged: no wait", tracker.awaitReplug(5_000))
        assertEquals(0L, currentTime)
    }

    @Test fun waitsForThePlugAfterAnUnplug() = runTest {
        val tracker = HdmiAudioPlugTracker()
        tracker.onPlugState(plugged = false, initialSticky = false)
        val result = async { tracker.awaitReplug(5_000) }
        advanceTimeBy(1_500); runCurrent()
        assertTrue(result.isActive)
        tracker.onPlugState(plugged = true, initialSticky = false)
        runCurrent()
        assertTrue(result.await())
    }

    @Test fun replugTimesOut() = runTest {
        val tracker = HdmiAudioPlugTracker()
        tracker.onPlugState(plugged = false, initialSticky = false)
        assertFalse(tracker.awaitReplug(5_000))
        assertEquals(5_000L, currentTime)
    }

    @Test fun cecPlaybackTypeIsAnHdmiSource() {
        assertTrue(hdmiDeviceTypeIsSource("4"))
        assertTrue(hdmiDeviceTypeIsSource("0, 4"))
        assertFalse(hdmiDeviceTypeIsSource("0"))
        assertFalse(hdmiDeviceTypeIsSource("14"))
        assertFalse(hdmiDeviceTypeIsSource(null))
    }

    @Test fun sourceDevicesWaitAtLeastTwoSecondsAfterTheRequest() = runTest {
        val watch = FakeWatch(minimumWaitMs = HdmiAudioReplug.SOURCE_DEVICE_MIN_WAIT_MS)
        watch.tracker.onPlugState(plugged = true, initialSticky = false)
        val result = async { awaitHdmiAudioAfterSwitch(watch, requestedAtMs = 500, nowMs = { currentTime + 1_000 }) }
        advanceUntilIdle()
        assertTrue(result.await())
        assertEquals("2 s from the request at 500 ms, now at 1000 ms → 1.5 s", 1_500L, currentTime)
    }

    @Test fun heldSwitchReopensAudioBeforeResumingOnceHdmiAudioIsBack() = runTest {
        val player = FakePlayer()
        val watch = FakeWatch(minimumWaitMs = 2_000)
        val result = async {
            holdForDisplaySwitch(player, DisplaySwitchHold.PAUSE, 500, isLive = false,
                request = { player.events += "request"; watch.tracker.onPlugState(false, false) },
                awaitConfirmed = { true }, stillOwned = { true },
                hdmiAudio = watch, nowMs = { currentTime })
        }
        advanceTimeBy(2_500); runCurrent()
        assertEquals("waiting for the re-plug", listOf("pause", "request"), player.events)
        watch.tracker.onPlugState(true, false)
        advanceUntilIdle()
        assertEquals(DisplaySwitchOutcome.RESUMED, result.await())
        assertEquals(listOf("pause", "request", "reopen-audio", "play"), player.events)
        assertEquals(listOf("start", "stop"), watch.events)
    }

    @Test fun noUnplugMeansNoReopenAndOnlyTheMinimumWait() = runTest {
        val player = FakePlayer()
        val watch = FakeWatch(minimumWaitMs = 2_000)
        holdForDisplaySwitch(player, DisplaySwitchHold.PAUSE, 500, isLive = false, request = {},
            awaitConfirmed = { true }, stillOwned = { true }, hdmiAudio = watch, nowMs = { currentTime })
        assertEquals(listOf("pause", "play"), player.events)
        assertEquals(2_000L, currentTime)
    }

    @Test fun unheldSwitchStillReopensAudio() = runTest {
        val player = FakePlayer(playing = false)
        val watch = FakeWatch()
        val outcome = holdForDisplaySwitch(player, DisplaySwitchHold.PAUSE, 500, isLive = false,
            request = { watch.tracker.onPlugState(false, false); watch.tracker.onPlugState(true, false) },
            awaitConfirmed = { true }, stillOwned = { true }, hdmiAudio = watch, nowMs = { currentTime })
        assertEquals(DisplaySwitchOutcome.NOT_HELD, outcome)
        assertEquals(listOf("reopen-audio"), player.events)
        assertEquals(listOf("start", "stop"), watch.events)
    }

    @Test fun foreignTitleIsNotTouched() = runTest {
        val player = FakePlayer()
        val watch = FakeWatch()
        holdForDisplaySwitch(player, DisplaySwitchHold.PAUSE, 500, isLive = false,
            request = { watch.tracker.onPlugState(false, false); watch.tracker.onPlugState(true, false) },
            awaitConfirmed = { true }, stillOwned = { false }, hdmiAudio = watch, nowMs = { currentTime })
        assertEquals(listOf("pause"), player.events)
    }
}
