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

    @Test fun subMillisecondNeighborsDoNotCauseRepeatedIdenticalSeeks() {
        val history = Media3FrameTimeline()
        history.presented(20_100)
        history.presented(20_800)
        history.presented(40_200)
        history.pending(40_900)
        assertEquals(40_200L, history.neighbor(20_100, true))
        assertEquals(20_800L, history.neighbor(40_200, false))
        assertNull(history.neighbor(40_200, true))
    }

    @Test fun frameStepNeverTargetsLogicalVodEnd() {
        assertEquals(Media3FrameStepPlan(999L, 999_000L),
            media3FrameStepPlan(980_000L, null, 1000L, true, 25f, false))
        assertEquals(1000L, media3FrameStepPlan(980_000L, null, 1000L, true, 25f, true).targetMs)
        assertEquals(0L, media3FrameStepPlan(10_000L, null, 1000L, false, 25f, false).targetMs)
    }

    @Test fun nominalFractionalCadenceDoesNotAccumulateMillisecondRounding() {
        var cursor = 0L
        repeat(24) {
            cursor = media3FrameStepPlan(cursor, null, 0L, true, 24f, false).cursorUs
        }
        assertEquals(999_984L, cursor)
        repeat(24) {
            cursor = media3FrameStepPlan(cursor, null, 0L, false, 24f, false).cursorUs
        }
        assertEquals(0L, cursor)
    }

    @Test fun realPtsOverridesNominalCadenceButNeverReversesDirection() {
        assertEquals(Media3FrameStepPlan(95L, 95_600L),
            media3FrameStepPlan(20_000L, 95_600L, 1000L, true, 25f, false))
        assertEquals(60L, media3FrameStepPlan(20_000L, 10_000L, 1000L, true, 25f, false).targetMs)
    }
}
