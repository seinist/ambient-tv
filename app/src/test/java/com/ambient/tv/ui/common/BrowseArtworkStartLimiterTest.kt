package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class BrowseArtworkStartLimiterTest {
    @Test fun uncachedStartsAreSpreadAcrossFrames() {
        val limiter = BrowseArtworkStartLimiter()
        val delays = List(12) { limiter.delayMs(1_000L) }
        assertEquals(
            listOf(0L, 16L, 32L, 48L, 64L, 80L, 96L, 96L, 96L, 96L, 96L, 96L),
            delays,
        )
    }

    @Test fun focusedArtworkBypassesTheBackgroundWave() {
        val limiter = BrowseArtworkStartLimiter()
        repeat(4) { limiter.delayMs(1_000L) }
        assertEquals(0L, browseArtworkAdmissionDelayMs(true, 1_000L, limiter))
        assertEquals(64L, browseArtworkAdmissionDelayMs(false, 1_000L, limiter))
    }

    @Test fun waitsUntilBrowseInteractionIsQuiet() = runBlocking {
        var checks = 0
        var pauses = 0
        awaitBrowseArtworkIdle(
            recentlyInteracting = {
                checks++
                checks <= 3
            },
            pause = { pauses++ },
        )
        assertEquals(4, checks)
        assertEquals(3, pauses)
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
