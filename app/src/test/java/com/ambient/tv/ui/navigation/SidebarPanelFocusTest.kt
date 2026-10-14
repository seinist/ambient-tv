package com.ambient.tv.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarPanelFocusTest {
    private fun d(id: String, route: String = id) = MenuDestination(id, id, Icons.Filled.Home, route)
    private val globals = listOf(d("search"), d("home"))
    private fun libs(n: Int) = (1..n).map { d("plex:$it", "section/$it") }

    private val plexEntries = listOf(d("plex:overview", "plex")) + libs(6)
    private fun systems(activeRoute: String?) = listOf(
        SidebarNavSystem("plex", "Plex", plexEntries, plexEntries.any { it.route == activeRoute }, Icons.Filled.Home),
        SidebarNavSystem("tvheadend", "TVHeadend", listOf(d("tvh:live", "live_tv"), d("tvh:guide", "guide")), activeRoute in setOf("live_tv", "guide"), Icons.Filled.Home),
        SidebarNavSystem("empty", "Empty", emptyList(), false, Icons.Filled.Home),
    )
    private fun ids(r: List<SidebarNavRow.Item>) = r.map { it.destination.id }

    @Test fun `main page lists one row per system, no more and no headings`() {
        val r = sidebarMainRows(globals, systems(null), null)
        assertEquals(listOf("search", "home", "system:plex", "system:tvheadend"), ids(r))
        assertTrue(r.drop(2).all { it.opensPage })
    }

    @Test fun `system page lists every entry plus the settings shortcut`() {
        val settings = MenuDestination("settings:plex", "Plex-Einstellungen", Icons.Filled.Home, "settings", settingsCategory = "plex")
        val r = sidebarPageRows("plex", plexEntries, settings, "section/6")
        assertEquals(ids(plexEntries.map { SidebarNavRow.Item(it, false) }) + "settings:plex", ids(r))
        // Entering lands on the active entry, even the 6th library (no "More" folding).
        assertEquals("plex:6", sidebarActiveRowId(r))
    }

    @Test fun `back from a system page lands on its main row`() {
        val main = ids(sidebarMainRows(globals, systems(null), null))
        assertTrue(sidebarPageBackTarget("plex") in main)
        // Back on a system page (a parent level exists) leaves the page instead of the bar …
        assertEquals(SidebarBackAction.LEAVE_NESTED_LEVEL, sidebarBackAction(miniPlayerHasFocus = false, hasParentLevel = true))
        // … and Back on the main page still returns to content as before.
        assertEquals(SidebarBackAction.RETURN_TO_CONTENT, sidebarBackAction(miniPlayerHasFocus = false, hasParentLevel = false))
    }

    @Test fun `entering focuses the active row, not the first`() {
        assertEquals("system:tvheadend", sidebarActiveRowId(sidebarMainRows(globals, systems("guide"), "guide")))
        assertEquals("home", sidebarActiveRowId(sidebarMainRows(globals, systems("home"), "home")))
        assertEquals("plex:3", sidebarEntryTarget("plex:3", listOf("plex:3"), "search"))
        assertEquals("search", sidebarEntryTarget(null, listOf("search"), "search"))
        assertEquals("search", sidebarEntryTarget("gone", listOf("search"), "search"))
    }

    @Test fun `edges wrap around`() {
        val order = listOf("search", "home", "settings")
        assertEquals("settings", sidebarWrapTarget(order, "search", up = true, miniPlayerBelow = false))
        assertEquals("settings", sidebarWrapTarget(order, "search", up = true, miniPlayerBelow = true))
        assertEquals("search", sidebarWrapTarget(order, "settings", up = false, miniPlayerBelow = false))
        assertNull(sidebarWrapTarget(order, "settings", up = false, miniPlayerBelow = true))
        assertNull(sidebarWrapTarget(order, "home", up = true, miniPlayerBelow = false))
    }

    @Test fun `held left never opens the panel`() {
        assertTrue(sidebarEntryArmed(true, 0))
        assertFalse(sidebarEntryArmed(true, 3))
        assertFalse(sidebarEntryArmed(false, 0))
    }

    @Test fun `panel width follows the widest label and is clamped`() {
        // Settings + reorder button dominates short labels → 227 dp.
        assertEquals(227f, sidebarPanelWidthDp(60f, 80f, hasReorder = true, screenWidthDp = 960f))
        // A long label widens it: (62 + 160) * 1.02 + 28 = 254.44 → 255.
        assertEquals(255f, sidebarPanelWidthDp(160f, 80f, hasReorder = true, screenWidthDp = 960f))
        assertEquals(336f, sidebarPanelWidthDp(900f, 80f, hasReorder = true, screenWidthDp = 960f))
        assertEquals(200f, sidebarPanelWidthDp(0f, 0f, hasReorder = false, screenWidthDp = 400f))
    }

    @Test fun `dividers sit below marked entries and focus order skips them`() {
        val main = sidebarMainRows(globals, systems(null), null)
        val shown = sidebarRowsWithDividers(main, setOf("home", "plex", "tvheadend"))
        // Below Home and Plex, never after the last row.
        assertEquals(listOf("search", "home", "|", "system:plex", "|", "system:tvheadend"),
            shown.map { (it as? SidebarNavRow.Item)?.destination?.id ?: "|" })
        val focusOrder = shown.filterIsInstance<SidebarNavRow.Item>().map { it.destination.id }
        assertEquals(ids(main), focusOrder)
        assertNull(sidebarWrapTarget(focusOrder, "home", up = false, miniPlayerBelow = false))
        assertEquals(setOf("a"), sidebarToggleDivider(emptySet(), "a"))
        assertEquals(emptySet<String>(), sidebarToggleDivider(setOf("a"), "a"))
    }
}
