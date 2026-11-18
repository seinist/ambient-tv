package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerOsdProgressTrackTest {
    @Test fun passiveLiveTimelineClampsUnknownAndOutOfRangeValues() {
        assertEquals(0f, playerOsdProgressFraction(0f), 0f)
        assertEquals(.5f, playerOsdProgressFraction(.5f), 0f)
        assertEquals(1f, playerOsdProgressFraction(1f), 0f)
        assertEquals(0f, playerOsdProgressFraction(-1f), 0f)
        assertEquals(1f, playerOsdProgressFraction(2f), 0f)
        assertEquals(0f, playerOsdProgressFraction(Float.NaN), 0f)
        assertEquals(0f, playerOsdProgressFraction(Float.POSITIVE_INFINITY), 0f)
    }
}
