package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerToolsTest {
    @Test fun frameStepStaysInsideTheTitle() {
        assertEquals(1_040L, frameStepTarget(1_000, 10_000, forward = true, frameMs = 40))
        assertEquals(960L, frameStepTarget(1_000, 10_000, forward = false, frameMs = 40))
        assertEquals(0L, frameStepTarget(20, 10_000, forward = false, frameMs = 40))
        assertEquals(10_000L, frameStepTarget(9_990, 10_000, forward = true, frameMs = 40))
        // Unknown duration (live-like): only the lower bound applies.
        assertEquals(50_040L, frameStepTarget(50_000, 0, forward = true, frameMs = 40))
    }

    @Test fun abRepeatWrapsAtOrPastBOnly() {
        assertFalse(abRepeatWraps(4_999, 1_000, 5_000))
        assertTrue(abRepeatWraps(5_000, 1_000, 5_000))
        assertTrue(abRepeatWraps(9_000, 1_000, 5_000))
        assertFalse(abRepeatWraps(9_000, 5_000, 5_000))
    }

    @Test fun screenshotNamesAreSafeFileNames() {
        val epoch = java.util.Calendar.getInstance().apply { set(2026, 9, 27, 21, 5, 33) }.timeInMillis
        assertEquals("Alien_ Covenant_2026-10-27_21-05-33.png", screenshotFileName("Alien: Covenant", epoch))
        assertEquals("Ambient TV_2026-10-27_21-05-33.png", screenshotFileName("  ", epoch))
        assertEquals("Ambient TV_2026-10-27_21-05-33.png", screenshotFileName(null, epoch))
    }
}
