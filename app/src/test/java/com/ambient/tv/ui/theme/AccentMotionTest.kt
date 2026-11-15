package com.ambient.tv.ui.theme

import androidx.compose.runtime.MonotonicFrameClock
import com.ambient.tv.ui.performance.PerformanceMode
import com.ambient.tv.ui.performance.PerformanceProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
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
class AccentMotionTest {
    @Test fun unknownOrMissingPreferencesKeepDecorationOff() {
        assertEquals("off", AccentMotionMode.DEFAULT)
        assertEquals(AccentMotionMode.OFF, AccentMotionMode.fromKey(null))
        assertEquals(AccentMotionMode.OFF, AccentMotionMode.fromKey("future-mode"))
        assertEquals(AccentMotionMode.OFF, AccentMotionMode.fromKey(""))
        for (mode in AccentMotionMode.entries) {
            assertEquals(mode, AccentMotionMode.fromKey(mode.key))
        }
    }

    @Test fun onlyExplicitMaximumInterfaceEnablesDecorationRegardlessOfPlaybackMode() {
        for (uiMode in PerformanceMode.entries) {
            for (playbackMode in PerformanceMode.entries) {
                for (lowEnd in listOf(false, true)) {
                    val profile = PerformanceProfile.resolve(uiMode, lowEnd, playbackMode)
                    assertFalse(accentMotionAllowed(AccentMotionMode.OFF, profile))
                    for (motion in listOf(AccentMotionMode.FOCUS, AccentMotionMode.AMBIENT)) {
                        assertEquals("$motion with UI=$uiMode, playback=$playbackMode, lowEnd=$lowEnd",
                            uiMode == PerformanceMode.MAXIMUM, accentMotionAllowed(motion, profile))
                    }
                }
            }
        }
    }

    @Test fun everyStyleHasABoundedFiniteGestureAndAQuieterAmbientRepeat() {
        for (style in AccentStyle.entries) {
            val duration = accentMotionDurationMs(style)
            assertTrue("$style must leave a long ambient rest", accentMotionPeriodMs(style) > duration * 2)
            for (elapsed in listOf(Long.MIN_VALUE, -1L, 0L, duration, Long.MAX_VALUE)) {
                assertEquals("$style at $elapsed", AccentMotionFrame.Rest, accentMotionFrame(style, elapsed))
            }
            for (elapsed in 1L until duration step 13L) {
                val focus = accentMotionFrame(style, elapsed)
                val ambient = accentMotionFrame(style, elapsed, ambientRepeat = true)
                assertTrue("$style phase at $elapsed", focus.phase.isFinite() && focus.phase in 0f..1f)
                assertTrue("$style strength at $elapsed", focus.strength.isFinite() && focus.strength in 0f..1f)
                assertEquals(focus.phase, ambient.phase, 0f)
                assertTrue("$style ambient at $elapsed", ambient.strength in 0f..0.6f)
                assertTrue(ambient.strength <= focus.strength)
            }
            assertEquals("$style focus peak", 1f, accentMotionFrame(style, duration / 2).strength, 0.0001f)
            assertEquals("$style ambient peak", 0.6f,
                accentMotionFrame(style, duration / 2, ambientRepeat = true).strength, 0.0001f)
        }
    }

