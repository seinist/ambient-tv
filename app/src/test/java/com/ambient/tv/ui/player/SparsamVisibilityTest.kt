package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SparsamVisibilityTest {
    @Test fun `sparsam hides MPV filter rows but keeps deinterlace, scaler and picture`() {
        val full = playerPanelVideoVisibility("mpv", gpuFilters = true, shadersApply = true, media3ScalerApplies = false)
        val low = full.sparsam(true)
        assertFalse(low.deband || low.denoise || low.shaders || low.shaderUpscalers || low.svp)
        assertTrue(low.deinterlace && low.upscaler && low.picture)
        assertEquals(full, full.sparsam(false))
    }

    @Test fun `sparsam hides VLC CPU filters and the Media3 effect chain`() {
        val vlc = playerPanelVideoVisibility("vlc", false, false, false, vlcSoftware = true).sparsam(true)
        assertFalse(vlc.deband || vlc.denoise || vlc.svp)
        assertTrue(vlc.deinterlace)
        val m3 = playerPanelVideoVisibility("media3", false, false, true, media3SdrAllowed = true).sparsam(true, media3 = true)
        assertFalse(m3.media3Processing || m3.picture || m3.upscaler)
    }
}
