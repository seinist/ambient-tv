package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseArtworkStartLimiterTest {
    @Test fun uncachedStartsAreSpreadAcrossFrames() {
        val limiter = BrowseArtworkStartLimiter()
        val delays = List(12) { limiter.delayMs(1_000L) }
        assertEquals(List(4) { 0L } + List(4) { 16L } + List(4) { 32L }, delays)
    }

    @Test fun quietGapStartsFreshBurst() {
        val limiter = BrowseArtworkStartLimiter()
        repeat(BROWSE_ARTWORK_STARTS_PER_FRAME + 1) { limiter.delayMs(1_000L) }
        assertEquals(0L, limiter.delayMs(1_000L + BROWSE_ARTWORK_BURST_RESET_MS + 1L))
    }

    @Test fun longBurstHasBoundedDelay() {
        val limiter = BrowseArtworkStartLimiter()
        val delays = List(100) { limiter.delayMs(1_000L) }
        assertTrue(delays.max() <= BROWSE_ARTWORK_MAX_DELAY_MS)
        assertEquals(BROWSE_ARTWORK_MAX_DELAY_MS, delays.last())
    }
}
