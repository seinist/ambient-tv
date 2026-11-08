package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackEndSeekTest {
    @Test fun onlyTargetsAtOrBeyondTheKnownEndAreTerminal() {
        assertFalse(isPlaybackEndSeek(-1L, 100_000L))
        assertFalse(isPlaybackEndSeek(0L, 100_000L))
        assertFalse(isPlaybackEndSeek(99_999L, 100_000L))
        assertTrue(isPlaybackEndSeek(100_000L, 100_000L))
        assertTrue(isPlaybackEndSeek(120_000L, 100_000L))
        assertTrue(isPlaybackEndSeek(Long.MAX_VALUE, 100_000L))
    }

    @Test fun unknownOrInvalidDurationsDoNotInventAnEnd() {
        listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE).forEach { duration ->
            assertFalse(isPlaybackEndSeek(Long.MAX_VALUE, duration))
            assertNull(safePlaybackEndSeekTarget(0L, duration))
        }
    }

    @Test fun endSeekLeavesOneAndAHalfSecondsForNaturalEof() {
        assertEquals(98_500L, safePlaybackEndSeekTarget(80_000L, 100_000L))
        assertEquals(98_500L, safePlaybackEndSeekTarget(0L, 100_000L))
    }

    @Test fun endSeekNeverRewindsOrRepeatsTheRemainingTail() {
        listOf(98_500L, 99_000L, 100_000L, 100_001L, Long.MAX_VALUE).forEach { current ->
            assertNull(safePlaybackEndSeekTarget(current, 100_000L))
        }
    }

    @Test fun theTailMustBeMoreThanAQuarterSecondAhead() {
        assertEquals(98_500L, safePlaybackEndSeekTarget(98_249L, 100_000L))
        assertNull(safePlaybackEndSeekTarget(98_250L, 100_000L))
        assertNull(safePlaybackEndSeekTarget(98_251L, 100_000L))
    }

    @Test fun shortMediaPlaysOutWithoutSeekingToItsStart() {
        listOf(1L, 250L, 1_499L, 1_500L, 1_750L).forEach { duration ->
            assertNull(safePlaybackEndSeekTarget(0L, duration))
        }
        assertEquals(251L, safePlaybackEndSeekTarget(0L, 1_751L))
    }

    @Test fun negativeEnginePositionsAreTreatedAsTheStart() {
        assertEquals(98_500L, safePlaybackEndSeekTarget(-1L, 100_000L))
        assertEquals(98_500L, safePlaybackEndSeekTarget(Long.MIN_VALUE, 100_000L))
        assertNull(safePlaybackEndSeekTarget(Long.MIN_VALUE, 1_500L))
    }

    @Test fun largeFiniteDurationsAndPositionsDoNotOverflow() {
        val duration = Long.MAX_VALUE - 1L
        assertTrue(isPlaybackEndSeek(duration, duration))
        assertEquals(duration - 1_500L, safePlaybackEndSeekTarget(0L, duration))
        assertNull(safePlaybackEndSeekTarget(Long.MAX_VALUE, duration))
    }
}
