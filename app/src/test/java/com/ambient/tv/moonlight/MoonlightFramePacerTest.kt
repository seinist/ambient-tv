package com.ambient.tv.moonlight

import org.junit.Assert.*
import org.junit.Test

class MoonlightFramePacerTest {
    @Test fun latencyDoesNotScheduleFutureFrames() {
        val pacer = MoonlightFramePacer("latency", 60)
        assertEquals(1000000000L, pacer.presentationTime(1000000000L))
        assertEquals(1001000000L, pacer.presentationTime(1001000000L))
        assertTrue(pacer.shouldDrain(1000000L, 1000000000L))
    }
    @Test fun smoothingNeverSchedulesBeyondTwoIntervals() {
        val pacer = MoonlightFramePacer("smooth", 60)
        repeat(100) {
            val now = 1000000000L + it * 1000000L
            assertTrue(pacer.presentationTime(now) in now..(now + 33333334L))
        }
        assertFalse(pacer.shouldDrain(1000000L, 1001000000L))
        assertTrue(pacer.shouldDrain(1000000L, 1100000000L))
    }
    @Test fun p95IsBoundedAndIgnoresInvalidSamples() {
        val h = MoonlightLatencyHistogram()
        assertNull(h.p95Ms())
        h.recordUs(-1)
        repeat(95) { h.recordUs(1000) }
        repeat(5) { h.recordUs(16000) }
        assertEquals(1, h.p95Ms())
    }
}
