package com.ambient.tv.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeBackgroundSettingsTest {
    @Test fun optionalBackgroundYieldsToReducedEffectsAndPlayback() {
        val enabled = HomeBackgroundSettings(everywhere = true)
        assertFalse(HomeBackgroundSettings().visible(false, false, false))
        assertTrue(enabled.visible(false, false, false))
        assertFalse(enabled.visible(true, false, false))
        assertFalse(enabled.visible(false, true, false))
        assertFalse(enabled.visible(false, false, true))
        assertFalse(enabled.copy(intensity = 0).visible(false, false, false))
        assertFalse(enabled.copy(style = HomeHeroStyle.STATIC).visible(false, false, false))
    }
}
