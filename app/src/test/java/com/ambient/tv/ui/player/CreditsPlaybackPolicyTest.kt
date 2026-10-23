package com.ambient.tv.ui.player

import com.ambient.tv.domain.model.PlaybackSegment
import org.junit.Assert.*
import org.junit.Test

class CreditsPlaybackPolicyTest {
    private val credits = PlaybackSegment("credits", 70_000, -1)
    @Test fun absentMarkersRetainKodiThreshold() {
        assertFalse(playbackIsComplete(89_999, 100_000, false, emptyList()))
        assertTrue(playbackIsComplete(90_000, 100_000, false, emptyList()))
        assertFalse(playbackIsComplete(1, 0, false, emptyList()))
    }
    @Test fun finalCreditsCompleteEarlyButKnownLaterScenesDoNot() {
        assertTrue(playbackIsComplete(70_000, 100_000, false, listOf(credits)))
        assertNull(finalCreditsAt(75_000, 100_000, listOf(credits.copy(endMs = 80_000))))
        assertNull(finalCreditsAt(75_000, 100_000, listOf(credits, credits.copy(startMs = 95_000))))
        assertNotNull(finalCreditsAt(95_000, 100_000, listOf(credits.copy(startMs = 95_000, endMs = 100_000))))
    }
    @Test fun malformedAndIntroMarkersCannotCompletePlayback() {
        assertNull(finalCreditsAt(70_000, 100_000, listOf(credits.copy(startMs = -1))))
        assertNull(finalCreditsAt(70_000, 100_000, listOf(credits.copy(type = "intro"))))
    }
    @Test fun countdownFreezesWhenPausedAndRequiresPlaybackTime() {
        val clock = CreditsCountdown()
        assertEquals(5_000L, clock.update(credits, 70_000, true, 5))
        assertEquals(4_000L, clock.update(credits, 71_000, true, 5))
        assertEquals(4_000L, clock.update(credits, 71_000, false, 5))
        assertEquals(4_000L, clock.update(credits, 71_000, true, 5))
        for (position in 72_000L..75_000L step 1_000) clock.update(credits, position, true, 5)
        assertEquals(0L, clock.update(credits, 75_000, true, 5))
    }
    @Test fun seekForwardRestartsAndRewindCancels() {
        val clock = CreditsCountdown()
        clock.update(credits, 70_000, true, 5)
        clock.update(credits, 71_000, true, 5)
        assertEquals(5_000L, clock.update(credits, 90_000, true, 5))
        assertNull(clock.update(credits, 75_000, true, 5))
        assertNull(clock.update(credits, 76_000, true, 5))
        assertNull(clock.update(null, 60_000, true, 5))
        assertNull(clock.update(credits, 70_000, true, 5))
    }
    @Test fun rewindingBeforeCreditsDoesNotRearmThem() {
        val clock = CreditsCountdown()
        clock.update(credits, 70_000, true, 5)
        assertNull(clock.update(null, 60_000, true, 5))
        assertNull(clock.update(credits, 70_000, true, 5))
    }
    @Test fun disabledAndDismissedCountdownCannotFire() {
        val clock = CreditsCountdown()
        assertNull(clock.update(credits, 70_000, true, 0))
        clock.update(credits, 70_000, true, 5)
        assertNull(clock.update(null, 75_000, true, 5))
    }
}
