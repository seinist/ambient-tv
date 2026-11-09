package com.ambient.tv.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowFocusCenteringGateTest {
    @Test fun completedDpadScrollConsumesOnlyTheMatchingFocusCentering() {
        val gate = RowFocusCenteringGate()
        gate.beginMove()
        gate.markCentered("row-8")
        assertTrue(gate.consume("row-8"))
        assertFalse(gate.consume("row-8"))
    }

    @Test fun normalFocusEntryAndEdgeWrapStillRequestCentering() {
        val gate = RowFocusCenteringGate()
        assertFalse(gate.consume("row-2"))
        gate.markCentered("row-3")
        gate.beginMove() // A new wrap/retarget invalidates the old completed row.
        assertFalse(gate.consume("row-3"))
    }

    @Test fun staleRowFocusCannotConsumeTheNewDestination() {
        val gate = RowFocusCenteringGate()
        gate.markCentered("old")
        gate.beginMove()
        gate.markCentered("new")
        assertFalse(gate.consume("old"))
        assertFalse(gate.consume("new")) // Stale focus cleared the one-shot signal.
        gate.markCentered("new")
        assertTrue(gate.consume("new"))
    }
}
