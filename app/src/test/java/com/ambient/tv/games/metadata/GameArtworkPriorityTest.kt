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

    @Test fun focusAndRepeatedCompositionKeepTheVisibleGeneration() {
        val priority = GameArtworkPriority()
        val first = Any()
        val second = Any()
        priority.update(first, "a", visible = true, focused = false)
        priority.update(second, "b", visible = true, focused = true)
        val generations = priority.demands.value.associate { it.fileId to it.generation }

        priority.update(first, "a", visible = true, focused = false)
        priority.update(first, "a", visible = true, focused = true)
        priority.update(second, "b", visible = true, focused = false)

        assertEquals(listOf("a", "b"), priority.prioritizedFileIds())
        assertEquals(generations, priority.demands.value.associate { it.fileId to it.generation })
    }

    @Test fun anImmediateRemoveAndRecreateChangesTheSnapshotEvenWithIdenticalIds() {
        val priority = GameArtworkPriority()
        val owner = Any()
        priority.update(owner, "a", visible = true, focused = true)
        val before = priority.demands.value.single()

        // A collector may miss the temporary empty state, but cannot mistake this final
        // value for the old visible composition because the producer retained its generation.
        priority.remove(owner)
        priority.update(Any(), "a", visible = true, focused = true)
        val after = priority.demands.value.single()
        assertEquals(before.fileId, after.fileId)
        assertTrue(after.generation > before.generation)
    }

    @Test fun aNewOwnerCanRetryButRemovingItPreservesTheOtherOwnersGeneration() {
        val priority = GameArtworkPriority()
        val home = Any()
        val library = Any()
        priority.update(home, "a", visible = true, focused = false)
        val first = priority.demands.value.single()
        priority.update(library, "a", visible = true, focused = true)
        val shared = priority.demands.value.single()
        assertTrue(shared.generation > first.generation)

        priority.remove(library)
        priority.update(home, "a", visible = true, focused = true)
        assertEquals(shared, priority.demands.value.single())
        priority.update(home, "a", visible = false, focused = false)
        priority.update(home, "a", visible = true, focused = true)
        assertTrue(priority.demands.value.single().generation > shared.generation)
    }
}
