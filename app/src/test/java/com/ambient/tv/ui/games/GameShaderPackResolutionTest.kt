package com.ambient.tv.ui.games

import com.ambient.tv.games.GameSettingsState
import com.swordfish.libretrodroid.ShaderConfig
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

    @Test fun identicalRepeatedRequestsStillHaveDistinctIdentity() {
        val state = GameSettingsState()
        val first = GameShaderPackChoice("pack/a", true, state)
        val second = GameShaderPackChoice("pack/a", true, state)
        assertNotSame(first, second)
    }
}
