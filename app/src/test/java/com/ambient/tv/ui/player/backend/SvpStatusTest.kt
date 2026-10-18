package com.ambient.tv.ui.player.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SvpStatusTest {
    private val run = SvpDecision.Run(23.976, 60.0)

    private fun state(
        mode: String = "balanced", custom: Boolean = false, unavailable: SvpInactiveReason? = null,
        overloaded: Boolean = false, decision: SvpDecision = run, inChain: Boolean = true,
        runtime: SvpInactiveReason? = null, backend: Int = 0,
    ) = svpState(mode, custom, unavailable, overloaded, decision, inChain, runtime, backend)

    @Test fun activeShowsRatesPresetAndBackend() {
        assertEquals("23.976 → 60 fps · balanced", formatSvpStatus(state()))
        assertEquals("23.976 → 60 fps · custom · Vulkan", formatSvpStatus(state(custom = true, backend = 2)))
        assertTrue(state()!!.active)
    }

    @Test fun inactiveSaysWhyInPriorityOrder() {
        assertNull(state(mode = "off"))
        assertEquals("unavailable (HDR)", formatSvpStatus(state(unavailable = SvpInactiveReason.HDR, overloaded = true)))
        assertEquals("off (load)", formatSvpStatus(state(overloaded = true, runtime = SvpInactiveReason.FORMAT)))
        assertEquals("off (source > 30 fps)", formatSvpStatus(state(decision = SvpDecision.Skip(SvpInactiveReason.SOURCE_RATE_LIMIT))))
        assertEquals("passthrough (format)", formatSvpStatus(state(runtime = SvpInactiveReason.FORMAT)))
        assertEquals("passthrough (timing)", formatSvpStatus(state(runtime = SvpInactiveReason.TIMING)))
        assertEquals("starting", formatSvpStatus(state(inChain = false)))
        assertEquals("not needed (source ≥ target)", formatSvpStatus(state(decision = SvpDecision.Skip(SvpInactiveReason.NOT_NEEDED))))
        assertFalse(state(runtime = SvpInactiveReason.PASSTHROUGH)!!.active)
    }

    @Test fun ratesNeedBothValues() {
        assertNull(svpRates(null, 60.0))
        assertNull(svpRates(0.0, 60.0))
        assertEquals("25 → 50 fps", svpRates(25.0, 50.0))
    }
}
