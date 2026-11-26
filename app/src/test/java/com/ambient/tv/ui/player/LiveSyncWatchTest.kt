package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class LiveSyncWatchTest {
    private fun sample(i: Int, frames: Long? = 100L, sync: Double? = null, gen: Int = 1, audio: Long? = null) =
        LiveSyncSample(i * 1000L, i * 1000L, gen, frames, audio, sync)

    @Test fun advancingAudioClockWithFrozenVideoTriggersOneReopen() {
        val watch = LiveSyncWatch()
        repeat(4) { assertNull(watch.observe(sample(it, audio = it.toLong()))) }
        assertEquals(LiveSyncFault.VIDEO_STALLED, watch.observe(sample(4, audio = 4)))
        repeat(14) { assertNull(watch.observe(sample(it + 5, audio = (it + 5).toLong()))) }
    }

    @Test fun healthyVideoAndShortReceptionErrorsDoNotTrigger() {
        val watch = LiveSyncWatch()
        repeat(100) { assertNull(watch.observe(sample(it, frames = (it * 25L)))) }
        repeat(3) { assertNull(watch.observe(sample(it + 100, frames = 2500L))) }
        assertNull(watch.observe(null))
        repeat(4) { assertNull(watch.observe(sample(it + 103, frames = 2500L))) }
    }

    @Test fun mpvPersistentDriftRespectsIntentionalDelay() {
        val watch = LiveSyncWatch()
        repeat(10) { assertNull(watch.observe(sample(it, frames = null, sync = 1.0), 1000)) }
        repeat(4) { assertNull(watch.observe(sample(it + 10, frames = null, sync = 2.0), 1000)) }
        assertEquals(LiveSyncFault.CLOCK_DRIFT, watch.observe(sample(14, frames = null, sync = 2.0), 1000))
    }

    @Test fun seekOrCounterResetDoesNotBridgeObservationWindows() {
        val watch = LiveSyncWatch()
        repeat(4) { assertNull(watch.observe(sample(it))) }
        assertNull(watch.observe(sample(4, gen = 2)))
        repeat(3) { assertNull(watch.observe(sample(it + 5, gen = 2))) }
        assertEquals(LiveSyncFault.VIDEO_STALLED, watch.observe(sample(8, gen = 2)))
    }

    @Test fun persistentReceptionFailureCannotRestartIndefinitely() {
        val watch = LiveSyncWatch()
        var restarts = 0
        repeat(90) { if (watch.observe(sample(it)) != null) restarts++ }
        assertEquals(2, restarts)
        repeat(35) { assertNull(watch.observe(sample(it + 90, frames = 101L + it))) }
        repeat(3) { assertNull(watch.observe(sample(it + 125, frames = 135L))) }
        assertEquals(LiveSyncFault.VIDEO_STALLED, watch.observe(sample(128, frames = 135L)))
    }

    @Test fun audioMustActuallyAdvanceWhenRendererReportsAudioCounters() {
        val watch = LiveSyncWatch()
        repeat(30) { assertNull(watch.observe(sample(it, audio = 12))) }
    }

    @Test fun unsupportedZeroFrameCounterDoesNotCauseFalseRecovery() {
        val watch = LiveSyncWatch()
        repeat(90) { assertNull(watch.observe(sample(it, frames = 0))) }
    }

    @Test fun mpvFrozenVideoClockWithMovingAudioClockTriggersWithoutChangingAvsync() {
        val watch = LiveSyncWatch()
        repeat(4) { i ->
            assertNull(watch.observe(sample(i, frames = null, sync = 0.0).copy(videoPositionMs = 1000L)))
        }
        assertEquals(LiveSyncFault.VIDEO_STALLED,
            watch.observe(sample(4, frames = null, sync = 0.0).copy(videoPositionMs = 1000L)))
    }

    @Test fun mpvVideoAndAudioClocksAdvancingTogetherDoNotTrigger() {
        val watch = LiveSyncWatch()
        repeat(60) { i ->
            assertNull(watch.observe(sample(i, frames = null, sync = 0.0).copy(videoPositionMs = i * 1000L)))
        }
    }
}
