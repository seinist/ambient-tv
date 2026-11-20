package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hardware repeat count is deliberately irrelevant: speed depends only on elapsed hold time. */
class ProgressiveSeekControllerTest {
    private var now = 0L
    private fun controller() = ProgressiveSeekController { now }

    @Test fun briefTapsMoveFiveSecondsAndCommitOnlyOnRelease() {
        val seek = controller()
        seek.press(1, 100_000L, 600_000L)
        assertTrue(seek.active)
        assertEquals(105_000L, seek.previewTargetMs)
        now = 120L
        assertEquals(105_000L, seek.release(1))
        assertNull(seek.previewTargetMs)
        assertFalse(seek.active)
        assertNull(seek.release(1))
    }

    @Test fun repeatedDownEventsDoNotRestartOrMultiplyTheBurst() {
        val seek = controller()
        seek.press(1, 100_000L, 3_600_000L)
        now = 1_000L
        seek.advance()
        val position = seek.previewTargetMs!!
        repeat(20) { seek.press(1, 999_999L, 3_600_000L) }
        assertEquals(position, seek.previewTargetMs)
        now = 2_000L
        seek.advance()
        assertTrue(seek.previewTargetMs!! > position)
    }

    @Test fun logarithmicAccelerationIncreasesThenGraduallyFlattens() {
        val seek = controller()
        seek.press(1, 20_000L, 7_200_000L)
        now = 1_300L
        seek.advance()
        val first = seek.previewTargetMs!!
        now = 2_300L
        seek.advance()
        val second = seek.previewTargetMs!!
        now = 3_300L
        seek.advance()
        val third = seek.previewTargetMs!!
        assertTrue(second - first > first - 25_000L)
        assertTrue(third - second > second - first)
        assertTrue(third < 7_200_000L)
    }

    @Test fun longHoldKeepsAcceleratingAfterTheInitialLogarithmicRamp() {
        val seek = controller()
        seek.press(1, 0L, 1_800_000L)
        now = 3_000L
        seek.advance()
        val atThreeSeconds = seek.previewTargetMs!!
        now = 4_000L
        seek.advance()
        val atFourSeconds = seek.previewTargetMs!!
        now = 5_000L
        seek.advance()
        val atFiveSeconds = seek.previewTargetMs!!

        // An extended hold traverses more ground in every later second, without
        // increasing the precise five-second tap. The former curve was < 800 s at 5 s.
        assertTrue(atFourSeconds - atThreeSeconds > 0L)
        assertTrue(atFiveSeconds - atFourSeconds > atFourSeconds - atThreeSeconds)
        assertTrue(atFiveSeconds > 900_000L)
        assertEquals(atFiveSeconds, seek.release(1))
    }

    @Test fun aShortHoldBelowTheAccelerationDelayStillMovesExactlyFiveSeconds() {
        val seek = controller()
        seek.press(-1, 100_000L, 1_800_000L)
        now = 290L
        assertEquals(95_000L, seek.release(-1))
    }

    @Test fun aMissedTickerFrameMustNotSlowDownAProlongedHold() {
        val regular = controller()
        val sparse = controller()
        regular.press(1, 0L, 7_200_000L)
        sparse.press(1, 0L, 7_200_000L)
        for (elapsed in 50L..5_000L step 50L) {
            now = elapsed
            regular.advance()
        }
        now = 5_000L
        sparse.advance()
        // Interpolated 50-ms integration is independent of native key-repeat events or
        // Compose failing to deliver intermediate animation ticks during a slow frame.
        assertEquals(regular.previewTargetMs, sparse.previewTargetMs)
        assertEquals(regular.release(1), sparse.release(1))
    }

    @Test fun longHoldAccelerationWorksInBothDirections() {
        val forward = controller()
        val back = controller()
        val origin = 900_000L
        val duration = 1_800_000L
        forward.press(1, origin, duration)
        back.press(-1, origin, duration)
        now = 3_000L
        val forwardTarget = forward.release(1)!!
        val backwardTarget = back.release(-1)!!
        assertTrue(forwardTarget > origin + 100_000L)
        assertTrue(backwardTarget < origin - 100_000L)
        assertEquals(2 * origin, forwardTarget + backwardTarget)
    }

    @Test fun directionChangeStartsAgainFromTheNewAnchor() {
        val seek = controller()
        seek.press(1, 100_000L, 600_000L)
        now = 2_000L
        seek.advance()
        assertTrue(seek.previewTargetMs!! > 105_000L)
        seek.press(-1, 100_000L, 600_000L)
        assertEquals(95_000L, seek.previewTargetMs)
        assertNull(seek.release(1))
        assertEquals(95_000L, seek.release(-1))
    }

    @Test fun holdAlwaysClampsAtMediaBounds() {
        val seek = controller()
        seek.press(1, 598_000L, 600_000L)
        assertEquals(600_000L, seek.previewTargetMs)
        now = 60_000L
        seek.advance()
        assertEquals(600_000L, seek.release(1))
        seek.press(-1, 1_000L, 600_000L)
        assertEquals(0L, seek.previewTargetMs)
        assertEquals(0L, seek.release(-1))
    }

    @Test fun cancellingDropsThePreviewWithoutSeeking() {
        val seek = controller()
        seek.press(1, 100_000L, 600_000L)
        seek.cancel()
        assertNull(seek.previewTargetMs)
        assertNull(seek.release(1))
    }
    @Test fun longMoviesCanTraverseLargeDistancesWithoutLosingPrecisionOnTap() {
        val seek = controller()
        val film = 7_200_000L
        seek.press(1, 0L, film)
        assertEquals(5_000L, seek.previewTargetMs)
        now = 10_000L
        seek.advance()
        assertTrue(seek.previewTargetMs!! > film / 2)
        assertTrue(seek.previewTargetMs!! <= film)
        assertEquals(seek.previewTargetMs, seek.release(1))
    }

}
