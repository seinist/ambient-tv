package com.ambient.tv.ui.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceProfileTest {
    @Test
    fun svpRequiresExplicitMaximumModeOnEveryDeviceClass() {
        for (mode in PerformanceMode.entries) for (lowEnd in listOf(false, true)) {
            assertEquals(mode == PerformanceMode.MAXIMUM,
                PerformanceProfile.resolve(mode, lowEnd).allowSvpInterpolation)
        }
        assertFalse(PerformanceProfile.Full.allowSvpInterpolation)
        assertEquals(PerformanceMode.MAXIMUM, PerformanceMode.fromKey("maximum"))
    }
    @Test
    fun lowAlwaysReducesEvenOnHighEnd() {
        val profile = PerformanceProfile.resolve(PerformanceMode.LOW, lowEndDevice = false)
        assertTrue(profile.reducedEffects)
        assertFalse(profile.allowBlur)
        assertFalse(profile.allowSoftShadow)
        assertFalse(profile.allowSpringFocus)
        assertFalse(profile.allowComplexHero)
        assertFalse(profile.allowRowFadeSaveLayer)
        assertFalse(profile.allowHighQualityAssets)
        assertTrue(profile.constrainImageDecodeSize)
        assertTrue(profile.throttleProjectM)
        assertTrue(profile.pollOsdPositionOnlyWhenChromeVisible)
        assertTrue(profile.gateExpensiveVideoVisuals)
        assertTrue(profile.forceScreensaverEconomy)
        assertTrue(profile.throttleSpectrumVisualizer)
    }

    @Test
    fun balancedForcesFullFeaturesOnLowEnd() {
        val profile = PerformanceProfile.resolve(PerformanceMode.BALANCED, lowEndDevice = true)
        assertFalse(profile.reducedEffects)
        assertTrue(profile.allowBlur)
        assertTrue(profile.allowHighQualityAssets)
        assertFalse(profile.throttleProjectM)
        assertFalse(profile.pollOsdPositionOnlyWhenChromeVisible)
        assertFalse(profile.gateExpensiveVideoVisuals)
        assertFalse(profile.forceScreensaverEconomy)
        assertFalse(profile.throttleSpectrumVisualizer)
    }

    @Test
    fun autoFollowsDeviceClass() {
        val low = PerformanceProfile.resolve(PerformanceMode.AUTO, lowEndDevice = true)
        val full = PerformanceProfile.resolve(PerformanceMode.AUTO, lowEndDevice = false)
        assertTrue(low.reducedEffects)
        assertFalse(full.reducedEffects)
        assertEquals(PerformanceMode.AUTO, low.mode)
        assertEquals(PerformanceMode.AUTO, full.mode)
    }

    @Test
    fun fromKeyFallsBackToAuto() {
        assertEquals(PerformanceMode.AUTO, PerformanceMode.fromKey(null))
        assertEquals(PerformanceMode.AUTO, PerformanceMode.fromKey("nope"))
        assertEquals(PerformanceMode.BALANCED, PerformanceMode.fromKey("balanced"))
        assertEquals(PerformanceMode.LOW, PerformanceMode.fromKey("low"))
    }

    @Test
    fun interfaceAndPlaybackModesAreIndependent() {
        val uiLow = PerformanceProfile.resolve(
            PerformanceMode.LOW,
            lowEndDevice = false,
            playbackMode = PerformanceMode.MAXIMUM,
        )
        assertTrue(uiLow.reducedEffects)
        assertFalse(uiLow.allowBlur)
        assertFalse(uiLow.allowSpringFocus)
        assertTrue(uiLow.throttleProjectM)
        assertTrue(uiLow.forceScreensaverEconomy)
        assertFalse(uiLow.reducedPlayback)
        assertFalse(uiLow.gateExpensiveVideoVisuals)
        assertFalse(uiLow.pollOsdPositionOnlyWhenChromeVisible)
        assertFalse(uiLow.throttleSpectrumVisualizer)
        assertTrue(uiLow.allowSvpInterpolation)

        val playbackLow = PerformanceProfile.resolve(
            PerformanceMode.MAXIMUM,
            lowEndDevice = true,
            playbackMode = PerformanceMode.LOW,
        )
        assertFalse(playbackLow.reducedEffects)
        assertTrue(playbackLow.allowBlur)
        assertFalse(playbackLow.throttleProjectM)
        assertTrue(playbackLow.reducedPlayback)
        assertTrue(playbackLow.gateExpensiveVideoVisuals)
        assertTrue(playbackLow.pollOsdPositionOnlyWhenChromeVisible)
        assertFalse(playbackLow.allowSvpInterpolation)
        assertEquals(PerformanceMode.MAXIMUM, playbackLow.mode)
        assertEquals(PerformanceMode.LOW, playbackLow.playbackMode)
    }

    @Test
    fun probeUsesExistingLevelsOnly() {
        assertEquals(PerformanceMode.MAXIMUM, PerformanceProbe.classify(0))
        assertEquals(PerformanceMode.MAXIMUM, PerformanceProbe.classify(PerformanceProbe.BALANCED_AT_MS - 1))
        assertEquals(PerformanceMode.BALANCED, PerformanceProbe.classify(PerformanceProbe.BALANCED_AT_MS))
        assertEquals(PerformanceMode.BALANCED, PerformanceProbe.classify(PerformanceProbe.LOW_AT_MS - 1))
        assertEquals(PerformanceMode.LOW, PerformanceProbe.classify(PerformanceProbe.LOW_AT_MS))
        assertEquals(PerformanceMode.LOW, PerformanceProbe.classify(5_000))
    }
}
