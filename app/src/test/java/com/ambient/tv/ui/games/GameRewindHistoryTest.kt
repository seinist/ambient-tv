package com.ambient.tv.ui.games

import org.junit.Assert.*
import org.junit.Test

class GameRewindHistoryTest {
    @Test fun onlyAnEarlierRestorableFrameOffersRewind() {
        for (frames in listOf(Long.MIN_VALUE, -1L, 0L)) {
            assertFalse(GameRewindHistory(frames).available)
            assertEquals(0f, GameRewindHistory(frames).seconds)
        }
        assertTrue(GameRewindHistory(1).available)
    }

    @Test fun durationUsesExistingHistoryRatherThanConfiguredTwentySecondTarget() {
        assertEquals(2f, GameRewindHistory(100, 50.0).seconds)
        assertEquals(.5f, GameRewindHistory(30, 60.0).seconds)
    }

    @Test fun capturedFrameDistancesIncludeFastForwardAndSparseCheckpoints() {
        // Thirty saved intervals may represent 30, 60 or 120 emulated frames.
        assertEquals(.5f, GameRewindHistory(30, 60.0).seconds)
        assertEquals(1f, GameRewindHistory(60, 60.0).seconds)
        assertEquals(2f, GameRewindHistory(120, 60.0).seconds)
    }

    @Test fun invalidCoreTimingCannotProduceInvalidDuration() {
        for (fps in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0.0, -50.0, 241.0)) {
            assertEquals(1f, GameRewindHistory(60, fps).seconds)
        }
    }

    @Test fun extremeSnapshotsStayWithinTheConfiguredHistoryTarget() {
        for (fps in listOf(1.0, 50.0, 60.0988118623, 240.0, Double.NaN)) {
            assertEquals(GAME_REWIND_SECONDS.toFloat(), GameRewindHistory(Long.MAX_VALUE, fps).seconds)
        }
        assertEquals(20f, GameRewindHistory(1_000, 50.0).seconds)
    }
}
