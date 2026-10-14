package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPerformanceTest {
    @Test fun filmRateOnAFasterPanelIsNotAFailure() {
        val hint = playbackLoadHint(PlaybackLoadSample(12_000, 0, 23.976, 23.976, 0.0, false, false, false))
        assertEquals(PlaybackLoadHint.NONE, hint)
    }

    @Test fun heavyVideoBelowFileRateIsReportedAfterTheWindow() {
        val hint = playbackLoadHint(PlaybackLoadSample(11_000, 5_000, 24.0, 20.0, 0.0, true, false, false))
        assertEquals(PlaybackLoadHint.VIDEO, hint)
    }

    @Test fun gracePeriodStaysQuiet() {
        val hint = playbackLoadHint(PlaybackLoadSample(9_000, 6_000, 24.0, 10.0, 8.0, true, true, true))
        assertEquals(PlaybackLoadHint.NONE, hint)
    }

    @Test fun dropsWithoutAHeavyFilterStayGeneric() {
        val hint = playbackLoadHint(PlaybackLoadSample(11_000, 5_000, 24.0, 24.0, 3.0, false, false, false))
        assertEquals(PlaybackLoadHint.DROPS, hint)
    }

    @Test fun audioLineNeedsBothInstabilityAndProcessing() {
        assertEquals(
            PlaybackLoadHint.AUDIO,
            playbackLoadHint(PlaybackLoadSample(11_000, 5_000, 24.0, 24.0, 0.0, false, true, true)),
        )
        assertEquals(
            PlaybackLoadHint.NONE,
            playbackLoadHint(PlaybackLoadSample(11_000, 5_000, 24.0, 24.0, 0.0, false, false, true)),
        )
    }

    @Test fun clockRestartsAfterASeek() {
        var now = 0L
        val clock = PlaybackLoadClock { now }
        clock.mark(1_000, false)
        now = 9_000
        val early = clock.mark(9_000, true)
        assertTrue(early.first < 10_000)
        now = 16_000
        val held = clock.mark(15_000, true)
        assertTrue(held.first >= 10_000)
        assertTrue(held.second >= 5_000)
        now = 20_000
        val afterSeek = clock.mark(0, true)
        assertEquals(0L, afterSeek.first)
        assertEquals(0L, afterSeek.second)
    }

    @Test fun heavyVideoNamesMatchTheShaderListOnly() {
        assertTrue(heavyVideoLabel("anime4k-a"))
        assertTrue(heavyVideoLabel("deinterlace"))
        assertFalse(heavyVideoLabel("lanczos"))
        assertFalse(heavyVideoLabel("off"))
    }
}