    /** Count requests as well as delivered frames, so hiding a drawing alone cannot pass. */
    private class FakeFrameClock(private val scope: TestScope) : MonotonicFrameClock {
        var requests = 0
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            requests++
            delay(16L)
            return onFrame(scope.testScheduler.currentTime * 1_000_000L)
        }
    }

    private class Driver(
        private val scope: TestScope,
        private val mode: AccentMotionMode = AccentMotionMode.FOCUS,
        private val style: AccentStyle = AccentStyle.CHROME,
    ) {
        val clock = FakeFrameClock(scope)
        var frame = AccentMotionFrame.Rest
        var nonRestFrames = 0
        var active = true
        var interacting = false
        var durationScale = 1f

        fun start() = scope.launch {
            withContext(clock) {
                runAccentMotion(
                    mode = mode,
                    style = style,
                    setFrame = {
                        frame = it
                        if (it.strength > 0f) nonRestFrames++
                    },
                    nowMs = { scope.testScheduler.currentTime },
                    interacting = { interacting },
                    active = { active },
                    awaitActive = { while (!active) delay(25L) },
                    durationScale = { durationScale },
                )
            }
        }
    }

    @Test fun offReturnsWithoutRequestingAFrame() = runTest {
        val d = Driver(this, mode = AccentMotionMode.OFF)
        val job = d.start()
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(0, d.clock.requests)
        assertEquals(AccentMotionFrame.Rest, d.frame)
    }

    @Test fun focusWaitsForAQuietTargetThenSettlesAndReleasesTheFrameClock() = runTest {
        val d = Driver(this)
        val job = d.start()
        advanceTimeBy(300L); runCurrent()
        assertEquals(0, d.clock.requests)
        assertEquals(AccentMotionFrame.Rest, d.frame)
        advanceTimeBy(350L); runCurrent()
        assertTrue(d.frame.strength > 0f)
        advanceTimeBy(2_000L); runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(AccentMotionFrame.Rest, d.frame)
        val requests = d.clock.requests
        advanceTimeBy(20_000L); runCurrent()
        assertEquals(requests, d.clock.requests)
    }

    @Test fun inputBlocksFramesAndAnAlreadyQueuedFrameCannotAdvanceTheGesture() = runTest {
        val d = Driver(this)
        d.interacting = true
        val job = d.start()
        advanceTimeBy(2_000L); runCurrent()
        assertEquals(0, d.clock.requests)

        d.interacting = false
        advanceTimeBy(700L); runCurrent()
        assertTrue(d.frame.strength > 0f)
        val requests = d.clock.requests
        val nonRestFrames = d.nonRestFrames
        d.interacting = true
        advanceTimeBy(1_000L); runCurrent()
        assertEquals("A pending frame must recheck input before writing motion", nonRestFrames, d.nonRestFrames)
        assertEquals(requests, d.clock.requests)
        assertEquals(AccentMotionFrame.Rest, d.frame)

        d.interacting = false
        advanceTimeBy(200L); runCurrent()
        assertEquals("A rapid focus hop must keep its quiet delay", requests, d.clock.requests)
        advanceTimeBy(500L); runCurrent()
        assertTrue(d.clock.requests > requests)
        assertTrue(d.frame.strength > 0f)
        job.cancelAndJoin()
    }

    @Test fun inactiveWindowsNeverRequestFramesAndResumingDoesNotJumpAhead() = runTest {
        val d = Driver(this, mode = AccentMotionMode.AMBIENT)
        d.active = false
        val job = d.start()
        advanceTimeBy(10_000L); runCurrent()
        assertEquals(0, d.clock.requests)
        d.active = true
        advanceTimeBy(700L); runCurrent()
        assertTrue(d.frame.strength > 0f)

        val requests = d.clock.requests
        val nonRestFrames = d.nonRestFrames
        d.active = false
        advanceTimeBy(20_000L); runCurrent()
        assertEquals(requests, d.clock.requests)
        assertEquals(nonRestFrames, d.nonRestFrames)
        assertEquals(AccentMotionFrame.Rest, d.frame)

        d.active = true
        advanceTimeBy(200L); runCurrent()
        assertEquals(requests, d.clock.requests)
        advanceTimeBy(500L); runCurrent()
        assertTrue(d.clock.requests > requests)
        assertTrue("Resuming starts a finite gesture rather than a stale elapsed time", d.frame.strength > 0f)
        job.cancelAndJoin()
    }

    @Test fun removedAnimationsAndInvalidDurationScalesKeepTheClockIdle() = runTest {
        for (disabledScale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val d = Driver(this, mode = AccentMotionMode.AMBIENT)
            d.durationScale = disabledScale
            val job = d.start()
            advanceTimeBy(1_000L); runCurrent()
            assertEquals("scale=$disabledScale", 0, d.clock.requests)
            assertEquals(AccentMotionFrame.Rest, d.frame)
            d.durationScale = 1f
            advanceTimeBy(700L); runCurrent()
            assertTrue(d.frame.strength > 0f)

            val requests = d.clock.requests
            val nonRestFrames = d.nonRestFrames
            d.durationScale = disabledScale
            advanceTimeBy(1_000L); runCurrent()
            assertEquals(requests, d.clock.requests)
            assertEquals(nonRestFrames, d.nonRestFrames)
            assertEquals(AccentMotionFrame.Rest, d.frame)
            job.cancelAndJoin()
        }
    }

    @Test fun ambientRestsForSecondsWithoutRequestingFramesThenRepeats() = runTest {
        val d = Driver(this, mode = AccentMotionMode.AMBIENT, style = AccentStyle.LIGHT)
        val job = d.start()
        advanceTimeBy(1_500L); runCurrent()
        assertFalse(job.isCompleted)
        assertTrue(d.nonRestFrames > 0)
        assertEquals(AccentMotionFrame.Rest, d.frame)
        val requests = d.clock.requests
        advanceTimeBy(9_000L); runCurrent()
        assertEquals(requests, d.clock.requests)
        assertEquals(AccentMotionFrame.Rest, d.frame)
        advanceTimeBy(4_000L); runCurrent()
        assertTrue(d.clock.requests > requests)
        assertFalse(job.isCompleted)
        job.cancelAndJoin()
    }

    @Test fun cancellingAnActiveGestureRestoresTheStaticMaterial() = runTest {
        val d = Driver(this, mode = AccentMotionMode.AMBIENT)
        val job = d.start()
        advanceTimeBy(650L); runCurrent()
        assertTrue(d.frame.strength > 0f)
        job.cancelAndJoin()
        assertEquals(AccentMotionFrame.Rest, d.frame)
        val requests = d.clock.requests
        advanceTimeBy(5_000L); runCurrent()
        assertEquals(requests, d.clock.requests)
    }
}
