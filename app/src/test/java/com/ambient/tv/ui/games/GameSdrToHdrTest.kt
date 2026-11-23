package com.ambient.tv.ui.games

import org.junit.Assert.assertEquals
import org.junit.Test

class GameSdrToHdrTest {
    @Test fun autoWhiteIsTheVideoDefaultAndNeverAboveAPlausiblePeak() {
        assertEquals(203, gameSdrWhiteNits(0, GameHdrDisplayCapabilities(hdr10 = true)))
        assertEquals(400, gameSdrWhiteNits(400, GameHdrDisplayCapabilities(hdr10 = true, peakNits = 1000f)))
        assertEquals(350, gameSdrWhiteNits(400, GameHdrDisplayCapabilities(hdr10 = true, peakNits = 350f)))
        // Placeholder peaks below 300 nits are ignored, like for video.
        assertEquals(300, gameSdrWhiteNits(300, GameHdrDisplayCapabilities(hdr10 = true, peakNits = 100f)))
    }
}
