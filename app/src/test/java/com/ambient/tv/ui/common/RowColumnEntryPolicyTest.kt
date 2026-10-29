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
}
