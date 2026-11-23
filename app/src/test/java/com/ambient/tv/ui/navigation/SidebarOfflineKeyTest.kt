package com.ambient.tv.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SidebarOfflineKeyTest {
    private fun row(id: String, systemId: String? = null, opensPage: Boolean = false) =
        SidebarNavRow.Item(MenuDestination(id, id, Icons.Filled.Home, ""), false, systemId, opensPage)

    @Test fun systemRowsUseTheirSystemKey() {
        assertEquals("plex", sidebarRowOfflineKey(row("system:plex", "plex", opensPage = true)))
        assertEquals("tvheadend", sidebarRowOfflineKey(row("system:tvheadend", "tvheadend", opensPage = true)))
    }

    @Test fun networkSourcesMapToTheirAvailabilityKeys() {
        assertEquals("files:nas", sidebarRowOfflineKey(row("files:source:nas", "files")))
        assertEquals("dlna:abc", sidebarRowOfflineKey(row("dlna:abc", "dlna")))
        assertEquals("weather", sidebarRowOfflineKey(row(WEATHER_MENU_ID)))
    }

    @Test fun plainDestinationsCarryNoStatus() {
        assertNull(sidebarRowOfflineKey(row("home")))
        assertNull(sidebarRowOfflineKey(row("dlna:manage", "dlna")))
        assertNull(sidebarRowOfflineKey(row("plex:home", "plex")))
    }
}
