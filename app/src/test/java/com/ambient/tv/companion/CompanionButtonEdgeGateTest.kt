package com.ambient.tv.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionButtonEdgeGateTest {
    @Test fun downIsImmediateButVeryShortUpLastsUntilCoreCanPoll() {
        val gate = CompanionButtonEdgeGate(minPressMs = 35)
        assertEquals(1, gate.update(1, 100L))
        assertEquals(1, gate.update(0, 102L))
        assertEquals(33L, gate.nextReleaseDelay(102L))
        assertEquals(1, gate.settle(120L))
        assertEquals(0, gate.settle(135L))
        assertNull(gate.nextReleaseDelay(135L))
    }

    @Test fun holdingLongerThanOneFrameReleasesWithoutExtraDelay() {
        val gate = CompanionButtonEdgeGate()
        gate.update(1, 100L)
        assertEquals(1, gate.update(1, 150L))
        assertEquals(0, gate.update(0, 151L))
        assertNull(gate.nextReleaseDelay(151L))
    }

    @Test fun simultaneousButtonsReleaseIndependently() {
        val gate = CompanionButtonEdgeGate()
        gate.update(1, 100L)
        gate.update(3, 110L)
        assertEquals(3, gate.update(0, 120L))
        assertEquals(2, gate.settle(135L))
        assertEquals(0, gate.settle(145L))
    }

    @Test fun stopClearsAllHeldStatesImmediatelyEvenBeforePulseDeadline() {
        val gate = CompanionButtonEdgeGate()
        gate.update(1, 100L)
        gate.update(0, 101L)
        gate.clear()
        assertEquals(0, gate.settle(101L))
        assertNull(gate.nextReleaseDelay(101L))
    }
}
