package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RowColumnEntryPolicyTest {
    @Test fun unvisitedRowsUseTheSuggestedEpisodeOrTheFirstItem() {
        val keys = listOf("episode:1", "episode:2", "episode:3")
        assertEquals(2, rowColumnEntryIndex(keys, null, "episode:3"))
        assertEquals(0, rowColumnEntryIndex(keys, null, null))
    }

    @Test fun rememberedIdentitySurvivesReorderingAndAChangedSuggestion() {
        assertEquals(1, rowColumnEntryIndex(listOf("a", "b", "c"), "b", "c"))
        assertEquals(2, rowColumnEntryIndex(listOf("c", "a", "b"), "b", "a"))
    }

    @Test fun removedItemsFallBackWithoutSelectingAnOutOfBoundsIndex() {
        assertEquals(1, rowColumnEntryIndex(listOf("a", "c"), "b", "c"))
        assertEquals(0, rowColumnEntryIndex(listOf("a", "c"), "b", "d"))
        assertNull(rowColumnEntryIndex(emptyList(), "b", "c"))
    }

    @Test fun asynchronousSuggestionOnlyAffectsRowsWithoutAValidUserPosition() {
        val keys = listOf("a", "b", "c")
        assertEquals(0, rowColumnEntryIndex(keys, null, null))
        assertEquals(2, rowColumnEntryIndex(keys, null, "c"))
        assertEquals(0, rowColumnEntryIndex(keys, "a", "c"))
    }

    @Test fun disabledRememberedAndSuggestedItemsFallBackToAnEnabledPhysicalIndex() {
        val keys = listOf("disabled:0", "a", "disabled:2", "b")
        val enabled = listOf("a", "b")
        assertEquals(3, rowColumnEntryIndex(keys, "b", "a", enabled))
        assertEquals(3, rowColumnEntryIndex(keys, "disabled:0", "b", enabled))
        assertEquals(1, rowColumnEntryIndex(keys, "disabled:0", "disabled:2", enabled))
    }

    @Test fun staleOrEmptyEnabledListsDoNotProduceUnavailableTargets() {
        val keys = listOf("a", "b", "c")
        assertEquals(2, rowColumnEntryIndex(keys, null, null, listOf("removed", "c")))
        assertNull(rowColumnEntryIndex(keys, "a", "b", emptyList()))
        assertNull(rowColumnEntryIndex(keys, "removed", "removed", listOf("removed")))
    }
}
