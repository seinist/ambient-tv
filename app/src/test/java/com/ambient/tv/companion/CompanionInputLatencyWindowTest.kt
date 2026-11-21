package com.ambient.tv.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionInputLatencyWindowTest {
    @Test fun p95HighlightsIntermittentJitterWithoutHoldingUnboundedSamples() {
        val window = CompanionInputLatencyWindow(capacity = 20)
        assertNull(window.p95Ms())
        repeat(18) { window.record(12L) }
        repeat(2) { window.record(110L) }
        assertEquals(110L, window.p95Ms())
        repeat(20) { window.record(22L) }
        assertEquals(22L, window.p95Ms())
    }

    @Test fun extremeOrInvalidAgesAreBoundedAndQuantileStable() {
        val window = CompanionInputLatencyWindow(capacity = 4)
        window.record(-90L)
        window.record(2L)
        window.record(3L)
        window.record(90_000L)
        assertEquals(60_000L, window.p95Ms())
        assertEquals(0L, CompanionInputLatencyWindow(capacity = 1).apply {
            record(-1L)
        }.p95Ms())
    }
}
