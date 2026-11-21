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

    @Test fun twoRapidTapsRemainTwoVisiblePressesSeparatedByAReleaseFrame() {
        val gate = CompanionButtonEdgeGate(minPressMs = 35L, minGapMs = 17L)
        assertEquals(1, gate.update(1, 100L))
        assertEquals(1, gate.update(0, 110L))
        assertEquals(1, gate.update(1, 114L))
        assertEquals(1, gate.update(0, 118L))
        assertEquals(0, gate.settle(135L)) // first UP becomes visible
        assertEquals(17L, gate.nextReleaseDelay(135L))
        assertEquals(0, gate.settle(151L))
        assertEquals(1, gate.settle(152L)) // second DOWN, after its distinct UP gap
        assertEquals(1, gate.settle(186L))
        assertEquals(0, gate.settle(187L)) // second UP after a full core-visible pulse
        assertNull(gate.nextReleaseDelay(187L))
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
