package com.ambient.tv.ui.games

import org.junit.Assert.assertEquals
import org.junit.Test

class GameRewindSeekTest {
    @Test fun seekingStaysInsideTheStoredHistory() {
        // Left on the timeline is a negative delta: further back from now.
        assertEquals(5_000L, gameRewindSeekTarget(0L, -5_000L, 12_000L))
        assertEquals(12_000L, gameRewindSeekTarget(10_000L, -5_000L, 12_000L))
        assertEquals(0L, gameRewindSeekTarget(3_000L, 5_000L, 12_000L))
        assertEquals(0L, gameRewindSeekTarget(3_000L, -5_000L, 0L))
    }

    @Test fun distancesBecomeEmulatedFramesAtTheCoreRate() {
        assertEquals(300L, gameRewindSeekFrames(5_000L, 60.0))
        assertEquals(250L, gameRewindSeekFrames(5_000L, 50.0))
        // An implausible rate falls back to 60 fps; negative distances mean now.
        assertEquals(60L, gameRewindSeekFrames(1_000L, Double.NaN))
        assertEquals(0L, gameRewindSeekFrames(-1_000L, 60.0))
    }
}
