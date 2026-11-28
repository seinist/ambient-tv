package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class Media3EffectiveChainKeyTest {
    @Test fun nonScalingFiltersIgnoreSurfaceSizeChanges() {
        val stages = listOf(Media3Stage.Sharpen("cas"))
        assertEquals(media3EffectiveChainKey(stages, 1920, 1080, false),
            media3EffectiveChainKey(stages, 3840, 2160, false))
    }

    @Test fun scalingAndPrecisionChangesRequireNewChain() {
        val stages = listOf(Media3Stage.Scale("fsr"), Media3Stage.Sharpen("rcas"))
        val first = media3EffectiveChainKey(stages, 1920, 1080, true)
        assertNotEquals(first, media3EffectiveChainKey(stages, 3840, 2160, true))
        assertNotEquals(first, media3EffectiveChainKey(stages, 1920, 1080, false))
    }

    @Test fun emptyChainIgnoresViewportAndPrecision() {
        assertEquals(media3EffectiveChainKey(emptyList(), 1920, 1080, true),
            media3EffectiveChainKey(emptyList(), 3840, 2160, false))
    }
}
