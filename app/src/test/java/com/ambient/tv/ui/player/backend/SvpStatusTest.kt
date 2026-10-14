package com.ambient.tv.ui.player.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SvpStatusTest {
    private fun status(mode: String = "balanced", allowed: Boolean = true, active: Boolean = true, overloaded: Boolean = false,
                       source: Double? = 23.976, target: Double? = 60.0, bypass: Int = 0) =
        formatSvpStatus(mode, allowed, active, overloaded, source, target, bypass)

    @Test fun activeShowsRatesPresetAndBypass() {
        assertEquals("23.976 → 60 fps · balanced", status())
        assertEquals("23.976 → 60 fps", status(mode = "auto"))
        assertEquals("25 → 50 fps · quality · bypassed", status(mode = "quality", source = 25.0, target = 50.0, bypass = 2))
    }

    @Test fun inactiveSaysWhy() {
        assertNull(status(mode = "off"))
        assertEquals("unavailable", status(allowed = false))
        assertEquals("off (load)", status(overloaded = true))
        assertEquals("waiting for source rate", status(active = false, source = null))
        assertEquals("display rate unknown", status(active = false, target = null))
        assertEquals("not needed (source ≥ display)", status(active = false, source = 60.0, target = 60.0))
        assertEquals("starting", status(active = false))
    }
}
