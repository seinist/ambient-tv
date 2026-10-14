package com.ambient.tv.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarRulesTest {
    private fun row(id: String, system: String? = null) =
        SidebarNavRow.Item(MenuDestination(id, id, Icons.Filled.Home, id), active = false, systemId = system, opensPage = system != null)

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
}
