package com.ambient.tv.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GameCoreCompatibilityTest {
    @Test fun melonDsUsesItsSoftwareRendererOnAndroid() {
        val core = GameSystems.byId("nds")!!.core("melondsds")!!
        assertFalse(core.hardware)
        assertFalse(core.supportsVulkan)
        assertEquals(mapOf("melonds_render_mode" to "software"), core.glOptions)
    }

    @Test fun melonDsCompatibilityOverridesPreviouslySavedDesktopRenderers() {
        val core = GameSystems.byId("nds")!!.core("melondsds")!!
        val settings = GameSettingsState()
            .withCoreOption(core, "melonds_render_mode", "opengl")
            .withCoreOption(core, "melonds_console_mode", "ds")
            .withGameCoreOption(core, "game", "melonds_render_mode", "compute")
            .withGameCoreOption(core, "game", "melonds_screen_layout", "left-right")
        // Startup and the core-options panel apply the backend map last. Ordinary
        // options still inherit per-core defaults and per-game overrides.
        val options = settings.effectiveCoreOptions(core, "game") + core.glOptions
        assertEquals("software", options["melonds_render_mode"])
        assertEquals("ds", options["melonds_console_mode"])
        assertEquals("left-right", options["melonds_screen_layout"])
        assertEquals("software", (settings.effectiveCoreOptions(core, null) + core.glOptions)["melonds_render_mode"])
    }
}
