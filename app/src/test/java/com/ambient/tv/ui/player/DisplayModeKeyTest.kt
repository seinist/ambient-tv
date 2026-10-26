package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayModeKeyTest {
    @Test fun preferredDoubleBeatsNativeRate() {
        val hz30 = mode("30", 3840, 2160, 30f)
        val hz60 = mode("60", 3840, 2160, 60f)
        val hz50 = mode("50", 3840, 2160, 50f)
        val hz100 = mode("100", 3840, 2160, 100f)
        assertEquals(hz60, DisplayRefreshRateMatcher.selectCadenceMode(listOf(hz30, hz60), 30f, true, true, preferDouble = true))
        assertEquals(hz30, DisplayRefreshRateMatcher.selectCadenceMode(listOf(hz30, hz60), 30f, false, true, preferDouble = true))
        assertEquals(hz30, DisplayRefreshRateMatcher.selectCadenceMode(listOf(hz30), 30f, true, true, preferDouble = true))
        // 50→100 still needs the high-rate opt-in.
        assertEquals(hz50, DisplayRefreshRateMatcher.selectCadenceMode(listOf(hz50, hz100), 50f, true, true, highDouble = false, preferDouble = true))
    }

    @Test fun nativeRateComesFirstThenDoubleThenPulldown() {
        val native = mode("24", 1920, 1080, 24.000002f)
        val double = mode("48", 1920, 1080, 48f)
        val pulldown = mode("60", 1920, 1080, 60f)
        // Native wins even with doubling and 3:2 enabled.
        assertEquals(native, DisplayRefreshRateMatcher.selectCadenceMode(listOf(pulldown, double, native), 24f, true, true))
        // Without a native mode: 2× before 3:2.
        assertEquals(double, DisplayRefreshRateMatcher.selectCadenceMode(listOf(pulldown, double), 24f, true, true))
        assertEquals(native, DisplayRefreshRateMatcher.selectCadenceMode(listOf(pulldown, native), 24f, false, true))
        assertEquals(pulldown, DisplayRefreshRateMatcher.selectCadenceMode(listOf(pulldown), 24f, true, true))
        assertEquals(pulldown, DisplayRefreshRateMatcher.selectCadenceMode(listOf(pulldown, double), 24f, false, true))
        assertEquals(native, DisplayRefreshRateMatcher.selectCadenceMode(listOf(pulldown, double, native), 24f, false, false))
        assertNull(DisplayRefreshRateMatcher.selectCadenceMode(listOf(pulldown), 24f, true, false))
        val hz50 = mode("50", 1920, 1080, 50f)
        val hz100 = mode("100", 1920, 1080, 100f)
        val hz60 = mode("60hz", 1920, 1080, 60f)
        val hz120 = mode("120", 1920, 1080, 120f)
        assertEquals(hz50, DisplayRefreshRateMatcher.selectCadenceMode(listOf(hz50, hz100), 50f, true, true))
        assertEquals(hz60, DisplayRefreshRateMatcher.selectCadenceMode(listOf(hz60, hz120), 59.94f, true, true))
        assertEquals(hz50, DisplayRefreshRateMatcher.selectCadenceMode(listOf(hz100, hz50), 25f, true, true))
        assertEquals(hz50, DisplayRefreshRateMatcher.selectCadenceMode(listOf(hz100, hz50), 50f, false, false))
        assertEquals(hz60, DisplayRefreshRateMatcher.selectCadenceMode(listOf(hz120, hz60), 30f, true, true))
    }

    @Test fun hundredAndHundredTwentyHertzNeedTheExplicitOptIn() {
        val hz100 = mode("100", 3840, 2160, 100f)
        val hz120 = mode("120", 3840, 2160, 120f)
        // No native 50/60 mode: the 2× mode is used only with the opt-in.
        assertNull(DisplayRefreshRateMatcher.selectCadenceMode(listOf(hz100), 50f, true, true, highDouble = false))
        assertEquals(hz100, DisplayRefreshRateMatcher.selectCadenceMode(listOf(hz100), 50f, true, true, highDouble = true))
        assertNull(DisplayRefreshRateMatcher.selectCadenceMode(listOf(hz120), 59.94f, true, true, highDouble = false))
        assertEquals(hz120, DisplayRefreshRateMatcher.selectCadenceMode(listOf(hz120), 60f, true, true, highDouble = true))
        // Even with the opt-in a native mode stays first.
        val hz60 = mode("60", 3840, 2160, 60f)
        assertEquals(hz60, DisplayRefreshRateMatcher.selectCadenceMode(listOf(hz120, hz60), 60f, true, true, highDouble = true))
    }

    @Test fun interpolationMatchesResolutionWithoutLosingDesktopTargetRate() {
        val desktop = mode("4k60", 3840, 2160, 60f)
        val film = mode("1080p24", 1920, 1080, 24f)
        val fast = mode("1080p60", 1920, 1080, 60f)
        assertEquals(fast, DisplayRefreshRateMatcher.selectInterpolationMode(listOf(film, fast), 1920, 1080, desktop, true))
        assertEquals(desktop, DisplayRefreshRateMatcher.selectInterpolationMode(listOf(film), 1920, 1080, desktop, true))
        assertEquals(desktop, DisplayRefreshRateMatcher.selectInterpolationMode(listOf(film, fast), 1920, 1080, desktop, false))
    }
    @Test fun stableModeKeyKeepsResolutionAndThreeDecimalRate() {
        assertEquals("3840x2160@59.940", DisplayRefreshRateMatcher.modeKey(3840, 2160, 59.94f))
    }

    @Test fun parsesMpvDimensionsAndRejectsInvalidSizes() {
        assertEquals(3840 to 2160, DisplayRefreshRateMatcher.parseVideoDimensions("3840×2160"))
        assertEquals(1920 to 1080, DisplayRefreshRateMatcher.parseVideoDimensions("1920x1080"))
        assertNull(DisplayRefreshRateMatcher.parseVideoDimensions("0×1080"))
        assertNull(DisplayRefreshRateMatcher.parseVideoDimensions(null))
    }

    @Test fun whitelistSelectsExactContentResolutionAndCadence() {
        val desktop = mode("3840x2160@60", 3840, 2160, 60f)
        val allowed = listOf(
            mode("1920x1080@24", 1920, 1080, 23.976f),
            mode("1920x1080@60", 1920, 1080, 59.94f),
        )

        val selected = DisplayRefreshRateMatcher.selectWhitelistedMode(
            allowedModes = allowed,
            contentWidth = 1920,
            contentHeight = 1080,
            contentFrameRate = 23.976f,
            fallbackMode = desktop,
            allowDoubleRate = true,
            allowThreeTwoPulldown = true,
        )

        // Native 24 Hz wins over 3:2 pulldown at 60 Hz.
        assertEquals(allowed[0], selected)
    }

    @Test fun whitelistFallsBackToDesktopIfNoExactAllowedResolutionAndCadenceMatch() {
        val desktop = mode("3840x2160@60", 3840, 2160, 60f)
        val allowed = listOf(mode("1920x1080@50", 1920, 1080, 50f))

        val selected = DisplayRefreshRateMatcher.selectWhitelistedMode(
            allowedModes = allowed,
            contentWidth = 1920,
            contentHeight = 1080,
            contentFrameRate = 23.976f,
            fallbackMode = desktop,
            allowDoubleRate = true,
            allowThreeTwoPulldown = true,
        )

        assertEquals(desktop, selected)
    }

    @Test fun sourceAutoPrefersExactResolutionAndNativeRate() {
        val desktop = mode("4k60", 3840, 2160, 60f)
        val source = mode("1080p24", 1920, 1080, 23.976f)
        val doubled = mode("1080p48", 1920, 1080, 47.952f)
        assertEquals(source, DisplayRefreshRateMatcher.selectContentResolutionMode(
            listOf(desktop, doubled, source), 1920, 1080, 23.976f, desktop, true, true, false,
        ))
        assertEquals(doubled, DisplayRefreshRateMatcher.selectContentResolutionMode(
            listOf(desktop, doubled), 1920, 1080, 23.976f, desktop, true, true, false,
        ))
        assertEquals(source, DisplayRefreshRateMatcher.selectContentResolutionMode(
            listOf(desktop, doubled, source), 1920, 1080, 23.976f, desktop, false, false, false,
        ))
    }

    @Test fun sourceAutoFitsLetterboxAndHonorsAllowedModes() {
        val desktop = mode("4k60", 3840, 2160, 60f)
        val hd = mode("1080p24", 1920, 1080, 24f)
        val low = mode("720p24", 1280, 720, 24f)
        assertEquals(hd, DisplayRefreshRateMatcher.selectContentResolutionMode(
            listOf(low, hd, desktop), 1920, 800, 24f, desktop, true, true, false,
        ))
        assertEquals(desktop, DisplayRefreshRateMatcher.selectContentResolutionMode(
            listOf(desktop), 1920, 1080, 24f, desktop, true, true, false,
        ))
    }

    @Test fun fullDisplayAutoKeepsLargestOutputAndMatchesCadence() {
        val desktop = mode("1080p60", 1920, 1080, 60f)
        val low = mode("1080p24", 1920, 1080, 24f)
        val full = mode("4k24", 3840, 2160, 24f)
        val fullFast = mode("4k60", 3840, 2160, 60f)
        // Native rate first: 24p stays at 24 Hz instead of 3:2 at 60 Hz.
        assertEquals(full, DisplayRefreshRateMatcher.selectFullDisplayMode(
            listOf(desktop, low, fullFast, full), 24f, desktop, true, true, false,
        ))
        assertEquals(fullFast, DisplayRefreshRateMatcher.selectFullDisplayMode(
            listOf(desktop, fullFast), 24f, desktop, true, true, false,
        ))
        assertEquals(desktop, DisplayRefreshRateMatcher.selectFullDisplayMode(
            listOf(desktop, low), 24f, desktop, true, true, true,
        ))
    }

    @Test fun bothAutoPoliciesUseSourceCadenceAndDifferentOutputSizes() {
        val desktop = mode("4k60", 3840, 2160, 60f)
        val hd = mode("1080p24", 1920, 1080, 23.976f)
        val full = mode("4k24", 3840, 2160, 23.976f)
        val modes = listOf(desktop, full, hd)
        assertEquals(hd, DisplayRefreshRateMatcher.selectContentMode(modes, desktop,
            23.976f, 1920, 800, preferredModeKey = "auto_content", preserveOutputResolution = true))
        assertEquals(full, DisplayRefreshRateMatcher.selectContentMode(modes, desktop,
            23.976f, 1920, 800, preferredModeKey = "auto_display"))
        assertEquals(hd, DisplayRefreshRateMatcher.selectContentMode(modes, desktop,
            23.976f, 1920, 1080, preferredModeKey = "auto_display", allowedModes = setOf(hd.key)))
    }

    @Test fun autoCannotRequestMissingOrDisallowedDisplayModes() {
        val desktop = mode("4k60", 3840, 2160, 60f)
        assertNull(DisplayRefreshRateMatcher.selectContentMode(emptyList(), desktop,
            24f, 1920, 1080, preferredModeKey = "auto_content"))
        assertNull(DisplayRefreshRateMatcher.selectContentMode(listOf(desktop), desktop,
            24f, preferredModeKey = "auto_display", allowedModes = setOf("missing")))
        assertEquals(desktop, DisplayRefreshRateMatcher.selectContentMode(listOf(desktop), desktop,
            24f, preferredModeKey = "auto_display"))
    }

    @Test fun separateDecoderEventsRetainSourceCadenceAndCompleteGeometry() {
        val rate = mergeDisplayContentFormat(null, 23.976f, null, null)
        val complete = mergeDisplayContentFormat(rate, 0f, 1920, 1080)
        assertEquals(complete, mergeDisplayContentFormat(mergeDisplayContentFormat(null, 0f, 1920, 1080), 23.976f, null, null))
        assertEquals(DisplayContentFormat(23.976f, 1920, 1080), complete)
        assertEquals(complete, mergeDisplayContentFormat(complete, Float.NaN, 1280, null))
        assertEquals(DisplayContentFormat(25f, 1280, 720), mergeDisplayContentFormat(complete, 25f, 1280, 720))
    }

    @Test fun startOnlyCannotLatchUntilPhysicalSwitchAndRequiredFormatAreConfirmed() {
        val target = mode("hd24", 1920, 1080, 24f)
        val complete = DisplayContentFormat(24f, 1920, 1080)
        org.junit.Assert.assertFalse(displayStartRequestConfirmed(complete, true, null, target.key))
        org.junit.Assert.assertFalse(displayStartRequestConfirmed(complete, true, target, "desktop60"))
        org.junit.Assert.assertFalse(displayStartRequestConfirmed(DisplayContentFormat(0f, 1920, 1080), true, target, target.key))
        org.junit.Assert.assertFalse(displayStartRequestConfirmed(DisplayContentFormat(24f, null, null), true, target, target.key))
        org.junit.Assert.assertTrue(displayStartRequestConfirmed(complete, true, target, target.key))
        org.junit.Assert.assertTrue(displayStartRequestConfirmed(DisplayContentFormat(24f, null, null), false, target, target.key))
        org.junit.Assert.assertTrue(displayFormatNeedsSize("auto_content", false))
        org.junit.Assert.assertFalse(displayFormatNeedsSize("auto_display", true))
    }

    @Test fun bothAutoPoliciesHonorDoublingAndPulldownSwitches() {
        for (policy in listOf("auto_content", "auto_display")) {
            for ((fps, hz, doubling) in listOf(
                Triple(24f, 48f, true), Triple(23.976f, 47.952f, true),
                Triple(25f, 50f, true), Triple(30f, 60f, true),
                Triple(29.97f, 59.94f, true),
                Triple(24f, 60f, false), Triple(23.976f, 59.94f, false),
            )) {
                val desktop = mode("desktop", 1280, 720, hz)
                val target = mode("target", 1920, 1080, hz)
                fun select(double: Boolean, pulldown: Boolean) = DisplayRefreshRateMatcher.selectContentMode(
                    listOf(desktop, target), desktop, fps, 1920, 1080,
                    preferredModeKey = policy, allowedModes = setOf(target.key),
                    allowDoubleRate = double, allowThreeTwoPulldown = pulldown,
                )
                assertEquals("$policy: $fps -> $hz enabled", target, select(doubling, !doubling))
                assertNull("$policy: $fps -> $hz disabled", select(!doubling, doubling))
                assertNull("$policy: all alternatives disabled", select(false, false))
            }
        }
    }

    @Test fun bothAutoPoliciesPreferNativeThenDoublingThenPulldown() {
        for (policy in listOf("auto_content", "auto_display")) {
            val desktop = mode("desktop", 1280, 720, 60f)
            val native = mode("native", 1920, 1080, 23.976f)
            val doubled = mode("doubled", 1920, 1080, 47.952f)
            val pulldown = mode("pulldown", 1920, 1080, 59.94f)
            fun select(modes: List<SupportedDisplayMode>) = DisplayRefreshRateMatcher.selectContentMode(
                modes, desktop, 23.976f, 1920, 1080, preferredModeKey = policy,
            )
            assertEquals(native, select(listOf(pulldown, doubled, native)))
            assertEquals(doubled, select(listOf(pulldown, doubled)))
            assertEquals(native, select(listOf(pulldown, native)))
            assertEquals(native, select(listOf(native)))
        }
    }

    @Test fun manualResolutionKeepsSizeAndUsesNativeThenDoubleOrPulldown() {
        val picked = mode("1080p30", 1920, 1080, 30f)
        val doubled = mode("1080p60", 1920, 1080, 59.94f)
        val film = mode("1080p24", 1920, 1080, 23.976f)
        val pulldown = mode("1080p59", 1920, 1080, 59.94f)
        val uhd = mode("4k60", 3840, 2160, 60f)
        assertEquals(picked, DisplayRefreshRateMatcher.selectContentMode(
            listOf(picked, doubled, uhd), uhd, 29.97f, 1920, 1080, preferredModeKey = picked.key,
        ))
        assertEquals(picked, DisplayRefreshRateMatcher.selectContentMode(
            listOf(picked, doubled), uhd, 29.97f, 1920, 1080, preferredModeKey = picked.key, allowDoubleRate = false,
        ))
        assertEquals(film, DisplayRefreshRateMatcher.selectContentMode(
            listOf(film, pulldown), uhd, 23.976f, 1920, 1080, preferredModeKey = film.key,
        ))
        assertEquals(uhd, DisplayRefreshRateMatcher.selectContentMode(
            listOf(film, pulldown, uhd), uhd, 30f, 1920, 1080, preferredModeKey = uhd.key,
            allowedModes = setOf(film.key, pulldown.key),
        ))
    }

    @Test fun exactRateWinsWithinAMultiple() {
        val film = mode("23", 1920, 1080, 23.976f)
        val hz24 = mode("24", 1920, 1080, 24f)
        val hz48 = mode("48", 1920, 1080, 48f)
        assertEquals(film, DisplayRefreshRateMatcher.selectCadenceMode(listOf(hz24, hz48, film), 23.976f, true, true))
        assertEquals(hz24, DisplayRefreshRateMatcher.selectCadenceMode(listOf(hz24, hz48), 23.976f, true, true))
    }

    @Test fun cadenceDeviationIsReportedOnlyForNearMatches() {
        assertNull(DisplayRefreshRateMatcher.cadenceDeviationHz(23.976f, 23.976f, 1f))
        assertNull(DisplayRefreshRateMatcher.cadenceDeviationHz(47.952f, 23.976f, 2f))
        assertEquals(0.024f, DisplayRefreshRateMatcher.cadenceDeviationHz(24f, 23.976f, 1f)!!, 0.001f)
        assertEquals(0.06f, DisplayRefreshRateMatcher.cadenceDeviationHz(60f, 59.94f, 1f)!!, 0.001f)
    }

    private fun mode(key: String, width: Int, height: Int, refreshRate: Float) =
        SupportedDisplayMode(key, modeId = key.hashCode(), width = width, height = height, refreshRate = refreshRate)
}
