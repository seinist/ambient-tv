package com.ambient.tv.ui.tvheadend

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeshiftProgrammeProgressTest {
    @Test fun followsPlaybackRatherThanLiveClock() {
        assertEquals(30_000L, programmePositionMs(130_000, 100, 200))
        assertEquals(20_000L, programmePositionMs(120_000, 100, 200))
    }
    @Test fun clampsEarlierProgrammesAndInvalidEpgRanges() {
        assertEquals(0L, programmePositionMs(90_000, 100, 200))
        assertEquals(100_000L, programmePositionMs(250_000, 100, 200))
        assertEquals(0L, programmePositionMs(150_000, 200, 100))
        assertEquals(0L, programmePositionMs(150_000, 100, 100))
    }
}
