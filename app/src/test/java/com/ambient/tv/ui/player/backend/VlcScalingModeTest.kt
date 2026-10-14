package com.ambient.tv.ui.player.backend

import org.junit.Assert.assertEquals
import org.junit.Test

class VlcScalingModeTest {
    @Test fun mapsUiScalerNamesToVlcIntegerModes() {
        assertEquals(4, vlcSwscaleMode("nearest"))
        assertEquals(1, vlcSwscaleMode("bilinear"))
        assertEquals(2, vlcSwscaleMode("bicubic"))
        assertEquals(9, vlcSwscaleMode("lanczos"))
        assertEquals(1, vlcSwscaleMode("unknown"))
    }
}
