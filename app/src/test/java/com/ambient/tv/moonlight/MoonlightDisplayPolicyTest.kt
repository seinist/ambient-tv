package com.ambient.tv.moonlight

import com.ambient.tv.ui.moonlight.selectMoonlightDisplayMode
import com.ambient.tv.ui.player.DisplayRefreshRateMatcher
import com.ambient.tv.ui.player.SupportedDisplayMode
import org.junit.Assert.*
import org.junit.Test

class MoonlightDisplayPolicyTest {
    private fun mode(id: Int, w: Int, h: Int, hz: Float, hdr: Set<Int>? = setOf(2)) =
        SupportedDisplayMode(DisplayRefreshRateMatcher.modeKey(w, h, hz), id, w, h, hz, hdr)
    private val uhd60 = mode(1, 3840, 2160, 60f)
    private val uhd120 = mode(2, 3840, 2160, 120f, emptySet())
    private val hd60 = mode(3, 1920, 1080, 60f)
    private val hd120 = mode(4, 1920, 1080, 120f)
    private val hd50 = mode(5, 1920, 1080, 50f)
    private val all = listOf(uhd60, uhd120, hd60, hd120, hd50)
    private val profile = MoonlightStreamProfile(matchDisplayRefresh = true)

    @Test fun offByDefaultAndStreamSizeRemainsIndependent() {
        assertNull(selectMoonlightDisplayMode(MoonlightStreamProfile(), all, uhd60, false))
        val stream = profile.copy(width = 3840, height = 2160, displayOutputMode = MoonlightStreamProfile.DISPLAY_PERFORMANCE)
        assertEquals(hd60, selectMoonlightDisplayMode(stream, all, uhd60, false))
        assertEquals(3840, stream.width)
        assertEquals(60, stream.fps)
    }

    @Test fun matchingAndDoubleRatePreserveNativeStreamFps() {
        assertEquals(uhd120, selectMoonlightDisplayMode(profile.copy(fps = 120), all, uhd60, false))
        assertEquals(uhd60, selectMoonlightDisplayMode(profile, all, uhd60, false))
        assertEquals(uhd120, selectMoonlightDisplayMode(profile.copy(preferDoubleDisplayRefresh = true), all, uhd60, false))
        assertEquals(hd50, selectMoonlightDisplayMode(profile.copy(fps = 50), all, hd60, false))
        assertNull(selectMoonlightDisplayMode(profile.copy(fps = 90), all, uhd60, false))
    }

    @Test fun hdrWhitelistAndHandheldRestrictions() {
        val manual = profile.copy(displayOutputMode = uhd120.key)
        assertNull(selectMoonlightDisplayMode(manual, all, uhd60, false, requiredHdrType = 2))
        assertNull(selectMoonlightDisplayMode(manual, all, uhd60, false, allowed = setOf(uhd60.key)))
        assertNull(selectMoonlightDisplayMode(manual, all, hd60, true))
        assertEquals(hd120, selectMoonlightDisplayMode(profile.copy(fps = 120), all, hd60, true))
        assertEquals(uhd60, selectMoonlightDisplayMode(profile.copy(preferDoubleDisplayRefresh = true),
            all, uhd60, false, requiredHdrType = 2))
    }

    @Test fun resolutionOnlyPreservesRateAndManualRequiresAnAdvertisedMode() {
        assertEquals(hd60, selectMoonlightDisplayMode(MoonlightStreamProfile(displayOutputMode =
            MoonlightStreamProfile.DISPLAY_STREAM), all, uhd60, false))
        assertEquals(hd50, selectMoonlightDisplayMode(profile.copy(displayOutputMode = hd50.key), all, uhd60, false))
        assertNull(selectMoonlightDisplayMode(profile.copy(displayOutputMode = "1920x1080@90.000"), all, uhd60, false))
    }

    @Test fun invalidSyncedModeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { profile.copy(displayOutputMode = "bogus").validated() }
        assertEquals(hd120.key, profile.copy(displayOutputMode = hd120.key).validated().displayOutputMode)
    }
}
