package com.ambient.tv.ui.radio

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioPlayerTimelineTest {
    @Test fun unboundedLiveRadioAlwaysHasPassiveTimeline() {
        assertEquals(RadioTimelineMode.LIVE, radioTimelineMode(0L, seekable = false))
        assertEquals(RadioTimelineMode.LIVE, radioTimelineMode(-1L, seekable = true))
        assertEquals(0f, radioTimelineFraction(90_000L, 0L), 0f)
    }

    @Test fun durationAloneDoesNotPermitScrubbing() {
        assertEquals(RadioTimelineMode.PROGRESS, radioTimelineMode(180_000L, seekable = false))
        assertEquals(RadioTimelineMode.SEEK, radioTimelineMode(180_000L, seekable = true))
    }

    @Test fun passiveProgressUsesRealWindowAndRemainsBounded() {
        assertEquals(.5f, radioTimelineFraction(90_000L, 180_000L), .001f)
        assertEquals(0f, radioTimelineFraction(-100L, 180_000L), 0f)
        assertEquals(1f, radioTimelineFraction(500_000L, 180_000L), 0f)
    }

    @Test fun actualRadioSeekTargetsClampAtWindowEdges() {
        assertEquals(25_000L, radioSeekTarget(10_000L, 60_000L, 15_000L))
        assertEquals(0L, radioSeekTarget(10_000L, 60_000L, -30_000L))
        assertEquals(60_000L, radioSeekTarget(58_000L, 60_000L, 30_000L))
        assertEquals(0L, radioSeekTarget(10_000L, 0L, 10_000L))
        assertEquals(Long.MAX_VALUE, radioSeekTarget(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(0L, radioSeekTarget(Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE))
    }
}
