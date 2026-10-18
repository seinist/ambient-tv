package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.SvpOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SvpRuntimePolicyTest {
    @Test fun onlyTheEngineComputeBypassIsAnOverload() {
        assertTrue(mpvSvpNativeOverloaded(SvpNativeReason.BYPASS_OVERLOAD, 0L, 0.0))
        // Old adapters reported 3 for format/timing passthrough and engine errors: never "too slow".
        for (reason in listOf(0, SvpNativeReason.BYPASS_GATE, 3, 4)) assertFalse("$reason", mpvSvpNativeOverloaded(reason, 0L, 0.0))
        assertTrue(mpvSvpNativeOverloaded(0, 2_000_000L, 2.0))
        assertFalse(mpvSvpNativeOverloaded(0, 1_000_000L, 2.0))
        assertTrue(mpvRenderOverloaded(0, 60.0, 2.0, svpBypassReason = 1))
        assertFalse(mpvRenderOverloaded(0, 60.0, 2.0, svpBypassReason = 3))
    }

    @Test fun adapterSignalsMapToHonestReasons() {
        assertNull(svpRuntimeReason(0, 0))
        assertEquals(SvpInactiveReason.OVERLOAD, svpRuntimeReason(1, 0))
        assertEquals(SvpInactiveReason.FORMAT, svpRuntimeReason(0, SvpNativeReason.PASSTHROUGH_FORMAT))
        assertEquals(SvpInactiveReason.TIMING, svpRuntimeReason(0, SvpNativeReason.PASSTHROUGH_TIMING))
        assertEquals(SvpInactiveReason.PASSTHROUGH, svpRuntimeReason(0, SvpNativeReason.PASSTHROUGH_UNAVAILABLE))
        assertEquals(SvpInactiveReason.PASSTHROUGH, svpRuntimeReason(0, SvpNativeReason.PASSTHROUGH_ERROR))
        assertEquals(SvpInactiveReason.PASSTHROUGH, svpRuntimeReason(3, 0))
        assertEquals(SvpInactiveReason.PERFORMANCE_MODE, svpRuntimeReason(SvpNativeReason.BYPASS_GATE, 0))
    }

    @Test fun displayMatchingIsOnlyHeldForAnSvpThatRunsOrCanStillRun() {
        fun intended(modeOn: Boolean = true, overloaded: Boolean = false, hdr: Boolean = false, decided: Boolean = true,
                     inChain: Boolean = true, runtime: SvpInactiveReason? = null) =
            mpvSvpInterpolationIntended(modeOn, overloaded, hdr, decided, inChain, runtime)
        assertTrue(intended())
        assertTrue(intended(decided = false, inChain = false)) // Before the decision, keep the display rate.
        assertFalse(intended(modeOn = false))
        assertFalse(intended(hdr = true, decided = false))   // HDR 24p may switch to 24 Hz.
        assertFalse(intended(overloaded = true))
        assertFalse(intended(inChain = false))                // Not attached for this file.
        assertFalse(intended(runtime = SvpInactiveReason.FORMAT))
        assertFalse(intended(runtime = SvpInactiveReason.OVERLOAD))
        assertTrue(intended(runtime = SvpInactiveReason.TIMING)) // Transient gap: no mode switch.
    }

    @Test fun decisionExplainsEverySkip() {
        val all = SvpOptions(filmOnly = false)
        assertEquals(SvpDecision.Run(24.0, 60.0), svpDecision(24.0, 60.0))
        assertEquals(SvpDecision.Skip(SvpInactiveReason.SOURCE_RATE_UNKNOWN), svpDecision(null, 60.0))
        assertEquals(SvpDecision.Skip(SvpInactiveReason.SOURCE_RATE_LIMIT), svpDecision(50.0, 100.0))
        assertEquals(SvpDecision.Run(50.0, 100.0), svpDecision(50.0, 100.0, all))
        assertEquals(SvpDecision.Skip(SvpInactiveReason.NOT_NEEDED), svpDecision(30.0, 30.0))
        assertEquals(SvpDecision.Skip(SvpInactiveReason.DISPLAY_RATE_UNKNOWN), svpDecision(24.0, null))
        assertEquals(SvpDecision.Skip(SvpInactiveReason.RESOLUTION), svpDecision(24.0, 60.0, width = 3840, height = 2160))
        assertEquals(SvpDecision.Run(24.0, 60.0), svpDecision(24.0, 60.0, SvpOptions(maxHeight = 2160), 3840, 2160))
        assertEquals(SvpDecision.Skip(SvpInactiveReason.SOURCE_RATE_UNKNOWN), svpDecision(10.0, 60.0))
    }

    @Test fun resolutionLimitAllowsCodecPaddingAndWideFormats() {
        val hd = SvpOptions()
        assertTrue(svpWithinResolution(1920, 1088, hd))
        assertTrue(svpWithinResolution(1920, 800, hd))
        assertTrue(svpWithinResolution(null, null, hd))
        assertFalse(svpWithinResolution(2560, 1080, hd))
        assertFalse(svpWithinResolution(1920, 1080, SvpOptions(maxHeight = 720)))
        assertTrue(svpWithinResolution(1280, 720, SvpOptions(maxHeight = 720)))
        assertNull(mpvSvpFilter("balanced", 24.0, 60.0, hd, 3840, 2160))
    }

    @Test fun atMostSixtyKeepsAnIntegerDisplayCadence() {
        val cap = SvpOptions(rateDivisor = 3)
        assertEquals(60.0, svpOutputFps(120.0, cap)!!, 1e-9)
        assertEquals(50.0, svpOutputFps(100.0, cap)!!, 1e-9)
        assertEquals(60.0, svpOutputFps(60.0, cap)!!, 1e-9)
        assertEquals(50.0, svpOutputFps(50.0, cap)!!, 1e-9)
        assertEquals(60000.0 / 1001, svpOutputFps(120000.0 / 1001, cap)!!, 1e-9)
        assertEquals(30.0, svpOutputFps(60.0, SvpOptions(rateDivisor = 2))!!, 1e-9)
        assertNull(svpOutputFps(144.0, cap))
    }

    @Test fun vlcHonoursKnownLimitsButKeepsUnknownSources() {
        val o = SvpOptions()
        assertTrue(vlcSvpApplies(null, 60.0, o, null, null))
        assertFalse(vlcSvpApplies(50.0, 100.0, o, null, null))
        assertTrue(vlcSvpApplies(50.0, 100.0, o.copy(filmOnly = false), null, null))
        assertFalse(vlcSvpApplies(24.0, 60.0, o, 3840, 2160))
        assertFalse(vlcSvpApplies(60.0, 60.0, o, 1920, 1080))
    }

    @Test fun probeKeepsOutputLimitsAndDevice() {
        val tuning = SvpOptions(rateDivisor = 3, algorithm = 23, block = 8, maxHeight = 720, filmOnly = false, backend = 2, threads = 3)
        assertEquals(SvpOptions(rateDivisor = 3, maxHeight = 720, filmOnly = false, backend = 2, threads = 3), svpProbeOptions(tuning))
    }
}
