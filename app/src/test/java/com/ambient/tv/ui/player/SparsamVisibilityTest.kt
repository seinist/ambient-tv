package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SparsamVisibilityTest {
    @Test fun `sparsam keeps MPV filter rows choosable and only hides motion interpolation`() {
        val full = playerPanelVideoVisibility("mpv", gpuFilters = true, shadersApply = true, media3ScalerApplies = false,
            globalSvp = true)
        val low = full.sparsam(true, mpv = true)
        assertEquals(full.copy(svp = false), low)
        assertTrue(low.deband && low.denoise && low.shaders && low.shaderUpscalers && low.upscaler)
        assertEquals(full, full.sparsam(false, mpv = true))
    }

    @Test fun `sparsam hides VLC CPU filters and the Media3 effect chain`() {
        val vlc = playerPanelVideoVisibility("vlc", false, false, false, vlcSoftware = true).sparsam(true)
        assertFalse(vlc.deband || vlc.denoise || vlc.svp)
        assertTrue(vlc.deinterlace)
        val m3 = playerPanelVideoVisibility("media3", false, false, true, media3SdrAllowed = true).sparsam(true, media3 = true)
        assertFalse(m3.media3Processing || m3.picture || m3.upscaler)
        assertFalse(m3.deband || m3.shaders || m3.pictureGamma)
    }
}
