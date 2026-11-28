package com.ambient.tv.moonlight

import org.junit.Assert.*
import org.junit.Test

class MoonlightAvQueuePolicyTest {
    @Test fun timestampProjectionHandlesWrapAndRejectsOldClockSamples() {
        val now = 1_000_000_000L
        assertEquals(4800L, moonlightProjectAudioHead(4320L, now - 10_000_000L, 4800L, 6000L, 48000, now))
        val wrap = 0x1_0000_0000L
        assertEquals(wrap + 480L, moonlightProjectAudioHead(0, now - 10_000_000L, wrap + 480L,
            wrap + 600L, 48000, now))
        assertNull(moonlightProjectAudioHead(5000, now + 1, 5000, 6000, 48000, now))
        assertNull(moonlightProjectAudioHead(5000, now - 600_000_000L, 5000, 6000, 48000, now))
        assertNull(moonlightProjectAudioHead(10000, now, 0, 100, 48000, now))
    }

    private fun audio(now: Long, delay: Double = 75.0, underruns: Int = 0, timestamp: Boolean = true) =
        MoonlightAudioMetrics(rate = 48000, estimatedQueuedMs = delay, nativeQueuedMs = 10,
            underruns = underruns, sampledAtNanos = now, timestampBacked = timestamp)
    private fun video(now: Long, delay: Double = 10.0) =
        MoonlightVideoMetrics(fps = 60, recentPresentationMs = delay, presentationSampleNanos = now)

    @Test fun stableAudioExcessReducesBudgetOnlyWithinBound() {
        val policy = MoonlightAvQueuePolicy()
        for (i in 1..30) {
            val now = i * 1_000_000_000L
            val result = policy.sample(now, audio(now), video(now), 0)
            assertEquals(75.0, result.differenceMs!!, .01)
            assertTrue(result.reductionMs in 0..20)
            if (i < 3) assertEquals(0, result.reductionMs)
            if (i >= 12) assertEquals(20, result.reductionMs)
        }
        val now = 31_000_000_000L
        assertEquals(0, policy.sample(now, audio(now, underruns = 1), video(now), 0).reductionMs)
        assertEquals(0, policy.sample(now + 1, audio(now + 1, underruns = 1), video(now + 1), 0).reductionMs)
    }

    @Test fun noCorrectionForStaleUnreliableOrVideoBackloggedSamples() {
        val policy = MoonlightAvQueuePolicy()
        for (i in 1..10) {
            val now = i * 1_000_000_000L
            assertEquals(0, policy.sample(now, audio(now, timestamp = false), video(now), 0).reductionMs)
            assertEquals(0, policy.sample(now, audio(now), video(now), 8).reductionMs)
            assertNull(policy.sample(now, audio(now - 1_100_000_000L), video(now), 0).differenceMs)
        }
    }

    @org.junit.Test fun pcmWriteAlignmentAndConservativeQueueProjection() {
        org.junit.Assert.assertTrue(moonlightValidPcmWrite(12, 24, 6))
        org.junit.Assert.assertFalse(moonlightValidPcmWrite(13, 24, 6))
        org.junit.Assert.assertFalse(moonlightValidPcmWrite(30, 24, 6))
        org.junit.Assert.assertEquals(30.0, moonlightEstimateQueuedAudio(30.0, 10_000_000L, false), 0.0)
        org.junit.Assert.assertEquals(20.0, moonlightEstimateQueuedAudio(30.0, 10_000_000L, true), 0.0)
    }
    @org.junit.Test fun timestampCannotUnderestimateObservedQueue() {
        org.junit.Assert.assertEquals(20.0, moonlightConservativeAudioQueue(20.0, 960, 960, 48000), 0.0)
        org.junit.Assert.assertEquals(20.0, moonlightConservativeAudioQueue(10.0, 960, 0, 48000), 0.0)
    }
}
