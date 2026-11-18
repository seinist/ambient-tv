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

    @Test fun verticalPlayheadOnlyShowsDuringSeekingAndKeepsTheActualPosition() {
        // Merely opening either TV OSD mode, focusing the bar or displaying an
        // idle handheld timeline must not turn on the vertical playhead.
        assertEquals(null, seekBarPlayheadFraction(25_000, 100_000, seeking = false))
        assertEquals(null, seekBarPlayheadFraction(0, 100_000, seeking = false))
        // Active D-pad/remote seeking, touch drag and pending acknowledgement all
        // share this gate. The independent preview target must not move the line.
        val previewMs = seekBarVisualPosition(25_000, 100_000, keyboardTargetMs = 75_000)
        assertEquals(.75f, seekBarFraction(previewMs, 100_000), 0f)
        assertEquals(.25f, seekBarPlayheadFraction(25_000, 100_000, seeking = true)!!, 0f)
        assertEquals(0f, seekBarPlayheadFraction(-1_000, 100_000, seeking = true)!!, 0f)
        assertEquals(1f, seekBarPlayheadFraction(120_000, 100_000, seeking = true)!!, 0f)
        assertEquals(null, seekBarPlayheadFraction(25_000, 0, seeking = true))
        assertEquals(null, seekBarPlayheadFraction(25_000, -1, seeking = true))
    }

    @Test fun playheadFadeKeepsItsLastCoordinateAfterSeekEnds() {
        assertEquals(.65f, seekBarPlayheadDrawFraction(.65f, .1f), 0f)
        assertEquals(.65f, seekBarPlayheadDrawFraction(null, .65f), 0f)
        assertEquals(150, seekBarPlayheadFadeDurationMs(showing = true, reducedEffects = false))
        assertEquals(220, seekBarPlayheadFadeDurationMs(showing = false, reducedEffects = false))
        assertEquals(90, seekBarPlayheadFadeDurationMs(showing = true, reducedEffects = true))
        assertEquals(120, seekBarPlayheadFadeDurationMs(showing = false, reducedEffects = true))
    }

    @Test fun scrubbingScalesThumbButDoesNotChangeTrackSize() {
        assertEquals(1f, seekBarThumbScale(false), 0f)
        assertEquals(1.18f, seekBarThumbScale(true), 0f)
    }
}
