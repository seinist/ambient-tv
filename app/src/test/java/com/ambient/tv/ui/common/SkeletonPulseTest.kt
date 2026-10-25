package com.ambient.tv.ui.common

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkeletonPulseTest {
    private val rest = SkeletonPulseCurve.REST_ALPHA

    @Test
    fun curveStaysInRangeAndRestsEveryHalfPeriod() {
        for (ms in 0L..3_600L step 10L) {
            val a = SkeletonPulseCurve.alpha(ms)
            assertTrue("alpha $a at $ms", a in 0.279f..0.621f)
        }
        assertEquals(rest, SkeletonPulseCurve.alpha(0L), 1e-4f)
        assertEquals(rest, SkeletonPulseCurve.alpha(900L), 1e-4f)
        assertEquals(rest, SkeletonPulseCurve.alpha(3_600L), 1e-4f)
        assertEquals(0.62f, SkeletonPulseCurve.alpha(450L), 1e-3f)
        assertEquals(0.28f, SkeletonPulseCurve.alpha(1_350L), 1e-3f)
    }

    @Test
    fun timelineWaitsPausesAndSettles() {
        val t = DampedPulseTimeline()
        assertEquals(PulsePhase.WAIT, t.phase(0L, 0L, interacting = false))
        assertEquals(PulsePhase.WAIT, t.phase(399L, 0L, interacting = true))
        assertEquals(PulsePhase.PULSE, t.phase(400L, 0L, interacting = false))
        assertEquals(PulsePhase.PAUSE, t.phase(1_000L, 500L, interacting = true))
        assertEquals(PulsePhase.SETTLED, t.phase(4_000L, 3_600L, interacting = false))
        assertEquals(PulsePhase.SETTLED, t.phase(4_100L, 2_000L, interacting = true))
        assertEquals(PulsePhase.PULSE, t.phase(4_100L, 2_000L, interacting = false))
        assertEquals(PulsePhase.SETTLED, t.phase(6_000L, 1_000L, interacting = false))
    }

    /** 60 Hz clock on virtual time; counts every frame the pulse requests. */
    private class FakeFrameClock(private val scope: TestScope) : MonotonicFrameClock {
        var frames = 0
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            delay(16L)
            frames++
            return onFrame(scope.testScheduler.currentTime * 1_000_000L)
        }
    }

    private class Driver(val scope: TestScope, val timeline: DampedPulseTimeline?) {
        val clock = FakeFrameClock(scope)
        var alpha = -1f
        var interacting = false
        var active = true
        var done = false
        fun start() = scope.launch {
            withContext(clock) {
                runSkeletonPulse(
                    timeline = timeline,
                    setAlpha = { alpha = it },
                    nowMs = { scope.testScheduler.currentTime },
                    interacting = { interacting },
                    active = { active },
                    awaitActive = { while (!active) delay(50L) },
                )
            }
            done = true
        }
    }

    @Test
    fun dampedPulseIsStaticFirstThenPulsesThenSettlesAndReleasesTheClock() = runTest {
        val d = Driver(this, DampedPulseTimeline())
        val job = d.start()
        advanceTimeBy(390L); runCurrent()
        assertEquals(0, d.clock.frames)
        assertEquals(rest, d.alpha, 0f)
        advanceTimeBy(500L); runCurrent()
        assertTrue(d.clock.frames > 0)
        assertTrue(d.alpha != rest)
        advanceTimeBy(4_000L); runCurrent()
        assertTrue(d.done)
        assertEquals(rest, d.alpha, 0f)
        val frames = d.clock.frames
        advanceTimeBy(5_000L); runCurrent()
        assertEquals(frames, d.clock.frames)
        job.cancel()
    }

    @Test
    fun inputFreezesThePulseWithoutRequestingFrames() = runTest {
        val d = Driver(this, DampedPulseTimeline())
        val job = d.start()
        advanceTimeBy(1_000L); runCurrent()
        d.interacting = true
        advanceTimeBy(200L); runCurrent()
        val frozen = d.alpha
        val frames = d.clock.frames
        advanceTimeBy(1_000L); runCurrent()
        assertEquals(frames, d.clock.frames)
        assertEquals(frozen, d.alpha, 0f)
        d.interacting = false
        advanceTimeBy(300L); runCurrent()
        assertTrue(d.clock.frames > frames)
        job.cancel()
    }

    @Test
    fun inputAfterTheSettleWindowEndsThePulse() = runTest {
        val d = Driver(this, DampedPulseTimeline())
        val job = d.start()
        advanceTimeBy(1_000L); runCurrent()
        d.interacting = true
        advanceTimeBy(3_100L); runCurrent()
        assertTrue(d.done)
        assertEquals(rest, d.alpha, 0f)
        job.cancel()
    }

    @Test
    fun noFramesWhileTheAppIsNotResumed() = runTest {
        val d = Driver(this, timeline = null)
        d.active = false
        val job = d.start()
        advanceTimeBy(2_000L); runCurrent()
        assertEquals(0, d.clock.frames)
        assertFalse(d.done)
        d.active = true
        advanceTimeBy(500L); runCurrent()
        assertTrue(d.clock.frames > 0)
        d.active = false
        advanceTimeBy(100L); runCurrent()
        val frames = d.clock.frames
        advanceTimeBy(2_000L); runCurrent()
        assertEquals(frames, d.clock.frames)
        job.cancel()
    }

    @Test
    fun continuousPulseForSkeletonRowsNeverSettles() = runTest {
        val d = Driver(this, timeline = null)
        val job = d.start()
        advanceTimeBy(20_000L); runCurrent()
        assertFalse(d.done)
        assertTrue(d.clock.frames > 1_000)
        job.cancel()
    }
}
