package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPanelAvPolicyTest {
    @Test
    fun mpvDirectHidesDeinterlaceAndUpscaler() {
        val visibility = playerPanelVideoVisibility("mpv", gpuFilters = false, shadersApply = true, media3ScalerApplies = true)
        assertFalse(visibility.deinterlace)
        assertFalse(visibility.upscaler)
        assertFalse(visibility.shaderUpscalers)
        assertFalse(visibility.denoise)
        assertFalse(visibility.shaders)
        assertFalse(visibility.svp)
        assertFalse(visibility.picture)
        assertFalse(visibility.hasFilters(svpAllowed = true))
    }

    @Test
    fun mpvGpuOnHdrKeepsDeinterlaceAndBuiltinUpscalerButDropsShaders() {
        val visibility = playerPanelVideoVisibility("mpv", gpuFilters = true, shadersApply = false, media3ScalerApplies = false)
        assertTrue(visibility.deinterlace)
        assertTrue(visibility.upscaler)
        assertFalse(visibility.shaderUpscalers)
        assertFalse(visibility.denoise)
        assertFalse(visibility.shaders)
        assertFalse(visibility.picture)
        assertFalse(visibility.svp)
        assertTrue(visibility.deband)
        assertTrue(visibility.hasFilters(svpAllowed = true))
    }

    @Test
    fun mpvGpuOnSdrShowsShaderUpscalers() {
        val visibility = playerPanelVideoVisibility("mpv", gpuFilters = true, shadersApply = true, media3ScalerApplies = false)
        assertTrue(visibility.shaderUpscalers)
        assertTrue(visibility.shaders)
        assertTrue(visibility.denoise)
        assertTrue(visibility.picture)
        assertTrue(visibility.pictureGamma)
        assertTrue(visibility.svp)
    }

    @Test
    fun vlcHidesSvpWithoutTheAmbientSvpFilterBuild() {
        assertTrue(playerPanelVideoVisibility("vlc", gpuFilters = false, shadersApply = false, media3ScalerApplies = false).svp)
        assertFalse(playerPanelVideoVisibility("vlc", gpuFilters = false, shadersApply = false, media3ScalerApplies = false, vlcSvp = false).svp)
    }

    @Test
    fun vlcShowsDeinterlaceAndUpscalerWithoutShaders() {
        val visibility = playerPanelVideoVisibility("vlc", gpuFilters = false, shadersApply = false, media3ScalerApplies = false)
        assertTrue(visibility.deinterlace)
        assertTrue(visibility.upscaler)
        assertFalse(visibility.shaderUpscalers)
        assertFalse(visibility.denoise)
        assertFalse(visibility.picture)
        assertTrue(visibility.svp)
    }

    @Test
    fun vlcSoftwareUnlocksDenoiseAndPicture() {
        val visibility = playerPanelVideoVisibility(
            "vlc", gpuFilters = false, shadersApply = false, media3ScalerApplies = false, vlcSoftware = true,
        )
        assertTrue(visibility.denoise)
        assertTrue(visibility.deband)
        assertTrue(visibility.picture)
        assertTrue(visibility.pictureGamma)
        assertFalse(visibility.shaders)
    }

    @Test
    fun media3HasNoDeinterlaceAndUpscalesOnlyWhenSdrEffectsApply() {
        val blocked = playerPanelVideoVisibility("media3", gpuFilters = true, shadersApply = true, media3ScalerApplies = false)
        assertFalse(blocked.deinterlace)
        assertFalse(blocked.upscaler)
        assertFalse(blocked.picture)
        val allowed = playerPanelVideoVisibility(
            "media3", gpuFilters = false, shadersApply = false, media3ScalerApplies = true, media3SdrAllowed = true,
        )
        assertTrue(allowed.upscaler)
        assertTrue(allowed.picture)
        assertFalse(allowed.pictureGamma)
        assertFalse(allowed.shaderUpscalers)
        assertTrue(allowed.media3Processing)
    }

    @Test
    fun phoneSpeakerHidesBitstreamButKeepsNightWhenTheEngineAppliesIt() {
        val visibility = playerPanelAudioVisibility(supportsNight = true, routeOffersBitstream = false)
        assertTrue(visibility.night)
        assertFalse(visibility.bitstream)
        assertTrue(visibility.delay)
        assertTrue(visibility.channels)
        assertTrue(visibility.equalizer)
    }

    @Test
    fun nightModeHiddenWhenThisEngineDoesNotApplyIt() {
        val visibility = playerPanelAudioVisibility(supportsNight = false, routeOffersBitstream = true)
        assertFalse(visibility.night)
        assertTrue(visibility.bitstream)
    }

    @Test
    fun vlcHidesChannelMixAndPassthroughSuspendsEq() {
        val vlc = playerPanelAudioVisibility(supportsNight = true, routeOffersBitstream = true, engine = "vlc")
        assertTrue(vlc.channels)
        assertTrue(vlc.equalizer)
        assertTrue(vlc.delay)
        val suspended = playerPanelAudioVisibility(
            supportsNight = true, routeOffersBitstream = true, engine = "mpv", dspSuspended = true,
        )
        assertFalse(suspended.night)
        assertTrue(suspended.channels)
        assertFalse(suspended.equalizer)
        assertTrue(suspended.delay)
        assertTrue(suspended.bitstream)
    }

    @Test
    fun emptyOrFullyRejectedRouteCannotBitstream() {
        assertFalse(routeOffersBitstream(emptySet(), emptySet()))
        assertFalse(routeOffersBitstream(setOf("ac3"), setOf("ac3")))
        assertTrue(routeOffersBitstream(setOf("ac3", "eac3"), setOf("ac3")))
    }

    @Test
    fun blockedShaderUpscalerDisplaysTheBuiltinFallback() {
        val shaders = setOf("fsr", "nis")
        assertEquals("lanczos", panelUpscalerKey("fsr", shaders, shadersApply = false))
        assertEquals("fsr", panelUpscalerKey("fsr", shaders, shadersApply = true))
        assertEquals("bilinear", panelUpscalerKey("bilinear", shaders, shadersApply = false))
    }

    @Test
    fun nativeAndDownscaledOutputDoNotOfferUpscalers() {
        assertEquals(VideoScaleRelation.NATIVE, videoScaleRelation(1920, 1080, 1920, 1080))
        assertEquals(VideoScaleRelation.DOWNSCALE, videoScaleRelation(3840, 2160, 1920, 1080))
        assertEquals(VideoScaleRelation.UPSCALE, videoScaleRelation(1280, 720, 1920, 1080))
        assertEquals(VideoScaleRelation.UNKNOWN, videoScaleRelation(null, 1080, 1920, 1080))
        val native = playerPanelVideoVisibility("mpv", true, true, false, scale = VideoScaleRelation.NATIVE)
        assertFalse(native.upscaler)
        assertFalse(native.downscaler)
        val down = playerPanelVideoVisibility("mpv", true, true, false, scale = VideoScaleRelation.DOWNSCALE)
        assertFalse(down.upscaler)
        assertTrue(down.downscaler)
        val vlcSurface = playerPanelVideoVisibility("vlc", false, false, false,
            scale = VideoScaleRelation.UPSCALE, vlcScalerApplies = false)
        assertFalse(vlcSurface.upscaler)
    }

    @Test
    fun toneMappingOnlyForHdrConvertedToSdr() {
        val converted = playerPanelVideoVisibility("mpv", true, false, false, hdrInput = true, hdrOutput = false)
        assertTrue(converted.toneMapping)
        assertFalse(playerPanelVideoVisibility("mpv", true, false, false,
            hdrInput = true, hdrOutput = true).toneMapping)
        assertFalse(playerPanelVideoVisibility("mpv", true, true, false,
            hdrInput = false, hdrOutput = false).toneMapping)
        val vlcHdr = playerPanelVideoVisibility("vlc", false, false, false,
            vlcSoftware = true, hdrInput = true)
        assertFalse(vlcHdr.picture)
        assertFalse(vlcHdr.denoise)
        assertFalse(vlcHdr.deinterlace)
        assertFalse(vlcHdr.upscaler)
        assertFalse(vlcHdr.svp)
    }

    @Test
    fun globalSvpOffHidesSvpEvenWhenRendererSupportsIt() {
        assertTrue(playerPanelVideoVisibility("mpv", true, true, false).svp)
        assertFalse(playerPanelVideoVisibility("mpv", true, true, false, globalSvp = false).svp)
        assertTrue(playerPanelVideoVisibility("vlc", false, false, false).svp)
        assertFalse(playerPanelVideoVisibility("vlc", false, false, false, globalSvp = false).svp)
    }

    @Test
    fun mpvGpuOnHdrOffersOnlyTheHdrSafeShaderRows() {
        val v = playerPanelVideoVisibility("mpv", gpuFilters = true, shadersApply = false, media3ScalerApplies = false, hdrInput = true)
        assertTrue(v.hdrShaders)
        assertTrue(v.shaders)
        assertTrue(v.shaderUpscalers)
        assertTrue(v.deband)
        assertFalse(v.denoise)
        assertFalse(v.picture)
        assertFalse(v.svp)
        assertFalse(playerPanelVideoVisibility("mpv", gpuFilters = true, shadersApply = true, media3ScalerApplies = false).hdrShaders)
        assertFalse(playerPanelVideoVisibility("mpv", gpuFilters = false, shadersApply = false, media3ScalerApplies = false, hdrInput = true).shaders)
        assertFalse(v.sparsam(true).hdrShaders)
        val hdrSafe = com.ambient.tv.ui.player.backend.MPV_HDR_UPSCALERS
        val all = com.ambient.tv.data.local.VideoImageCatalog.mpvShaders
        assertEquals(hdrSafe, panelShaderUpscalers(all, hdr = true, hdrSafe = hdrSafe).keys)
        assertEquals(all, panelShaderUpscalers(all, hdr = false, hdrSafe = hdrSafe))
        assertEquals("ravu_lite_r2", panelUpscalerKey("ravu_lite_r2", all.keys, shadersApply = false, stillRunning = hdrSafe))
        assertEquals("lanczos", panelUpscalerKey("artcnn16", all.keys, shadersApply = false, stillRunning = hdrSafe))
    }
}
