package com.ambient.tv.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvStallWatchdogTest {
    private fun counts(position: Long = 60_000, last: Long = 60_000, duration: Long = 3_600_000, ended: Boolean = false,
                       waiting: Boolean = false, explicit: Boolean = false, wants: Boolean = true) =
        mpvStallCounts(wants, position, last, duration, ended, waiting, explicit)

    @Test fun frozenClockMidFileCounts() { assertTrue(counts()) }

    @Test fun movingClockPausedOrBufferingDoesNotCount() {
        assertFalse(counts(position = 62_000))
        assertFalse(counts(wants = false))
        assertFalse(counts(waiting = true))
    }

    @Test fun fileEndDoesNotCount() {
        assertFalse(counts(ended = true))
        assertFalse(counts(position = 3_596_000, last = 3_596_000))
    }

    @Test fun explicitMpvIsNeverMovedAway() { assertFalse(counts(explicit = true)) }
}
