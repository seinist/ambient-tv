package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackClockFormatTest {
    @Test fun `below an hour shows minutes without leading zero`() {
        assertEquals("0:00", formatPlaybackClock(0))
        assertEquals("0:09", formatPlaybackClock(9_999))
        assertEquals("5:07", formatPlaybackClock(307_000))
        assertEquals("59:59", formatPlaybackClock(3_599_999))
    }

    @Test fun `from one hour on shows hours`() {
        assertEquals("1:00:00", formatPlaybackClock(3_600_000))
        assertEquals("2:03:04", formatPlaybackClock((2 * 3600 + 3 * 60 + 4) * 1000L))
        assertEquals("100:00:00", formatPlaybackClock(360_000_000))
    }

    @Test fun `negative and unknown positions count as zero`() {
        assertEquals("0:00", formatPlaybackClock(-1))
        assertEquals("0:00", formatPlaybackClock(Long.MIN_VALUE))
    }
}
