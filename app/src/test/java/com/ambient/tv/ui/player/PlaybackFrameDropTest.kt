package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackFrameDropTest {
    @Test fun ratioUsesRenderedFramesRatherThanTotal() {
        val before = FrameDropReading(10, 100, playing = true)
        val after = FrameDropReading(20, 140, playing = true)
        assertEquals(25.0, frameDropWindowPercent(before, after)!!, 0.001)
        assertEquals(20.0, frameDropPercent(10, 50.0)!!, 0.001)
    }
    @Test fun missingZeroAndResetCountersDoNotInventPercentages() {
        assertNull(frameDropPercent(null, 50.0))
        assertNull(frameDropPercent(0, 0.0))
        assertNull(frameDropPercent(1, Double.NaN))
        assertNull(frameDropWindowPercent(FrameDropReading(10, 100, playing = true), FrameDropReading(1, 20, playing = true)))
        assertNull(frameDropWindowPercent(FrameDropReading(0, 100, playing = true), FrameDropReading(1, 20, playing = true)))
        assertEquals(0.0, frameDropPercent(0, 50.0)!!, 0.001)
    }
    @Test fun mpvEstimateExcludesPausesAndSeeks() {
        val before = FrameDropReading(10, positionMs = 1000, playing = true, fps = 50.0, estimated = true)
        val after = before.copy(dropped = 20, positionMs = 2000)
        assertEquals(25.0, frameDropWindowPercent(before, after)!!, 0.001)
        assertNull(frameDropWindowPercent(before, after.copy(playing = false)))
        assertNull(frameDropWindowPercent(before, after.copy(positionMs = 1000)))
        assertNull(frameDropWindowPercent(before, after.copy(positionMs = 10000)))
        assertNull(frameDropWindowPercent(before, after.copy(positionMs = 0)))
    }
}
