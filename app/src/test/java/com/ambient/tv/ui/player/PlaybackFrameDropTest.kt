package com.ambient.tv.ui.player

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class PlaybackFrameDropTest {
    private val before = FrameDropReading(
        dropped = 10, rendered = 100, positionMs = 1_000, playing = true,
        fps = 50.0, realtimeMs = 1_000,
    )
    private val after = before.copy(dropped = 20, rendered = 140, positionMs = 2_000, realtimeMs = 2_000)

    @Test fun media3UnknownNominalRateStillUsesItsRealCounters() {
        // Raw AVC/HEVC TS can report Format.NO_VALUE even while decoder counters advance.
        val start = before.copy(fps = -1.0)
        val current = after.copy(fps = -1.0, rendered = 145, dropped = 15)
        val sample = frameDropWindow(start, current)
        assertNotNull("Unknown nominal FPS must not discard real Media3 output", sample)
        assertEquals(45.0, sample!!.actualFps, 0.001)
        assertNull(sample.targetFps)
        assertNull(sample.deviationFps)
        assertEquals(5.0, sample.droppedFps!!, 0.001)
        assertEquals(10.0, sample.counterDropPercent!!, 0.001)
        assertEquals(10.0, sample.deficitPercent!!, 0.001)
    }

    @Test fun vlcUnknownFilterCadenceStillUsesItsRealCounters() {
        // Auto double-rate deinterlacing cannot promise one nominal output cadence.
        val start = before.copy(fps = null)
        val current = after.copy(fps = null, rendered = 145, dropped = 15)
        val sample = frameDropWindow(start, current)
        assertNotNull("Unknown nominal FPS must not discard real VLC output", sample)
        assertEquals(45.0, sample!!.actualFps, 0.001)
        assertNull(sample.targetFps)
        assertNull(sample.deviationFps)
        assertEquals(10.0, sample.counterDropPercent!!, 0.001)
    }

    @Test fun liveCounterMeasurementSurvivesAWindowRelativePositionJump() {
        val start = before.copy(positionMs = 120_000)
        val current = after.copy(positionMs = 1_000)
        val sample = frameDropWindow(start, current)
        assertNotNull("A moving live window is not a counter reset", sample)
        assertEquals(40.0, sample!!.actualFps, 0.001)
    }

    @Test fun liveCounterMeasurementDoesNotRequireAnAvailableMediaClock() {
        val start = before.copy(positionMs = 0)
        val current = after.copy(positionMs = 0)
        val sample = frameDropWindow(start, current)
        assertNotNull("Monotone output counters remain useful without a live media clock", sample)
        assertEquals(40.0, sample!!.actualFps, 0.001)
    }

    @Test fun showsFpsBelowTargetInsteadOfDroppedOverRenderedRatio() {
        val sample = frameDropWindow(before, after)!!
        assertEquals(40.0, sample.actualFps, 0.001)
        assertEquals(50.0, sample.targetFps!!, 0.001)
        assertEquals(-10.0, sample.deviationFps!!, 0.001)
        assertEquals(20.0, sample.deficitPercent!!, 0.001)
        assertFalse(sample.estimated)
    }

    @Test fun delayedPollUsesElapsedTimeAndDetectsSlowOutputEvenWithoutReportedDrops() {
        val current = before.copy(dropped = 10, rendered = 160, positionMs = 2_250, realtimeMs = 2_250)
        val sample = frameDropWindow(before, current)!!
        assertEquals(48.0, sample.actualFps, 0.001)
        assertEquals(-2.0, sample.deviationFps!!, 0.001)
    }

    @Test fun healthyPlaybackMeetsVideoTargetWithoutAssumingDisplayRefresh() {
        val start = before.copy(fps = 24.0)
        val sample = frameDropWindow(start, start.copy(rendered = 124, positionMs = 2_000, realtimeMs = 2_000))!!
        assertEquals(24.0, sample.targetFps!!, 0.001)
        assertEquals(0.0, sample.deviationFps!!, 0.001)
    }

    @Test fun measuredOutputAboveTargetKeepsItsSignAndDoesNotCountAsAFrameDeficit() {
        val sample = frameDropWindow(before, after.copy(rendered = 152))!!
        assertEquals(2.0, sample.deviationFps!!, 0.001)
        assertEquals(0.0, sample.deficitPercent!!, 0.001)
    }

    @Test fun stoppedVideoOutputWhileMediaClockAdvancesIsNotHidden() {
        val sample = frameDropWindow(before, after.copy(rendered = 100))!!
        assertEquals(0.0, sample.actualFps, 0.001)
        assertEquals(-50.0, sample.deviationFps!!, 0.001)
    }

    @Test fun mpvEstimateUsesDropsPerRealSecondAndIsMarkedEstimated() {
        val start = before.copy(rendered = null, estimated = true)
        val current = start.copy(dropped = 20, positionMs = 2_250, realtimeMs = 2_250)
        val sample = frameDropWindow(start, current)!!
        assertEquals(42.0, sample.actualFps, 0.001)
        assertEquals(-8.0, sample.deviationFps!!, 0.001)
        assertTrue(sample.estimated)
        assertEquals(0.0, frameDropWindow(start, current.copy(dropped = 200))!!.actualFps, 0.001)
        assertNull(frameDropWindow(start, current.copy(dropped = null)))
    }

    @Test fun invalidTimingAndSpeedDoNotInventMeasurements() {
        assertNull(frameDropWindow(null, after))
        for (current in listOf(
            after.copy(realtimeMs = before.realtimeMs),
            after.copy(realtimeMs = 500),
            after.copy(realtimeMs = 10_000),
            after.copy(speed = 0.0), after.copy(speed = -1.0),
            after.copy(speed = Double.NaN), after.copy(speed = Double.POSITIVE_INFINITY),
        )) assertNull(current.toString(), frameDropWindow(before, current))
        for (speed in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(frameDropWindow(before.copy(speed = speed), after))
            assertNull(frameDropWindow(before.copy(fps = null, speed = speed), after.copy(fps = null, speed = speed)))
        }
    }

    @Test fun unknownNominalRatesAllowOnlyTrueCounterMeasurements() {
        for (fps in listOf(null, -1.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val start = before.copy(fps = fps)
            val current = after.copy(fps = fps)
            val sample = frameDropWindow(start, current)!!
            assertEquals(40.0, sample.actualFps, 0.001)
            assertNull(sample.targetFps)
            assertNull(sample.deviationFps)
            assertNull(frameDropWindow(start.copy(estimated = true), current.copy(estimated = true)))
        }
    }

    @Test fun pauseAndDiscontinuityGenerationsDiscardTheWindowForEveryRenderer() {
        for (estimated in listOf(false, true)) {
            val start = before.copy(estimated = estimated)
            val current = after.copy(estimated = estimated)
            assertNull(frameDropWindow(start.copy(playing = false), current))
            assertNull(frameDropWindow(start, current.copy(playing = false)))
            assertNull(frameDropWindow(start, current.copy(generation = 1)))
        }
    }

    @Test fun mpvEstimatesStillRejectStationaryClocksAndSeeks() {
        val start = before.copy(estimated = true)
        val current = after.copy(estimated = true)
        assertNull(frameDropWindow(start, current.copy(positionMs = start.positionMs)))
        assertNull(frameDropWindow(start, current.copy(positionMs = 0)))
        assertNull(frameDropWindow(start, current.copy(positionMs = 10_000)))
    }

    @Test fun coarseNativePositionTicksAreAllowedButNotUsedAsElapsedTime() {
        val sample = frameDropWindow(before, after.copy(positionMs = 1_800))!!
        assertEquals(40.0, sample.actualFps, 0.001)
    }

    @Test fun resetsAndMissingRenderCountersInvalidateMeasuredOutput() {
        assertNull(frameDropWindow(before, after.copy(rendered = 20)))
        assertNull(frameDropWindow(before, after.copy(dropped = 1)))
        assertNull(frameDropWindow(before, after.copy(dropped = -1)))
        assertNull(frameDropWindow(before, after.copy(rendered = null)))
        assertNull(frameDropWindow(before.copy(rendered = null), after))
        // Output FPS remains measurable when an engine omits only its drop counter.
        assertNotNull(frameDropWindow(before.copy(dropped = null), after.copy(dropped = null)))
    }

    @Test fun playbackSpeedScalesTargetAndMediaClockValidationTogether() {
        val start = before.copy(speed = 1.5)
        val current = start.copy(rendered = 190, positionMs = 2_875, realtimeMs = 2_250)
        val sample = frameDropWindow(start, current)!!
        assertEquals(75.0, sample.targetFps!!, 0.001)
        assertEquals(72.0, sample.actualFps, 0.001)
        assertEquals(-3.0, sample.deviationFps!!, 0.001)
    }

    @Test fun targetSpeedAndMeasurementModeChangesStartANewWindow() {
        assertNull(frameDropWindow(before, after.copy(fps = 60.0)))
        assertNull(frameDropWindow(before, after.copy(fps = null)))
        assertNull(frameDropWindow(before.copy(fps = null), after))
        assertNull(frameDropWindow(before, after.copy(speed = 1.5)))
        assertNull(frameDropWindow(before.copy(fps = null), after.copy(fps = null, speed = 1.5)))
        assertNull(frameDropWindow(before, after.copy(estimated = true)))
    }

    @Test fun smoothingClearsOldValuesAfterAnInvalidSampleAndDoesNotMixTargets() {
        val healthy = FrameDropSample(50.0, 50.0)
        val slow = FrameDropSample(40.0, 50.0)
        assertEquals(-3.5, smoothFrameDrop(healthy, slow)!!.deviationFps!!, 0.001)
        assertNull(smoothFrameDrop(slow, null))
        assertEquals(slow, smoothFrameDrop(null, slow))
        val changed = FrameDropSample(58.0, 60.0)
        assertEquals(changed, smoothFrameDrop(slow, changed))
        assertEquals(slow.copy(estimated = true), smoothFrameDrop(healthy, slow.copy(estimated = true)))
    }

    @Test fun fallbackKeepsCounterRatesSeparateFromAnUnknownNominalTarget() {
        val start = before.copy(fps = null)
        val current = start.copy(rendered = 145, dropped = 15, realtimeMs = 2_250)
        val sample = frameDropWindow(start, current)!!
        assertEquals(36.0, sample.actualFps, 0.001)
        assertEquals(4.0, sample.droppedFps!!, 0.001)
        assertEquals(10.0, sample.counterDropPercent!!, 0.001)
        assertNull(sample.targetFps)
    }

    @Test fun zeroActivityWithoutATargetDoesNotPretendHealthyOutput() {
        val start = before.copy(fps = null, positionMs = 0)
        val sample = frameDropWindow(start, start.copy(realtimeMs = 2_000))!!
        assertEquals(0.0, sample.actualFps, 0.0)
        assertEquals(0.0, sample.droppedFps!!, 0.0)
        assertNull(sample.counterDropPercent)
        assertNull(sample.deficitPercent)
        assertNull(sample.deviationFps)
    }

    @Test fun missingDropCounterKeepsOutputFpsButCannotClaimNoDrops() {
        val start = before.copy(fps = null, dropped = null)
        val current = after.copy(fps = null, dropped = null)
        val sample = frameDropWindow(start, current)!!
        assertEquals(40.0, sample.actualFps, 0.001)
        assertNull(sample.droppedFps)
        assertNull(sample.counterDropPercent)
        assertNull(sample.deficitPercent)
    }

    @Test fun reportedPicturesWithNoDropsProvideARealHealthyCounterSample() {
        val start = before.copy(fps = null)
        val sample = frameDropWindow(start, after.copy(fps = null, dropped = start.dropped))!!
        assertEquals(40.0, sample.actualFps, 0.001)
        assertEquals(0.0, sample.counterDropPercent!!, 0.0)
        assertEquals(0.0, sample.deficitPercent!!, 0.0)
    }

    @Test fun counterFallbackSmoothingDoesNotReuseAKnownTargetOrMissingCounters() {
        val healthy = FrameDropSample(50.0, null, droppedFps = 0.0)
        val slow = FrameDropSample(40.0, null, droppedFps = 10.0)
        val smoothed = smoothFrameDrop(healthy, slow)!!
        assertEquals(46.5, smoothed.actualFps, 0.001)
        assertEquals(3.5, smoothed.droppedFps!!, 0.001)
        assertEquals(7.0, smoothed.counterDropPercent!!, 0.001)
        assertNull(smoothed.deviationFps)
        assertEquals(slow, smoothFrameDrop(healthy.copy(targetFps = 50.0), slow))
        val unavailable = slow.copy(droppedFps = null)
        assertEquals(unavailable, smoothFrameDrop(slow, unavailable))
        assertNull(smoothFrameDrop(slow, unavailable)!!.deficitPercent)
    }

    @Test fun keepsTheExistingBarScale() {
        assertEquals(1f, frameDropBarFill(null), 0.001f)
        assertEquals(1f, frameDropBarFill(0.0), 0.001f)
        assertEquals(0.5f, frameDropBarFill(FRAME_DROP_WATCHDOG_PERCENT), 0.001f)
        assertEquals(0f, frameDropBarFill(20.0), 0.001f)
        assertEquals(0f, frameDropBarFill(100.0), 0.001f)
    }

    @Test fun formatsSignedLocalizedFpsWithoutNegativeZeroOrRoundedIntegerTargets() {
        assertEquals("−2,2", formatFrameFpsDeviation(-2.2, Locale.GERMANY))
        assertEquals("+2.2", formatFrameFpsDeviation(2.2, Locale.US))
        assertEquals("0,0", formatFrameFpsDeviation(-0.024, Locale.GERMANY))
        assertEquals("0.0", formatFrameFpsDeviation(0.024, Locale.US))
        assertEquals("—", formatFrameFpsDeviation(Double.NaN))
        assertEquals("—", formatFrameFpsDeviation(null))
        assertEquals("23,976", formatFrameTargetFps(23.976, Locale.GERMANY))
        assertEquals("50", formatFrameTargetFps(50.0, Locale.GERMANY))
        assertEquals("—", formatFrameTargetFps(null))
    }

    @Test fun formatsMeasuredOutputAndCounterPercentInTheRequestedLocale() {
        assertEquals("23,9", formatFrameOutputFps(23.94, Locale.GERMANY))
        assertEquals("23.9", formatFrameOutputFps(23.94, Locale.US))
        assertEquals("0,0", formatFrameOutputFps(0.0, Locale.GERMANY))
        assertEquals("0.0", formatFrameOutputFps(-0.0, Locale.US))
        assertEquals("10,0 %", formatFramePercent(10.0, Locale.GERMANY))
        assertEquals("10.0 %", formatFramePercent(10.0, Locale.US))
        for (value in listOf(null, -0.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals("—", formatFrameOutputFps(value, Locale.US))
        }
    }

    @Test fun watchdogPercentStillUsesItsOwnExplicitDenominator() {
        assertEquals(20.0, frameDropPercent(10, 50.0)!!, 0.001)
        assertEquals(0.0, frameDropPercent(0, 50.0)!!, 0.001)
        assertNull(frameDropPercent(null, 50.0))
        assertNull(frameDropPercent(0, 0.0))
        assertNull(frameDropPercent(1, Double.NaN))
    }
}
