package com.ambient.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeHeroStyleTest {
    @Test fun newStylesRemainSelectableAndRespectPowerSaving() {
        for (style in listOf(HomeHeroStyle.AURORA, HomeHeroStyle.CINEMA, HomeHeroStyle.PRISM)) {
            assertEquals(style, HomeHeroStyle.normalize(style))
            assertEquals(true, style in HomeHeroStyle.keys)
            assertEquals(true, HomeHeroStyle.samplesArtwork(style))
            assertEquals(true, HomeHeroStyle.isAnimated(style))
            assertEquals(HomeHeroStyle.STATIC, HomeHeroStyle.effective(style, "auto", reducedEffects = true))
            assertEquals(style, HomeHeroStyle.effective(style, "auto", reducedEffects = false))
            val low = HomeHeroStyle.effective(style, "low", reducedEffects = false)
            assertEquals(HomeHeroStyle.STATIC, low)
            assertEquals(false, HomeHeroStyle.isAnimated(low))
            assertEquals(false, HomeHeroStyle.samplesArtwork(low))
        }
    }

    @Test fun lowModeAndAutoOnWeakDevicesUseTheStaticHero() {
        assertEquals(HomeHeroStyle.STATIC, HomeHeroStyle.effective(HomeHeroStyle.DEPTH, "low", reducedEffects = true))
        assertEquals(HomeHeroStyle.STATIC, HomeHeroStyle.effective(HomeHeroStyle.DEPTH, "auto", reducedEffects = true))
        assertEquals(0f, HomeHeroStyle.motionStrength("low", reducedEffects = true))
        assertEquals(0.42f, HomeHeroStyle.motionStrength("auto", reducedEffects = true))
    }

    @Test fun maximumUsesFullMotionAndNormalModesStayRestrained() {
        assertEquals(1f, HomeHeroStyle.motionStrength("maximum", reducedEffects = false))
        assertEquals(0.72f, HomeHeroStyle.motionStrength("balanced", reducedEffects = false))
        assertEquals(HomeHeroStyle.DEPTH, HomeHeroStyle.effective(HomeHeroStyle.DEPTH, "balanced", reducedEffects = false))
        assertEquals(HomeHeroStyle.DEFAULT, HomeHeroStyle.normalize("unknown"))
    }

    @Test fun intensityAndSpreadPreserveTheExistingDefaultAndClampInvalidValues() {
        assertEquals(1f, HomeHeroStyle.intensityScale(HomeHeroStyle.DEFAULT_INTENSITY), 0.0001f)
        assertEquals(0f, HomeHeroStyle.intensityScale(-10), 0.0001f)
        assertEquals(100f / 60f, HomeHeroStyle.intensityScale(110), 0.0001f)
        assertEquals(HomeHeroStyle.SOFT, HomeHeroStyle.normalizeSpread("invalid"))
        assertEquals(1f, HomeHeroStyle.radiusScale(HomeHeroStyle.SOFT), 0.0001f)
        assertEquals(0.72f, HomeHeroStyle.radiusScale(HomeHeroStyle.FOCUSED), 0.0001f)
        assertEquals(1.32f, HomeHeroStyle.radiusScale(HomeHeroStyle.WIDE), 0.0001f)
    }
}
