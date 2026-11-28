package com.ambient.tv.ui.games

import com.ambient.tv.games.GameHdrToneMap
import com.ambient.tv.games.GameShader
import com.ambient.tv.games.RetroShaderConfig as ShaderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GameUpscaleShadersTest {
    @Test fun scalefxPreservesMetricsAndItsThreeTimesResolutionBeforeDisplay() {
        val chain = GameUpscaleShaders.config(GameShader.SCALEFX)
        assertEquals(7, chain.passes.size)
        assertEquals(listOf(false, true, true, false, false, false, false), chain.passes.map { it.floatFramebuffer })
        assertEquals(listOf(1f, 1f, 1f, 1f, 1f, 3f, 1f), chain.passes.map { it.scale })
        assertTrue(chain.passes.none { it.linear })
        // These are relative references: effect/HDR prefixes must not rewrite
        // them to the unconverted core image or to a different metric pass.
        assertTrue(chain.passes[3].fragment.contains("uniform highp sampler2D PassPrev2Texture;"))
        assertTrue(chain.passes[5].fragment.contains("uniform highp sampler2D PassPrev5Texture;"))
    }

    @Test fun effectPrefixKeepsScalefxPassOrderReferencesAndFloatTargets() {
        val filter = GameUpscaleShaders.config(GameShader.SCALEFX)
        val result = GamePictureEffect.apply(filter, GameShader.COLOR_BOOST) as ShaderConfig.Custom
        assertEquals(filter.passes.size + 1, result.passes.size)
        assertEquals(filter.passes, result.passes.drop(1))
        assertFalse(result.passes.first().linear)
        assertEquals(2, result.passes.count { it.floatFramebuffer })
    }

    @Test fun hdrPrefixKeepsTheExactFilterAndUsesNearestForMetricInputs() {
        GameShader.entries.filter(GameUpscaleShaders::supports).forEach { shader ->
            val filter = GameUpscaleShaders.config(shader)
            for (input in listOf(GameHdrToneMap.PQ, GameHdrToneMap.HLG)) {
                val result = GameHdrToneMapShader.forGame(shader, input, true) as ShaderConfig.PrependPass
                assertSame(shader.key, filter, result.filter)
                assertFalse(shader.key, result.pass.linear)
                assertFalse(shader.key, result.pass.floatFramebuffer)
            }
        }
    }

    @Test fun fixedTwoTimesAlgorithmsDoNotRunAtDisplayResolution() {
        for (shader in listOf(GameShader.XSAL_2X, GameShader.SUPER_2XSAI, GameShader.SUPER_EAGLE, GameShader.SCALEHQ2X)) {
            val passes = GameUpscaleShaders.config(shader).passes
            assertEquals(shader.key, 3, passes.size)
            assertEquals(shader.key, 1f, passes[0].scale, 0f)
            assertFalse(shader.key, passes[0].linear)
            assertEquals(shader.key, 2f, passes[1].scale, 0f)
            assertEquals(shader.key, shader == GameShader.XSAL_2X, passes[1].linear)
            assertFalse(shader.key, passes[2].floatFramebuffer)
        }
    }

    @Test fun legacyIntensityDoesNotChangeAuthenticAlgorithmsAndSwitchingReusesSource() {
        val filters = GameShader.entries.filter(GameUpscaleShaders::supports)
        assertEquals(8, filters.size)
        filters.forEach { shader ->
            val original = shader.config(0.5f)
            assertSame(shader.key, original, shader.config(1.5f))
            assertSame(shader.key, original, GameUpscaleShaders.config(shader))
            assertFalse(shader.key, GameRetroShaders.isCustom(shader))
        }
    }
}
