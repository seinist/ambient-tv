package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AppPreferences
import org.junit.Assert.*
import org.junit.Test

class HdrDisplayModeTest {
    private val sdr = SupportedDisplayMode("4k24", 1, 3840, 2160, 24f, emptySet())
    private val hdr = SupportedDisplayMode("4k60", 2, 3840, 2160, 60f, setOf(2))

    @Test fun hdrNeverFallsBackToIncompatibleDesktopOrPreferredMode() {
        val eligible = hdrCompatibleDisplayModes(listOf(sdr, hdr), 2)
        for (policy in listOf("system", sdr.key, AppPreferences.DISPLAY_MODE_AUTO_CONTENT, AppPreferences.DISPLAY_MODE_AUTO_DISPLAY)) {
            val selected = DisplayRefreshRateMatcher.selectContentMode(
                eligible, sdr, 24f, 3840, 2160, policy, sdr.key,
                allowThreeTwoPulldown = true,
            )
            assertEquals(hdr, selected)
        }
    }

    @Test fun missingCapabilityIsNotAnExplicitNegative() {
        val unknown = sdr.copy(hdrTypes = null)
        assertEquals(listOf(unknown, hdr), hdrCompatibleDisplayModes(listOf(sdr, unknown, hdr), 2))
        assertTrue(hdrCompatibleDisplayModes(listOf(sdr), 2).isEmpty())
        assertTrue(hdrCompatibleDisplayModes(listOf(hdr), 3).isEmpty())
        assertEquals(listOf(sdr, hdr), hdrCompatibleDisplayModes(listOf(sdr, hdr), null))
    }

    @Test fun whitelistCannotBringAnSdrModeBackIntoHdrSelection() {
        assertNull(DisplayRefreshRateMatcher.selectContentMode(
            hdrCompatibleDisplayModes(listOf(sdr, hdr), 2), sdr, 24f, 3840, 2160,
            allowedModes = setOf(sdr.key),
        ))
    }
}
