package com.ambient.tv.ui.games

import com.ambient.tv.games.GameSettingsState
import com.ambient.tv.games.GameShader
import com.ambient.tv.games.GameSystems
import com.ambient.tv.games.RetroShaderConfig as ShaderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRetroShadersTest {
    @Test fun customLibraryHasFifteenSmallSinglePassShaders() {
        val presets = GameShader.entries.filter(GameRetroShaders::isCustom)
        assertEquals(15, presets.size)
        presets.forEach { shader ->
            val chain = shader.config() as ShaderConfig.Custom
            assertEquals(shader.key, 1, chain.passes.size)
            val pass = chain.passes.single()
            assertTrue(shader.key, pass.fragment.contains("void main()"))
            assertTrue(shader.key, pass.fragment.contains("precision"))
            assertTrue(shader.key, pass.fragment.contains("INTENSITY"))
            assertTrue(shader.key, pass.fragment.length < 4000)
            assertTrue(shader.key, pass.scale <= 1f)
        }
    }

    @Test fun strengthChangesOnlyBoundedUniformMetadata() {
        for (shader in GameShader.entries.filter(GameRetroShaders::isCustom)) {
            fun pass(value: Float) = (GameRetroShaders.config(shader, value) as ShaderConfig.Custom).passes.single()
            val original = pass(1f)
            assertTrue(original.fragment.contains("uniform float ambientFilterIntensity;"))
            for ((value, expected) in listOf(
                0.5f to 0.5f, 1.25f to 1.25f, 100f to 1.5f, -2f to 0.5f,
                Float.NaN to 1f, Float.POSITIVE_INFINITY to 1f,
            )) {
                val changed = pass(value)
                assertEquals(original.fragment, changed.fragment)
                assertEquals(original.vertex, changed.vertex)
                assertEquals(expected, changed.ambientIntensity, 0f)
                assertEquals(original, changed.copy(ambientIntensity = original.ambientIntensity))
            }
        }
    }

    @Test fun effectAndHdrPrefixKeepIndependentUniformValues() {
        val filter = GameShader.CRT_LIGHT.config(1.25f)
        val withEffect = GamePictureEffect.apply(filter, GameShader.FILM_GRAIN) as ShaderConfig.Custom
        assertEquals(listOf(1f, 1.25f), withEffect.passes.map { it.ambientIntensity })
        val hdr = GameHdrToneMapShader.forConfig(filter, com.ambient.tv.games.GameHdrToneMap.PQ, true)
            as ShaderConfig.PrependPass
        assertEquals(0f, hdr.pass.ambientIntensity, 0f)
        assertEquals(1.25f, (hdr.filter as ShaderConfig.Custom).passes.single().ambientIntensity, 0f)
        // Signed/custom external GLSL has no implicit uniform binding.
        assertEquals(0f, ShaderConfig.Custom.ShaderPass("vertex", "fragment").ambientIntensity, 0f)
    }

    @Test fun perGameShaderOverridesDoNotAffectSystemDefaults() {
        val system = GameSystems.all.first()
        val base = GameSettingsState().withShader(system, GameShader.CRT_LIGHT)
        assertEquals(GameShader.CRT_LIGHT, base.shader(system, "game"))
        val other = base.withGameShader("game", GameShader.GAMEBOY_DMG)
        assertEquals(GameShader.GAMEBOY_DMG, other.shader(system, "game"))
        assertEquals(GameShader.CRT_LIGHT, other.shader(system, "other"))
        assertEquals(GameShader.CRT_LIGHT, other.withGameShader("game", null).shader(system, "game"))
        assertEquals("sharp", GameShader.SHARP.key)
        assertEquals("pixel", GameShader.PIXEL.key)
    }
}
