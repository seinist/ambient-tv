package com.ambient.tv.ui.home

import com.ambient.tv.data.local.HomeRowDefinition
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRowRevealTest {
    private val definitions = listOf(
        HomeRowDefinition(system = "plex", contentKey = "continue"),
        HomeRowDefinition(system = "plex", contentKey = "recent"),
        HomeRowDefinition(system = "tvheadend", contentKey = "live"),
    )

    private fun reveal(contentIds: List<String>, hasLive: Boolean, pending: Set<String>): List<String> {
        val ordered = orderedHomeRowKeys(definitions, contentIds, emptyList(), hasLive, false)
        val sources = homeRowKeySources(contentIds, emptyList(), hasLive, false)
        return revealHomeRowsInOrder(definitions, ordered, sources, pending)
    }

    @Test fun liveTvWaitsWhileLibraryRowsAboveItAreStillLoading() {
        assertEquals(emptyList<String>(), reveal(emptyList(), hasLive = true, pending = setOf("plex")))
        assertEquals(listOf("plex.continue"),
            reveal(listOf("plex.continue"), hasLive = true, pending = setOf("plex")))
    }

    @Test fun everythingShowsOnceTheRowsAboveArrivedOrLoadingEnded() {
        assertEquals(listOf("plex.continue", "plex.recentlyAdded", "live-tv"),
            reveal(listOf("plex.continue", "plex.recentlyAdded"), hasLive = true, pending = setOf("plex")))
        assertEquals(listOf("plex.continue", "live-tv"),
            reveal(listOf("plex.continue"), hasLive = true, pending = emptySet()))
    }

    @Test fun rowsAboveAPendingSourceAreNeverHeld() {
        val defs = listOf(
            HomeRowDefinition(system = "tvheadend", contentKey = "live"),
            HomeRowDefinition(system = "plex", contentKey = "continue"),
        )
        val ordered = orderedHomeRowKeys(defs, emptyList(), emptyList(), true, false)
        val sources = homeRowKeySources(emptyList(), emptyList(), true, false)
        assertEquals(listOf("live-tv"), revealHomeRowsInOrder(defs, ordered, sources, setOf("plex")))
    }

    @Test fun disabledRowsAndRowsWithoutAPositionDoNotBlock() {
        val defs = listOf(
            HomeRowDefinition(system = "plex", contentKey = "continue", enabled = false),
            HomeRowDefinition(system = "tvheadend", contentKey = "live"),
        )
        val ordered = orderedHomeRowKeys(defs, listOf("all.continue"), emptyList(), true, false)
        val sources = homeRowKeySources(listOf("all.continue"), emptyList(), true, false)
        assertEquals(ordered, revealHomeRowsInOrder(defs, ordered, sources, setOf("plex")))
    }
}
