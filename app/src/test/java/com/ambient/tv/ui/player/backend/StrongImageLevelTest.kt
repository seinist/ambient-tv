package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.VlcImageOptions
import com.ambient.tv.ui.player.Media3Stage
import com.ambient.tv.ui.player.media3EffectPlan
import org.junit.Assert.*
import org.junit.Test

/** "Strong" is a real level for every strength choice, above Medium on each player. */
class StrongImageLevelTest {
    @Test fun strongSurvivesNormalizationEverywhere() {
        val mpv = MpvImageOptions(deband = "strong", sharpening = "strong", denoise = "strong", deblock = "strong", filmGrain = "strong").normalized()
        assertEquals(listOf("strong", "strong", "strong", "strong", "strong"), listOf(mpv.deband, mpv.sharpening, mpv.denoise, mpv.deblock, mpv.filmGrain))
        val vlc = VlcImageOptions(deband = "strong", denoise = "strong").normalized()
        assertEquals(listOf("strong", "strong"), listOf(vlc.deband, vlc.denoise))
        val media3 = Media3ImageOptions(enabled = true, deband = "strong", sharpening = "strong")
        assertEquals(listOf(Media3Stage.Deband("strong"), Media3Stage.Sharpen("strong")), media3EffectPlan(media3, upscaling = false))
    }

    @Test fun mpvStrongGoesBeyondMedium() {
        assertEquals("0.8", mpvBuiltinSharpen("strong", "lanczos", processing = true, gpuNext = false))
        assertEquals("cas", mpvSharpeningShader("strong", "lanczos", gpuNext = true))
        assertNull(mpvSharpeningShader("strong", "lanczos", gpuNext = false))
        assertEquals("off", mpvSharpeningAfterOverload("strong", listOf("cas")))
        val filters = mpvCpuImageFilters(MpvImageOptions(denoise = "strong", deblock = "strong"), sdr = true)
        assertEquals("lavfi=[hqdn3d=6:4.5:9:6.75]", filters["plex-denoise"])
        assertTrue(filters.getValue("plex-deblock").contains("alpha=0.15"))
    }

    @Test fun vlcStrongUsesStrongerFilterValues() {
        val options = vlcImageMediaOptions(VlcImageOptions(softwareProcessing = true, deband = "strong", denoise = "strong"))
        assertTrue(":gradfun-strength=3.0" in options)
        assertTrue(":hqdn3d-luma-spat=6.0" in options)
    }
}
