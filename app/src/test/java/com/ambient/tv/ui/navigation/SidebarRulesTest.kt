package com.ambient.tv.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import com.ambient.tv.livetv.AvailableLiveTvSource
import com.ambient.tv.livetv.LiveTvCapabilities
import com.ambient.tv.livetv.LiveTvSource
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.data.browse.InternetReachability
import com.ambient.tv.vpn.WireGuardRouteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarRulesTest {
    private fun row(id: String, system: String? = null) =
        SidebarNavRow.Item(MenuDestination(id, id, Icons.Filled.Home, id), active = false, systemId = system, opensPage = system != null)

    @Test fun onlyExplicitVpnSourcesAreBlockedWhenInternetUnavailable() {
        val configuredVpn = setOf("plex", "files:remote-nas", "dlna:vpn")
        val blocked = sidebarVpnUnavailableSources(
            configuredVpn, WireGuardRouteState.Connecting, InternetReachability.UNAVAILABLE)
        assertEquals(configuredVpn, blocked)
        assertFalse("jellyfin" in blocked)
        assertFalse("files:local" in blocked)
        assertFalse("tvheadend" in blocked)
    }

    @Test fun healthyLanWireGuardWinsOverMissingAndroidInternetValidation() {
        val vpn = setOf("plex", "files:remote")
        assertTrue(sidebarVpnUnavailableSources(
            vpn, WireGuardRouteState.Up, InternetReachability.UNAVAILABLE).isEmpty())
        assertEquals(vpn, sidebarVpnUnavailableSources(
            vpn, WireGuardRouteState.Failed("handshake failed"), InternetReachability.AVAILABLE))
        // Idle at cold start with a working network is not evidence of an outage.
        assertTrue(sidebarVpnUnavailableSources(
            vpn, WireGuardRouteState.Idle, InternetReachability.UNKNOWN).isEmpty())
    }

    @Test fun dividerOfAHiddenSystemStaysAtItsPlaceInTheStoredOrder() {
        val rows = listOf(row("home"), row("system:plex", "plex"), row("system:tvheadend", "tvheadend"))
        // Divider under "emby", which has no row right now: it shows between plex and tvheadend.
        val out = sidebarRowsWithDividers(rows, setOf("emby"), listOf("plex", "emby", "tvheadend"))
        assertEquals(listOf("home", "system:plex", "-", "system:tvheadend"),
            out.map { if (it is SidebarNavRow.Item) it.destination.id else "-" })
    }

    @Test fun noDividerAfterTheLastRow() {
        val rows = listOf(row("home"), row("system:plex", "plex"))
        assertEquals(2, sidebarRowsWithDividers(rows, setOf("plex", "home"), listOf("plex")).count { it is SidebarNavRow.Item })
        assertEquals(3, sidebarRowsWithDividers(rows, setOf("plex", "home"), listOf("plex")).size)
    }

    @Test fun fallbackOnASystemPageStaysOnThatPage() {
        assertEquals("tvheadend:live", sidebarFocusFallback(true, listOf("tvheadend:live", "tvheadend:guide"), "home", "search"))
        assertEquals("settings", sidebarFocusFallback(true, emptyList(), "home", "search"))
        assertEquals("home", sidebarFocusFallback(false, listOf("search", "home"), "home", "search"))
        assertEquals("search", sidebarFocusFallback(false, listOf("search", "home"), "plex:1", "search"))
    }

    @Test fun entryOrderPutsKnownIdsFirstAndKeepsNewEntries() {
        val entries = listOf("a", "b", "c", "new")
        assertEquals(listOf("c", "a", "b", "new"), sidebarOrderedEntries(entries, listOf("c", "gone", "a", "b")) { it })
        assertEquals(entries, sidebarOrderedEntries(entries, null) { it })
    }

    @Test fun iptvParticipatesInTheSharedMenuEditor() {
        val state = SidebarUiState(
            liveTvSources = listOf(AvailableLiveTvSource(
                LiveTvSource("iptv:one", "iptv", "One"),
                LiveTvCapabilities(available = true, guide = true),
            )),
        )
        assertEquals(listOf(MediaSystem.IPTV), menuOrderSystems(state))
    }

    @Test fun monogramParticipatesInTheSharedMenuEditorLikeSmartTube() {
        val state = SidebarUiState(youtubeEnabled = true, telegramEnabled = true, telegramAuthorized = true, systemOrder = listOf("telegram", "youtube"))
        assertEquals(listOf(MediaSystem.TELEGRAM, MediaSystem.YOUTUBE), menuOrderSystems(state))
        assertEquals(listOf(MediaSystem.YOUTUBE), menuOrderSystems(state.copy(telegramAuthorized = false)))
    }

    @Test fun radioParticipatesInTheEditorAndKeepsItsSavedPosition() {
        val state = SidebarUiState(plexEnabled = true, radioEnabled = true, systemOrder = listOf("radio", "plex"))
        assertEquals(listOf(MediaSystem.RADIO, MediaSystem.PLEX), menuOrderSystems(state))
        assertEquals(listOf(MediaSystem.PLEX), menuOrderSystems(state.copy(radioEnabled = false)))
    }

    @Test fun weatherSharesTheSystemOrderAndDividersWithoutOpeningASubmenu() {
        val globals = listOf(MenuDestination("home", "Home", Icons.Filled.Home, Destinations.HOME))
        val systems = listOf("plex", "radio").map { id ->
            SidebarNavSystem(id, id, listOf(MenuDestination("$id:home", id, Icons.Filled.Home, id)), false, Icons.Filled.Home)
        }
        val weather = MenuDestination("weather", "Draußen", Icons.Filled.Home, Destinations.WEATHER)
        val order = listOf("radio", "weather", "plex")
        val rows = sidebarMainRows(globals, systems, Destinations.WEATHER, listOf(weather), order)

        assertEquals(listOf("home", "system:radio", "weather", "system:plex"), rows.map { it.destination.id })
        assertEquals("Draußen", rows[2].destination.label)
        assertTrue(rows[2].active)
        assertFalse(rows[2].opensPage)
        assertEquals(Destinations.WEATHER, rows[2].destination.route)
        assertEquals(listOf("home", "system:radio", "weather", "-", "system:plex"),
            sidebarRowsWithDividers(rows, setOf("weather"), order).map {
                if (it is SidebarNavRow.Item) it.destination.id else "-"
            })
    }
}
