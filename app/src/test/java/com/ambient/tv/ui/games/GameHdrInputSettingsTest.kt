package com.ambient.tv.ui.games

import com.ambient.tv.games.GameHdrToneMap
import com.ambient.tv.games.GameSettingsState
import com.ambient.tv.games.GameSystems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameHdrInputSettingsTest {
    private val core = GameSystems.byId("psx")!!.core("mednafen_psx_hw")!!
    private val key = "beetle_psx_hw_color_format"

    @Test fun enablingHdrReplacesOnlyTheCurrentGamesConflictingColorOverride() {
        val initial = GameSettingsState()
            .withGameCoreOption(core, "one", key, "24bit")
            .withGameCoreOption(core, "one", "beetle_psx_hw_internal_resolution", "4x")
            .withGameCoreOption(core, "two", key, "16bit")
        val selected = GameHdrInputSettings.select(initial, core, "one", GameHdrToneMap.PQ)
        assertEquals(GameHdrToneMap.PQ, selected.hdrToneMap(core))
        assertEquals("30bit_hdr", selected.effectiveCoreOptions(core, "one")[key])
        assertEquals("4x", selected.gameCoreOptions(core, "one")["beetle_psx_hw_internal_resolution"])
        assertEquals("16bit", selected.gameCoreOptions(core, "two")[key])
    }

    @Test fun disablingHdrClearsBothHdrOverridesButPreservesAnExplicitSdrChoice() {
        val initial = GameSettingsState().withHdrToneMap(core, GameHdrToneMap.PQ)
            .withCoreOption(core, key, "30bit_hdr")
            .withGameCoreOption(core, "one", key, "30bit_hdr")
        val off = GameHdrInputSettings.select(initial, core, "one", GameHdrToneMap.OFF)
        assertEquals(GameHdrToneMap.OFF, off.hdrToneMap(core))
        assertNull(off.effectiveCoreOptions(core, "one")[key])
        val explicitSdr = initial.withGameCoreOption(core, "one", key, "24bit")
        assertEquals("24bit", GameHdrInputSettings.select(explicitSdr, core, "one", GameHdrToneMap.OFF)
            .effectiveCoreOptions(core, "one")[key])
    }
}
