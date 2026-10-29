package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackFrameDropHistoryTest {
    private val start = FrameDropReading(
        dropped = 0, rendered = 0, positionMs = 0, playing = true,
        fps = 100.0, realtimeMs = 1_000,
    )

    @Test fun historyKeepsRawCounterRatiosInsteadOfSmoothedCadenceDeficitsOrCappedValues() {
        val history = FrameDropHistory()
        assertNull(history.record(start))
        val healthy = start.copy(rendered = 50, positionMs = 1_000, realtimeMs = 2_000)
        val first = history.record(healthy)!!
        assertEquals(50.0, first.deficitPercent!!, 0.0)
        assertEquals(0.0, history.points.last().percent!!, 0.0)

        val peak = healthy.copy(rendered = 90, dropped = 10, positionMs = 2_000, realtimeMs = 3_000)
        val raw = history.record(peak)!!
        assertEquals(20.0, history.points.last().percent!!, 0.0)
        assertNotEquals(smoothFrameDrop(first, raw)!!.counterDropPercent, history.points.last().percent)
        history.record(peak.copy(rendered = 95, dropped = 55, positionMs = 3_000, realtimeMs = 4_000))
        // Drawing clips at 20%; the underlying value and visible current percentage stay exact.
        assertEquals(90.0, history.points.last().percent!!, 0.0)
        assertFalse(history.points.last().estimated)
    }

    @Test fun pauseResumeAndSeekLeaveGapsWithoutInventingPeaksFromAccumulatedCounters() {
        val history = FrameDropHistory()
        history.record(start)
        val good = start.copy(rendered = 45, dropped = 5, positionMs = 1_000, realtimeMs = 2_000)
        history.record(good)
        history.record(good.copy(playing = false, realtimeMs = 3_000))
        history.record(good.copy(rendered = 50, dropped = 10, positionMs = 1_100, realtimeMs = 4_000))
        val resumed = good.copy(rendered = 100, dropped = 10, positionMs = 2_100, realtimeMs = 5_000)
        history.record(resumed)
        assertEquals(listOf(null, 10.0, null, null, 0.0), history.points.map { it.percent })

        // A seek can leave counter totals above the preceding poll; its generation still wins.
        val seeked = resumed.copy(rendered = 10_000, dropped = 5_000,
            positionMs = 120_000, realtimeMs = 6_000, generation = 1)
        assertNull(history.record(seeked))
        history.record(seeked.copy(rendered = 10_050, positionMs = 121_000, realtimeMs = 7_000))
        assertEquals(listOf(null, 0.0), history.points.takeLast(2).map { it.percent })
        assertEquals(10.0, history.points.mapNotNull { it.percent }.maxOrNull()!!, 0.0)
    }

    @Test fun counterResetMissingDropsAndLongSamplingGapsRemainMissingRatherThanZero() {
        val history = FrameDropHistory()
        val initial = start.copy(rendered = 100, dropped = 10)
        history.record(initial)
        history.record(initial.copy(rendered = 145, dropped = 15, realtimeMs = 2_000))
        val reset = initial.copy(rendered = 0, dropped = 0, realtimeMs = 3_000)
        assertNull(history.record(reset))
        history.record(reset.copy(rendered = 50, dropped = null, realtimeMs = 4_000))
        assertNull(history.points.last().percent)
        assertNull(history.record(reset.copy(rendered = 500, dropped = 100, realtimeMs = 15_000)))
        assertNull(history.points.last().percent)
        assertEquals(10.0, history.points.mapNotNull { it.percent }.single(), 0.0)
    }

    @Test fun estimatedHistoryUsesTheExistingMediaClockValidationAndMarksItsValues() {
        val history = FrameDropHistory()
        val initial = start.copy(rendered = null, fps = 50.0, estimated = true)
        history.record(initial)
        val good = initial.copy(dropped = 5, positionMs = 1_000, realtimeMs = 2_000)
        history.record(good)
        assertEquals(10.0, history.points.last().percent!!, 0.0)
        assertTrue(history.points.last().estimated)
        assertNull(history.record(good.copy(dropped = 20, positionMs = 60_000, realtimeMs = 3_000)))
        assertNull(history.points.last().percent)
        assertTrue(history.points.last().estimated)
    }

    @Test fun ringEvictsByElapsedTimeAndStaysBoundedAcrossLongPlayback() {
        val history = FrameDropHistory()
        for (second in 0..130) history.record(start.copy(
            rendered = second * 50L, realtimeMs = second * 1_000L, positionMs = second * 1_000L,
        ))
        assertEquals(61, history.points.size)
        assertEquals(70_000L, history.points.first().realtimeMs)
        assertEquals(130_000L, history.points.last().realtimeMs)
        history.record(start.copy(rendered = 7_000, realtimeMs = 140_000, positionMs = 140_000))
        assertEquals(80_000L, history.points.first().realtimeMs)
        assertEquals(52, history.points.size)
        assertNull(history.points.last().percent)
    }

    @Test fun aNewReaderOrNonMonotoneClockStartsWithoutAnOldMeasurementWindow() {
        val history = FrameDropHistory()
        history.record(start)
        history.record(start.copy(rendered = 40, dropped = 10, realtimeMs = 2_000))
        assertNotNull(history.points.last().percent)
        history.record(start.copy(rendered = 80, dropped = 20, realtimeMs = 500))
        assertEquals(listOf(FrameDropHistoryPoint(500, null, false)), history.points)
        val replacement = FrameDropHistory()
        assertNull(replacement.record(start.copy(rendered = 80, dropped = 20, realtimeMs = 3_000)))
        assertNull(replacement.points.single().percent)
    }
}
