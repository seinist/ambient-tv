package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekKeyAccelerationTest {
    private val duration = 6_000_000L

    @Test fun nativeRepeatsAndSyntheticPairsHaveTheSameAcceleration() {
        val native = SeekKeyAcceleration()
        val pairs = SeekKeyAcceleration()
        for (index in 0..24) {
            val time = if (index == 0) 0L else 400L + (index - 1) * 50L
            val nativeStep = native.step(10_000, 22, time, index, duration)
            // Recorded emulator stream: native repeats first, then repeatCount = 0 pairs.
            val pairStep = pairs.step(10_000, 22, time, if (index <= 4) index else 0, duration)
            assertEquals(acceleratedSeekStepMs(10_000, index, duration), nativeStep)
            assertEquals(nativeStep, pairStep)
        }
    }

    @Test fun rapidPairsAccelerateWithoutAnyNativeRepeats() {
        val acceleration = SeekKeyAcceleration()
        for (index in 0..12) {
            assertEquals(acceleratedSeekStepMs(10_000, index, duration),
                acceleration.step(10_000, 22, index * 40L, 0, duration))
        }
    }

    @Test fun separatedTapsStayAtBaseSpeed() {
        val acceleration = SeekKeyAcceleration()
        for (index in 0..12) {
            assertEquals(10_000L, acceleration.step(10_000, 22, index * 250L, 0, duration))
        }
    }

    @Test fun directionChangeAndPauseRestartSlowly() {
        val acceleration = SeekKeyAcceleration()
        repeat(12) { acceleration.step(60_000, 22, it * 40L, 0, duration) }
        assertEquals(60_000L, acceleration.step(60_000, 21, 480, 0, duration))
        repeat(12) { acceleration.step(60_000, 21, 520L + it * 40L, 0, duration) }
        assertEquals(60_000L, acceleration.step(60_000, 21, 1_300, 0, duration))
    }

    @Test fun accelerationKeepsDurationCap() {
        val acceleration = SeekKeyAcceleration()
        repeat(100) { acceleration.step(10_000, 22, it * 40L, 0, duration) }
        assertEquals(duration / 30, acceleration.step(10_000, 22, 4_000, 0, duration))
    }
}
