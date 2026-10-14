package com.ambient.tv.ui.settings

import org.junit.Assert.*
import org.junit.Test

class NetworkSettingsPolicyTest {
    @Test fun networkFunctionsHaveTheirOwnAreaAndMediaSourcesStayUnderSystems() {
        listOf(SettingsCategory.COMPANION, SettingsCategory.RECEIVER, SettingsCategory.WIREGUARD, SettingsCategory.WAKE)
            .forEach { assertEquals(SettingsArea.NETWORK, it.area) }
        assertEquals(SettingsArea.SYSTEMS, SettingsCategory.DLNA.area)
        assertEquals(SettingsArea.SYSTEMS, SettingsCategory.FILES.area)
        assertEquals(SettingsArea.APP, SettingsCategory.TRANSFER.area)
    }

    @Test fun legacyLinksStillResolveAndReceiverDoesNotOpenTheDlnaSource() {
        assertEquals(SettingsCategory.PLEX, SettingsCategory.fromKey("network"))
        assertEquals(SettingsCategory.WIREGUARD, SettingsCategory.fromKey("vpn"))
        assertEquals(SettingsCategory.RECEIVER, SettingsCategory.fromKey("upnp"))
        assertEquals(SettingsCategory.WAKE, SettingsCategory.fromKey("wol"))
        assertEquals(SettingsCategory.COMPANION, SettingsCategory.fromKey("phone"))
    }

    @Test fun tunnelAloneCannotCountAsAMediaSourceOrHijackSystemsShortcut() {
        val state = SettingsUiState(wireGuardSummary = "Configured tunnel", plexEnabled = false,
            jellyfinEnabled = false, embyEnabled = false, tvheadendEnabled = false,
            youtubeEnabled = false, spotifyEnabled = false, filesEnabled = false)
        assertFalse(usableConfiguredSystems(state).contains(SettingsCategory.WIREGUARD))
        assertNull(singleUsableConfiguredSystem(state))
    }

    @Test fun wakeProfilePickerOnlyWhenThereIsAChoice() {
        assertFalse(hasWakeProfilePicker(SettingsUiState()))
    }
}
