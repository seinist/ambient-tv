package com.ambient.tv.ui.player
import com.ambient.tv.data.local.AppPreferences
import org.junit.Assert.*
import org.junit.Test
class DisplayRateUpgradeTest {
    @Test fun adaptiveUpgradeCannotStayOnThirtyHertz() {
        assertTrue(displayRateUpgradeNeeded("start_stop", AppPreferences.DISPLAY_MODE_AUTO_DISPLAY, 30f, 60f))
        assertTrue(displayRateUpgradeNeeded("start", AppPreferences.DISPLAY_MODE_AUTO_CONTENT, 29.97f, 59.94f))
    }
    @Test fun fixedOutputAndAdequateRefreshArePreserved() {
        assertFalse(displayRateUpgradeNeeded("off", AppPreferences.DISPLAY_MODE_AUTO_DISPLAY, 30f, 60f))
        assertTrue(displayRateUpgradeNeeded("start_stop", "system", 30f, 60f))
        assertFalse(displayRateUpgradeNeeded("start_stop", "3840x2160@30.000", 30f, 60f))
        assertFalse(displayRateUpgradeNeeded("start_stop", AppPreferences.DISPLAY_MODE_AUTO_DISPLAY, 60f, 30f))
        assertFalse(displayRateUpgradeNeeded("start_stop", AppPreferences.DISPLAY_MODE_AUTO_DISPLAY, 59.94f, 60f))
        assertFalse(displayRateUpgradeNeeded("start_stop", AppPreferences.DISPLAY_MODE_AUTO_DISPLAY, null, 60f))
    }
}
