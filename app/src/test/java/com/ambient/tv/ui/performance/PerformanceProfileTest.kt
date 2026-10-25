package com.ambient.tv.ui.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceProfileTest {
    @Test
    fun svpRunsInMaximumAndInAutoOnFullDevices() {
        for (mode in PerformanceMode.entries) for (lowEnd in listOf(false, true)) {
            val expected = mode == PerformanceMode.MAXIMUM || (mode == PerformanceMode.AUTO && !lowEnd)
            assertEquals("$mode lowEnd=$lowEnd", expected,
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
        assertFalse(profile.allowFocusedItemShadow)
    }

    @Test
    fun focusedItemShadowIsMaximumUiOnly() {
        PerformanceMode.entries.forEach { mode ->
            assertEquals(
                mode == PerformanceMode.MAXIMUM,
                PerformanceProfile.resolve(mode, lowEndDevice = false).allowFocusedItemShadow,
            )
        }
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
    fun autoReducedFlagOnlyForAutoOnLowEnd() {
        assertTrue(PerformanceProfile.resolve(PerformanceMode.AUTO, lowEndDevice = true).playbackAutoReduced)
        assertFalse(PerformanceProfile.resolve(PerformanceMode.AUTO, lowEndDevice = false).playbackAutoReduced)
        assertFalse(PerformanceProfile.resolve(PerformanceMode.LOW, lowEndDevice = true).playbackAutoReduced)
        assertFalse(PerformanceProfile.resolve(PerformanceMode.MAXIMUM, lowEndDevice = true,
            playbackMode = PerformanceMode.AUTO).copy(reducedPlayback = false).playbackAutoReduced)
    }

    @Test
    fun mpvCheapDefaultsFollowSparsamOrLowEndDevice() {
        assertTrue(PerformanceProfile.resolve(PerformanceMode.AUTO, lowEndDevice = true, playbackMode = PerformanceMode.BALANCED).mpvCheapDefaults)
        assertTrue(PerformanceProfile.resolve(PerformanceMode.AUTO, lowEndDevice = false, playbackMode = PerformanceMode.LOW).mpvCheapDefaults)
        assertFalse(PerformanceProfile.resolve(PerformanceMode.AUTO, lowEndDevice = false, playbackMode = PerformanceMode.BALANCED).mpvCheapDefaults)
        // P1: playback Maximum is the explicit full-quality choice, also on a low-end device.
        assertFalse(PerformanceProfile.resolve(PerformanceMode.AUTO, lowEndDevice = true, playbackMode = PerformanceMode.MAXIMUM).mpvCheapDefaults)
        assertTrue(PerformanceProfile.resolve(PerformanceMode.AUTO, lowEndDevice = true, playbackMode = PerformanceMode.AUTO).mpvCheapDefaults)
        // A deliberate scaler/deband pick wins over the cheap defaults.
        assertTrue(com.ambient.tv.ui.player.mpvCheapDefaultsApply(profileCheapDefaults = true, explicitChoice = false))
        assertFalse(com.ambient.tv.ui.player.mpvCheapDefaultsApply(profileCheapDefaults = true, explicitChoice = true))
        assertFalse(com.ambient.tv.ui.player.mpvCheapDefaultsApply(profileCheapDefaults = false, explicitChoice = false))
    }

    @Test
    fun lowEndIsLowRamOrUnderTwoGibOnly() {
        val gib = 1024L * 1024L * 1024L
        assertTrue(isLowEndDevice(lowRamDevice = true, totalMemBytes = 8 * gib))
        assertTrue(isLowEndDevice(lowRamDevice = false, totalMemBytes = 2 * gib - 1))
        assertTrue(isLowEndDevice(lowRamDevice = false, totalMemBytes = 1 * gib))
        // 2–4 GiB TVs/SHIELD with a 192 MB memoryClass are full devices now.
        assertFalse(isLowEndDevice(lowRamDevice = false, totalMemBytes = 2 * gib))
        assertFalse(isLowEndDevice(lowRamDevice = false, totalMemBytes = 3 * gib))
        assertFalse(isLowEndDevice(lowRamDevice = false, totalMemBytes = 0))
    }

    @Test
    fun thirtyTwoBitOnlyUserspaceIsLowEnd() {
        val gib = 1024L * 1024L * 1024L
        // Philips TA1 (armeabi-v7a only, 2.7 GiB) vs SHIELD (arm64, 2.9 GiB).
        assertTrue(isLowEndDevice(lowRamDevice = false, totalMemBytes = 2_852_964L * 1024L, supports64Bit = false))
        assertFalse(isLowEndDevice(lowRamDevice = false, totalMemBytes = 3_023_620L * 1024L, supports64Bit = true))
        assertTrue(isLowEndDevice(lowRamDevice = false, totalMemBytes = 0, supports64Bit = false))
        assertFalse(isLowEndDevice(lowRamDevice = false, totalMemBytes = 3 * gib, supports64Bit = true))
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
