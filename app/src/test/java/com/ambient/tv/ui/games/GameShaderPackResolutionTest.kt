package com.ambient.tv.ui.games

import com.ambient.tv.games.GameSettingsState
import com.ambient.tv.games.GameShader
import com.ambient.tv.games.GameSystems
import com.ambient.tv.games.RetroShaderConfig as ShaderConfig
import org.junit.Assert.*
import org.junit.Test

class GameShaderPackResolutionTest {
    private val config = ShaderConfig.Custom(listOf(ShaderConfig.Custom.ShaderPass("vertex", "fragment")))

    @Test fun verifiedSourceNeverLeaksIntoAnotherSelection() {
        val resolved = GameShaderPackResolution("pack/a", config)
        assertTrue(resolved.matches("pack/a"))
        assertSame(config, resolved.configFor("pack/a"))
        assertFalse(resolved.matches("pack/b"))
        assertNull(resolved.configFor("pack/b"))
        assertNull(resolved.configFor(null))
    }

    @Test fun failedVerificationIsResolvedOnlyForItsOwnSelection() {
        val missing = GameShaderPackResolution("pack/a", null)
        assertTrue(missing.matches("pack/a"))
        assertNull(missing.configFor("pack/a"))
        assertFalse(missing.matches("pack/b"))
        assertFalse(missing.matches(null))
    }

    @Test fun unrelatedSettingsDoNotDiscardVerifiedShaderChoice() {
        val system = GameSystems.all.first()
        val state = GameSettingsState().withShader(system, GameShader.CRT_LIGHT)
        val request = GameShaderPackChoice("pack/a", false, state)
        val updated = state.copy(values = state.values + ("performance.profile" to "low_overhead"))
        assertTrue(request.stillApplies(updated, system, "game"))
        assertFalse(request.stillApplies(updated.withShader(system, GameShader.PIXEL), system, "game"))
        assertFalse(request.stillApplies(updated.withPackShader(system, null, "pack/b"), system, "game"))
        assertFalse(request.stillApplies(updated.withShaderStrength(system, null, 1.25f), system, "game"))
        assertFalse(request.stillApplies(updated.withGamePicture(system, "game"), system, "game"))
        val scoped = state.withGamePicture(system, "game")
        val scopedRequest = GameShaderPackChoice("pack/a", true, scoped)
        assertTrue(scopedRequest.stillApplies(scoped.copy(values = scoped.values + ("audio.other" to "1")), system, "game"))
        assertFalse(scopedRequest.stillApplies(scoped.withoutGamePicture("game"), system, "game"))
    }

    @Test fun identicalRepeatedRequestsStillHaveDistinctIdentity() {
        val state = GameSettingsState()
        val first = GameShaderPackChoice("pack/a", true, state)
        val second = GameShaderPackChoice("pack/a", true, state)
        assertNotSame(first, second)
    }
}
