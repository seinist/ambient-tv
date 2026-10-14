package com.ambient.tv.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsAutosaveTest {
    private val saved = SettingsUiState(
        host = "10.0.0.2", port = "32400", token = "old", macAddress = "",
        jellyfinHost = "jf", jellyfinPassword = "pw", tvheadendHost = "tvh", tvheadendPassword = "x",
        spotifyClientId = "id", customAccentHex = "#E5A00D",
    )

    @Test fun `credentials addresses and keys stay at the saved value until committed`() {
        val typing = saved.copy(
            host = "10.0.", port = "3", token = "ne", macAddress = "AA:BB",
            jellyfinHost = "j", jellyfinPassword = "p", embyUsername = "u",
            tvheadendHost = "t", tvheadendPassword = "", tvheadendMacAddress = "11",
            spotifyClientId = "i", spotifyDeviceName = "Liv",
        )
        assertEquals(saved.copy(spotifyDeviceName = saved.spotifyDeviceName), typing.deferredSettingsFrom(saved))
    }

    @Test fun `ordinary settings are saved immediately`() {
        val changed = saved.copy(nextUpEnabled = !saved.nextUpEnabled, uiScale = saved.uiScale + 0.1f, host = "typing")
        val target = changed.deferredSettingsFrom(saved)
        assertEquals(changed.nextUpEnabled, target.nextUpEnabled)
        assertEquals(changed.uiScale, target.uiScale)
        assertEquals(saved.host, target.host)
    }

    @Test fun `accent colour is saved only once it is a complete hex value`() {
        assertEquals("#E5A00D", saved.copy(customAccentHex = "#12").deferredSettingsFrom(saved).customAccentHex)
        assertEquals("#123456", saved.copy(customAccentHex = "#123456").deferredSettingsFrom(saved).customAccentHex)
    }
}
