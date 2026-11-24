package com.ambient.tv.games.metadata

import org.junit.Assert.*
import org.junit.Test

class GameArtworkPriorityTest {
    @Test fun visibleCardsAreStableAndMostRecentFocusComesFirst() {
        val demand = GameArtworkPriority()
        val first = Any()
        val second = Any()
        val last = Any()
        demand.update(first, "a", visible = true, focused = false)
        demand.update(second, "b", visible = true, focused = false)
        demand.update(last, "c", visible = true, focused = true)
        assertEquals(listOf("c", "a", "b"), demand.prioritizedFileIds())
        demand.update(second, "b", visible = true, focused = true)
        assertEquals(listOf("b", "c", "a"), demand.prioritizedFileIds())
        demand.update(last, "c", visible = true, focused = false)
        assertEquals(listOf("b", "a", "c"), demand.prioritizedFileIds())
    }

    @Test fun homeAndLibraryOwnersDeduplicateAndReleaseIndependently() {
        val demand = GameArtworkPriority()
        val home = Any()
        val library = Any()
        demand.update(home, "same-game", visible = true, focused = false)
        demand.update(library, "same-game", visible = true, focused = true)
        assertEquals(listOf("same-game"), demand.prioritizedFileIds())
        demand.remove(library)
        assertEquals(listOf("same-game"), demand.prioritizedFileIds())
        demand.remove(home)
        assertTrue(demand.prioritizedFileIds().isEmpty())
    }

    @Test fun offscreenOrInactiveCompositionsNeverRetainDemand() {
        val demand = GameArtworkPriority()
        val owner = Any()
        demand.update(owner, "game", visible = false, focused = true)
        assertTrue(demand.prioritizedFileIds().isEmpty())
        demand.update(owner, "game", visible = true, focused = true)
        demand.update(owner, "game", visible = false, focused = false)
        assertTrue(demand.prioritizedFileIds().isEmpty())
    }

    @Test fun ownersStayBoundedWhileRetainingFocusedDemand() {
        val demand = GameArtworkPriority(maxOwners = 3)
        demand.update(Any(), "focused", visible = true, focused = true)
        repeat(1_000) { demand.update(Any(), "visible-$it", visible = true, focused = false) }
        assertEquals(listOf("focused", "visible-998", "visible-999"), demand.prioritizedFileIds())
    }
}
