package com.ambient.tv.ui.player

import com.ambient.tv.R
import com.ambient.tv.data.local.SvpOptions
import com.ambient.tv.ui.player.backend.SvpInactiveReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SvpControlsPolicyTest {
    private val custom = SvpOptions(algorithm = 21, block = 8, artifacts = 2, rateDivisor = 3, backend = 2)

    @Test fun presetKeyShowsCustomOnlyForAnalysisOverrides() {
        assertEquals("off", svpPresetKey("off", custom))
        assertEquals("custom", svpPresetKey("balanced", custom))
        assertEquals("balanced", svpPresetKey("balanced", SvpOptions(artifacts = 3, scene = 1, rateDivisor = 2, backend = 1, threads = 2)))
        assertEquals("quality", svpPresetKey("quality", SvpOptions(search = 0)))
        assertEquals("custom", svpPresetKey("light", SvpOptions(search = 2)))
    }

    @Test fun choosingAPresetClearsAnalysisButKeepsOutputAndDevice() {
        val (mode, options) = svpApplyPresetChoice("quality", "balanced", custom)
        assertEquals("quality", mode)
        assertEquals(SvpOptions(artifacts = 2, rateDivisor = 3, backend = 2), options)
        assertEquals("off" to custom, svpApplyPresetChoice("off", "balanced", custom))
        // "Custom" keeps the values and turns an off SVP on with the balanced base.
        assertEquals("balanced" to custom, svpApplyPresetChoice("custom", "off", custom))
        assertEquals("light" to custom, svpApplyPresetChoice("custom", "light", custom))
    }

    @Test fun modeLabelsAreLocalizedPresetNames() {
        assertEquals(R.string.svp_mode_light, svpModeLabelRes("light"))
        assertEquals(R.string.svp_mode_light, svpModeLabelRes("auto"))
        assertEquals(R.string.svp_mode_balanced, svpModeLabelRes("balanced"))
        assertEquals(R.string.svp_mode_smooth, svpModeLabelRes("quality"))
        assertEquals(R.string.player_off, svpModeLabelRes("off"))
    }

    @Test fun uiUnavailabilityNamesPlayerBeforePerformanceMode() {
        assertEquals(SvpInactiveReason.ENGINE, svpUiUnavailable(engineSupportsSvp = false, maximumPerformance = false))
        assertEquals(SvpInactiveReason.PERFORMANCE_MODE, svpUiUnavailable(engineSupportsSvp = true, maximumPerformance = false))
        assertNull(svpUiUnavailable(engineSupportsSvp = true, maximumPerformance = true))
    }
}
