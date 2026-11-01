package com.ambient.tv.ui.tvheadend

import com.ambient.tv.livetv.LiveTvProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTimelineTest {
    private fun programme(id: String, start: Long, stop: Long) = LiveTvProgramme(
        eventId = id, channelUuid = "station", channelName = "Station",
        title = "Programme $id", startEpochSeconds = start, stopEpochSeconds = stop,
    )

    @Test fun everyGuideLaneUsesTheSameHalfHourOrigin() {
        assertEquals(5_400L, guideTimelineOrigin(7_200L))
        assertEquals(5_400L, guideTimelineOrigin(7_399L))
        assertEquals(7_200L, guideTimelineOrigin(9_001L))
        assertEquals(0L, guideTimelineOrigin(300L))
    }

    @Test fun proportionalSlotsKeepGapsAndOriginalEventIndices() {
        val a = programme("a", 4_800L, 6_000L)
        val b = programme("b", 6_600L, 8_400L)
        val slots = guideTimelineSlots(listOf(a, b), origin = 5_400L)
        assertEquals(listOf(a, b), slots.map { it.programme })
        assertEquals(0L, slots[0].gapBeforeSeconds)
        assertEquals(600L, slots[0].visibleSeconds)
        assertEquals(600L, slots[1].gapBeforeSeconds)
        assertEquals(1_800L, slots[1].visibleSeconds)
        assertEquals(40f, guideTimelineWidthDp(slots[0].visibleSeconds))
        assertEquals(120f, guideTimelineWidthDp(slots[1].visibleSeconds))
        assertEquals(40f, guideTimelineWidthDp(slots[1].gapBeforeSeconds))
    }

    @Test fun overlappingEpgEventsDoNotCreateNegativeSpaceOrShiftFocusIndices() {
        val first = programme("a", 4_800L, 7_200L)
        val overlap = programme("b", 7_000L, 8_400L)
        val future = programme("c", 9_000L, 10_800L)
        val slots = guideTimelineSlots(listOf(first, overlap, future), 5_400L)
        assertEquals(3, slots.size)
        assertEquals(0L, slots[1].gapBeforeSeconds)
        assertEquals(1_200L, slots[1].visibleSeconds)
        assertEquals(600L, slots[2].gapBeforeSeconds)
        assertTrue(slots.all { it.visibleSeconds >= 0L && it.gapBeforeSeconds >= 0L })
    }
}
