package com.ambient.tv.games

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameUpscaleSettingsTest {
    private val filters = listOf(
        GameShader.SCALEFX, GameShader.SABR, GameShader.XBR_LV2, GameShader.XBRZ_FREESCALE,
        GameShader.XSAL_2X, GameShader.SUPER_2XSAI, GameShader.SUPER_EAGLE, GameShader.SCALEHQ2X,
    )

    @Test fun upstreamFiltersAreAvailableForSoftwareCoresWithoutChangingTheirDefaults() {
        val snes = GameSystems.byId("snes")!!
        assertFalse(snes.core.hardware)
        assertEquals(GameShader.SHARP, GameSettingsState().shader(snes))
        filters.forEach { shader ->
            assertTrue(shader.key, GamePictureDefaults.offers(snes, shader))
            assertEquals(GameShaderGroup.SCALING, shader.group)
            assertFalse(shader in GamePictureDefaults.effects)
        }
        assertEquals(GameShader.entries.size, GameShader.entries.map { it.key }.distinct().size)
    }

    @Test fun everyNewFilterSurvivesTheSettingsMapUsedByBackupAndSync() {
        val snes = GameSystems.byId("snes")!!
        val megadrive = GameSystems.byId("megadrive")!!
        val serializer = MapSerializer(String.serializer(), String.serializer())
        filters.forEach { shader ->
            val selected = GameSettingsState()
                .withShader(null, GameShader.PIXEL)
                .withShader(snes, shader)
                .withGameShader("snes-save", GameShader.SABR)
            val encoded = Json.encodeToString(serializer, selected.values)
            val restored = GameSettingsState(Json.decodeFromString(serializer, encoded))
            assertEquals(shader.key, shader, restored.shader(snes, "another-save"))
            assertEquals(GameShader.SABR, restored.shader(snes, "snes-save"))
            assertEquals(GameShader.PIXEL, restored.shader(megadrive))
            assertEquals(shader, restored.withGameShader("snes-save", null).shader(snes, "snes-save"))
        }
    }

    @Test fun choosingABuiltInUpscalerReplacesThePackAtOnlyTheChosenScope() {
        val snes = GameSystems.byId("snes")!!
        val state = GameSettingsState()
            .withPackShader(snes, null, "demo/system")
            .withPackShader(snes, "one-game", "demo/game")
        val perGame = state.withGameShader("one-game", GameShader.SCALEFX)
        assertNull(perGame.packShader(snes, "one-game"))
        assertEquals("demo/system", perGame.packShader(snes, "another-game"))
        assertEquals(GameShader.SCALEFX, perGame.shader(snes, "one-game"))
        val perSystem = state.withShader(snes, GameShader.XBRZ_FREESCALE)
        assertNull(perSystem.packShader(snes, "another-game"))
        assertEquals("demo/game", perSystem.packShader(snes, "one-game"))
    }
}
