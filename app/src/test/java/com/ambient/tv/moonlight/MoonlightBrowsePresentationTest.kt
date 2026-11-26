package com.ambient.tv.moonlight

import com.ambient.tv.domain.model.GameBrowseItem
import com.ambient.tv.ui.moonlight.MoonlightLibraryUiState
import org.junit.Assert.*
import org.junit.Test

class MoonlightBrowsePresentationTest {
    private val pc = MoonlightHost("pc", "pc.local", name = "Office")
    private val other = MoonlightHost("other", "other.local", name = "Gaming PC")
    private val state = MoonlightLibraryUiState(
        hosts = listOf(pc, other),
        catalogs = mapOf("pc" to listOf(MoonlightApp(1, "Zeta", false), MoonlightApp(2, "Alpha", true)),
            "other" to listOf(MoonlightApp(1, "Alpha", false))),
        favorites = setOf("pc:2"),
        recent = listOf("other:1", "pc:1"),
    )

    @Test fun stableKeysAreHostScopedAndSharedWithGamePresentation() {
        val games: List<GameBrowseItem> = state.games
        assertEquals(listOf("other:1", "pc:2", "pc:1"), games.map { it.fileId })
        assertEquals("Gaming PC", games.first().systemLabel)
        assertEquals(2f / 3f, games.first().coverAspectRatio, .001f)
    }

    @Test fun sectionsFilterWithoutDependingOnOnlineState() {
        assertEquals(listOf("pc:2"), state.copy(sections = "favorites").games.map { it.key })
        assertEquals(listOf("other:1", "pc:1"), state.copy(sections = "recent").games.map { it.key })
        assertEquals(listOf("pc:2", "pc:1"), state.copy(sections = "host:pc").games.map { it.key })
        assertEquals(listOf("other:1"), state.copy(query = "Gaming PC").games.map { it.key })
    }

    @Test fun coverAndAvailabilityUpdatesDoNotChangeGameListOrStableKeys() {
        val before = state.games
        val after = state.copy(
            onlineHosts = setOf("pc"),
            runningApps = mapOf("pc" to 1),
            artworkVersion = 2,
            artworkRevisions = mapOf("pc:1" to 2L),
            offlineHosts = setOf("other"),
        ).games
        assertEquals(before, after)
        assertEquals(before.map { it.key }, after.map { it.key })
    }

    @Test fun readinessWaitsForCacheOrCompletedInitialRefresh() {
        assertFalse(state.ready)
        assertTrue(state.copy(localLoaded = true).ready)
        assertFalse(MoonlightLibraryUiState(localLoaded = true).ready)
        assertTrue(MoonlightLibraryUiState(localLoaded = true, initialRefreshComplete = true).ready)
    }
}
