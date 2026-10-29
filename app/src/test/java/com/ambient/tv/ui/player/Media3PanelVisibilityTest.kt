package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3PanelVisibilityTest {
    private fun media3(
        enabled: Boolean = false,
        gpuEffectsSupported: Boolean = true,
        sdrEffectsAllowed: Boolean = true,
        reduced: Boolean = false,
        scale: VideoScaleRelation = VideoScaleRelation.UNKNOWN,
    ): PlayerPanelVideoVisibility {
        val supported = gpuEffectsSupported && sdrEffectsAllowed
        return playerPanelVideoVisibility(
            engine = "media3",
            gpuFilters = false,
            shadersApply = false,
            media3ScalerApplies = supported && enabled,
            media3SdrAllowed = supported,
            scale = scale,
        ).sparsam(reduced, media3 = true)
    }

    private fun assertNoActualFilters(visibility: PlayerPanelVideoVisibility) {
        assertFalse(visibility.upscaler)
        assertFalse(visibility.deinterlace)
        assertFalse(visibility.denoise)
        assertFalse(visibility.shaders)
        assertFalse(visibility.deband)
        assertFalse(visibility.picture)
        assertFalse(visibility.pictureGamma)
    }

    @Test fun defaultOffStillOffersTheFilterEntryAndProcessingSwitch() {
        val visibility = media3()
        assertTrue(visibility.hasFilterSection(media3 = true))
        assertTrue(visibility.media3Processing)
        assertNoActualFilters(visibility)
        assertFalse(visibility.hasFilters(svpAllowed = false))
        assertNull(media3FiltersUnavailableReason(gpuEffectsSupported = true, sdrEffectsAllowed = true, reduced = false))
    }

    @Test fun enablingAndDisablingProcessingNeverRemovesItsFilterSection() {
        for (enabled in listOf(false, true, false)) {
            val visibility = media3(enabled = enabled)
            assertTrue(visibility.hasFilterSection(media3 = true))
            assertTrue(visibility.media3Processing)
            assertEquals(enabled, visibility.shaders)
            assertEquals(enabled, visibility.deband)
            assertEquals(enabled, visibility.picture)
        }
    }

    @Test fun nativeAndDownscaledVideoKeepSharpeningAndOtherNonScalingControls() {
        for (scale in listOf(VideoScaleRelation.NATIVE, VideoScaleRelation.DOWNSCALE)) {
            val visibility = media3(enabled = true, scale = scale)
            assertFalse(scale.name, visibility.upscaler)
            assertTrue(scale.name, visibility.hasFilterSection(media3 = true))
            assertTrue(scale.name, visibility.shaders)
            assertTrue(scale.name, visibility.deband)
            assertTrue(scale.name, visibility.picture)
            assertTrue(scale.name, visibility.pictureGamma)
        }
    }

    @Test fun unsupportedDeviceKeepsAnExplanationEntryWithoutOfferingInactiveFilters() {
        val visibility = media3(enabled = true, gpuEffectsSupported = false)
        assertTrue(visibility.hasFilterSection(media3 = true))
        assertFalse(visibility.media3Processing)
        assertNoActualFilters(visibility)
        assertEquals(Media3FiltersUnavailableReason.DEVICE,
            media3FiltersUnavailableReason(gpuEffectsSupported = false, sdrEffectsAllowed = true, reduced = false))
    }

    @Test fun unsupportedSourceKeepsAnExplanationEntryWithoutOfferingInactiveFilters() {
        val visibility = media3(enabled = true, sdrEffectsAllowed = false)
        assertTrue(visibility.hasFilterSection(media3 = true))
        assertFalse(visibility.media3Processing)
        assertNoActualFilters(visibility)
        assertEquals(Media3FiltersUnavailableReason.SOURCE,
            media3FiltersUnavailableReason(gpuEffectsSupported = true, sdrEffectsAllowed = false, reduced = false))
    }

    @Test fun reducedPerformanceKeepsAnExplanationEntryWhileTheEffectChainStaysOff() {
        val visibility = media3(enabled = true, reduced = true)
        assertTrue(visibility.hasFilterSection(media3 = true))
        assertFalse(visibility.media3Processing)
        assertNoActualFilters(visibility)
        assertEquals(Media3FiltersUnavailableReason.PERFORMANCE,
            media3FiltersUnavailableReason(gpuEffectsSupported = true, sdrEffectsAllowed = true, reduced = true))
    }

    @Test fun unavailableReasonPrioritizesDeviceThenPerformanceThenSource() {
        assertEquals(Media3FiltersUnavailableReason.DEVICE,
            media3FiltersUnavailableReason(gpuEffectsSupported = false, sdrEffectsAllowed = false, reduced = true))
        assertEquals(Media3FiltersUnavailableReason.PERFORMANCE,
            media3FiltersUnavailableReason(gpuEffectsSupported = true, sdrEffectsAllowed = false, reduced = true))
        assertEquals(Media3FiltersUnavailableReason.SOURCE,
            media3FiltersUnavailableReason(gpuEffectsSupported = true, sdrEffectsAllowed = false, reduced = false))
    }

    @Test fun mpvDirectOutputStillHidesItsUnsupportedFilterSection() {
        val visibility = playerPanelVideoVisibility("mpv", gpuFilters = false,
            shadersApply = true, media3ScalerApplies = false)
        assertFalse(visibility.hasFilterSection())
    }

    @Test fun vlcKeepsItsDeinterlaceSectionAndHidesItForHdr() {
        val sdr = playerPanelVideoVisibility("vlc", gpuFilters = false,
            shadersApply = false, media3ScalerApplies = false)
        assertTrue(sdr.deinterlace)
        assertTrue(sdr.hasFilterSection())
        val hdr = playerPanelVideoVisibility("vlc", gpuFilters = false,
            shadersApply = false, media3ScalerApplies = false, hdrInput = true)
        assertFalse(hdr.hasFilterSection())
    }

    @Test fun svpAloneBelongsToMotionAndDoesNotCreateAFilterSection() {
        val visibility = PlayerPanelVideoVisibility(deinterlace = false,
            upscaler = false, shaderUpscalers = false, svp = true)
        assertTrue(visibility.hasFilters(svpAllowed = true))
        assertFalse(visibility.hasFilterSection())
    }
}
