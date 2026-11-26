package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class Media3FrameTimelineTest {
    @Test fun variableFrameDurationsUseRealAdjacentPts() {
        val history = Media3FrameTimeline()
        history.presented(0)
        history.presented(20_000)
        history.presented(95_000)
        history.pending(130_000)
        assertEquals(95_000L, history.anchor(100_000))
        assertEquals(20_000L, history.neighbor(95_000, false))
        assertEquals(130_000L, history.neighbor(95_000, true))
        assertEquals(95_000L, history.neighbor(20_000, true))
    }

    @Test fun clearedOrDistantHistoryDoesNotSupplyAFalseNeighbor() {
        val history = Media3FrameTimeline()
        history.presented(100_000)
        assertEquals(10_000_000L, history.anchor(10_000_000))
        assertNull(history.neighbor(10_000_000, false))
        history.clear()
        assertNull(history.neighbor(100_000, false))
        assertNull(history.neighbor(0, true))
    }

    @Test fun historyEvictsOldFramesAndPendingIsInvalidated() {
        val history = Media3FrameTimeline(capacity = 2)
        history.presented(0)
        history.presented(100_000)
        history.presented(300_000)
        assertNull(history.neighbor(100_000, false))
        assertEquals(100_000L, history.neighbor(300_000, false))
        history.pending(350_000)
        assertEquals(350_000L, history.neighbor(300_000, true))
        history.pending(-1L)
        assertNull(history.neighbor(300_000, true))
    }
}
