package com.ambient.tv.ui.player.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** G6 of the 26.10. audit: a stream reload gets the 15 s opening limit, not the 5 s one. */
class MpvReopenWatchdogTest {
    @Test fun reopeningIsCountedAndNamed() {
        val step = MpvOwnerStep(clock = { 0L })
        step.reopening("reopen:loadfile")
        assertEquals(1, step.reopens)
        assertEquals("reopen:loadfile", step.name)
    }

    @Test fun unseenReopenCountsAsOpening() {
        assertTrue(mpvWatchdogOpening(opening = false, seenReopens = 0, reopens = 1))
        assertFalse(mpvWatchdogOpening(opening = false, seenReopens = 1, reopens = 1))
        assertTrue(mpvWatchdogOpening(opening = true, seenReopens = 1, reopens = 1))
    }
}
