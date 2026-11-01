package com.ambient.tv.data.telegram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonogramResumePositionTest {
    @Test fun shortProgressIsNotResumed() {
        assertFalse(monogramResumeWorthKeeping(positionMs = 4_000L, durationMs = 600_000L))
    }

    @Test fun progressInTheMiddleIsResumed() {
        assertTrue(monogramResumeWorthKeeping(positionMs = 120_000L, durationMs = 600_000L))
        assertTrue(monogramResumeWorthKeeping(positionMs = 120_000L, durationMs = 0L))
    }

    @Test fun nearlyFinishedMediaStartsOver() {
        assertFalse(monogramResumeWorthKeeping(positionMs = 590_000L, durationMs = 600_000L))
    }

    @Test fun stopWithoutPositionKeepsEarlierEntry() {
        assertFalse(monogramStopPositionKnown(positionMs = 0L, ended = false))
        assertTrue(monogramStopPositionKnown(positionMs = 0L, ended = true))
        assertTrue(monogramStopPositionKnown(positionMs = 95_000L, ended = false))
    }
}
