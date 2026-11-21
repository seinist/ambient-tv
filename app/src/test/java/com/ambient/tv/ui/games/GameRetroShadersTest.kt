package com.ambient.tv.ui.games

import com.ambient.tv.games.GameSettingsState
import com.ambient.tv.games.GameShader
import com.ambient.tv.games.GameSystems
import com.swordfish.libretrodroid.ShaderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun strengthIsBoundedBeforeCompilingSource() {
        val original = (GameRetroShaders.config(GameShader.FILM_GRAIN, 1f) as ShaderConfig.Custom).passes.single().fragment
        val stronger = (GameRetroShaders.config(GameShader.FILM_GRAIN, 1.25f) as ShaderConfig.Custom).passes.single().fragment
        val extreme = (GameRetroShaders.config(GameShader.FILM_GRAIN, 100f) as ShaderConfig.Custom).passes.single().fragment
        assertFalse(original == stronger)
        assertTrue(extreme.contains("const float INTENSITY = 1.5;"))
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
