package com.ambient.tv.ui.intro

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroBrandMarkMotionTest {
    @Test fun ambientBackgroundStaysVisibleAfterRevealAndDuringServerWake() {
        // Low-power rendering uses the settled 60-second frame; the 3-minute wake hold
        // must never fade these new backgrounds back to a blank screen.
        assertEquals(0f, introBackdropPresence(0f), 0.0001f)
        assertTrue(introBackdropPresence(0.65f) > 0f)
        assertTrue(introBackdropPresence(0.65f) < 1f)
        assertEquals(1f, introBackdropPresence(1.25f), 0.0001f)
        assertEquals(1f, introBackdropPresence(60f), 0.0001f)
        assertEquals(1f, introBackdropPresence(180f), 0.0001f)
    }

    @Test fun aKeepsFinalWordmarkProportionsInsideTheTv() {
        val peak = introBloomPeakInTv
        assertEquals(24f, peak[1].x, 0.0001f)
        assertEquals(13f, peak[1].y, 0.0001f)
        assertEquals(28f, peak[0].y, 0.0001f)
        assertEquals(15f, peak[2].x - peak[0].x, 0.0001f)
        assertEquals(15f, peak[0].y - peak[1].y, 0.0001f)
    }

    @Test fun framedAEndsOnTheWordmarkWithoutAnyMorph() {
        val wordmarkUnit = 2f
        val apex = Offset(200f, 100f)
        val iconUnit = wordmarkUnit * 16f / 15f
        val origin = apex - introBloomPeakInTv[1] * iconUnit
        val peak = introBloomPeakAt(origin, iconUnit)

        assertEquals(apex.x, peak[1].x, 0.0001f)
        assertEquals(apex.y, peak[1].y, 0.0001f)
        assertEquals(apex.x - 8f * wordmarkUnit, peak[0].x, 0.0001f)
        assertEquals(apex.x + 8f * wordmarkUnit, peak[2].x, 0.0001f)
        assertEquals(apex.y + 16f * wordmarkUnit, peak[0].y, 0.0001f)
        assertEquals(peak[0].y, peak[2].y, 0.0001f)
    }

    @Test fun aStaysFixedRelativeToTvAcrossTranslationAndScaling() {
        val before = introBloomPeakAt(Offset(30f, 50f), 4f)
        val after = introBloomPeakAt(Offset(70f, 60f), 2f)
        for (i in before.indices) {
            assertEquals((before[i].x - 30f) / 4f, (after[i].x - 70f) / 2f, 0.0001f)
            assertEquals((before[i].y - 50f) / 4f, (after[i].y - 60f) / 2f, 0.0001f)
        }
    }
}
