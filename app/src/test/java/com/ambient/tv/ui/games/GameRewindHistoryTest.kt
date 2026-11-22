package com.ambient.tv.ui.games

import org.junit.Assert.*
import org.junit.Test

class GameRewindHistoryTest {
    @Test fun emptyAndCurrentFrameOnlyDoNotOfferRewind() {
        for (states in listOf(-1, 0, 1)) {
            assertFalse(GameRewindHistory(states).available)
            assertEquals(0f, GameRewindHistory(states).seconds)
        }
        assertTrue(GameRewindHistory(2).available)
    }

    @Test fun durationUsesExistingHistoryRatherThanConfiguredTwentySecondTarget() {
        assertEquals(2f, GameRewindHistory(101, 50.0).seconds)
        assertEquals(.5f, GameRewindHistory(31, 60.0).seconds)
        assertEquals(1f, GameRewindHistory(61, Double.NaN).seconds)
        assertEquals(1f, GameRewindHistory(61, 0.0).seconds)
    }
}
