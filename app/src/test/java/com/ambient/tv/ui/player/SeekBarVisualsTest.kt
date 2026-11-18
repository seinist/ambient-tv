package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekBarVisualsTest {
    @Test fun pointerAndRemoteTargetsTakePrecedenceWithoutChangingTheEngineClock() {
        assertEquals(40_000L, seekBarVisualPosition(40_000, 100_000))
        assertEquals(55_000L, seekBarVisualPosition(40_000, 100_000, awaitingTargetMs = 55_000))
        assertEquals(70_000L, seekBarVisualPosition(40_000, 100_000, keyboardTargetMs = 70_000, awaitingTargetMs = 55_000))
        assertEquals(80_000L, seekBarVisualPosition(40_000, 100_000, pointerTargetMs = 80_000,
            keyboardTargetMs = 70_000, awaitingTargetMs = 55_000))
    }

    @Test fun zeroDurationAndOutOfBoundsTargetsDoNotProduceInvalidProgress() {
        assertEquals(0L, seekBarVisualPosition(10_000, 0, pointerTargetMs = 80_000))
        assertEquals(0L, seekBarVisualPosition(-10_000, 100_000))
        assertEquals(100_000L, seekBarVisualPosition(0, 100_000, keyboardTargetMs = 500_000))
        assertEquals(0f, seekBarFraction(250L, 0L), 0f)
        assertEquals(.5f, seekBarFraction(50L, 100L), .0001f)
        assertEquals(1f, seekBarFraction(900L, 100L), 0f)
    }

    @Test fun targetMarkerAppearsOnlyWhileSeekingAwayFromCurrentPosition() {
        assertFalse(seekBarHasDistinctTarget(25_000, 40_000, false))
        assertFalse(seekBarHasDistinctTarget(25_000, 25_500, true))
        assertTrue(seekBarHasDistinctTarget(25_000, 40_000, true))
    }

    @Test fun verticalPlayheadAlwaysTracksPublishedPlaybackAndNeverDependsOnSeekTarget() {
        // These assertions apply to the same helper in TV compact seek mode, expanded
        // button mode and the handheld scrubber; the target/preview can move separately.
        assertEquals(.25f, seekBarPlayheadFraction(25_000, 100_000)!!, 0f)
        assertEquals(.25f, seekBarPlayheadFraction(25_000, 100_000)!!, 0f)
        assertEquals(0f, seekBarPlayheadFraction(-1_000, 100_000)!!, 0f)
        assertEquals(1f, seekBarPlayheadFraction(120_000, 100_000)!!, 0f)
        assertEquals(null, seekBarPlayheadFraction(25_000, 0))
        assertEquals(null, seekBarPlayheadFraction(25_000, -1))
    }

    @Test fun scrubbingScalesThumbButDoesNotChangeTrackSize() {
        assertEquals(1f, seekBarThumbScale(false), 0f)
        assertEquals(1.18f, seekBarThumbScale(true), 0f)
    }
}
