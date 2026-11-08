package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowseRefreshFocusPolicyTest {
    private fun key(item: String, row: String? = "row") = BrowseFocusKey(row, item, true)
    private fun snapshot(vararg items: String) = BrowseRefreshFocusSnapshot(listOf("row" to items.toList()))
    private val before = snapshot("a", "b", "focused", "c", "d")
    private val anchor = checkNotNull(before.anchor(key("focused")))

    @Test fun insertionAndReorderKeepTheExactFocusedVideo() {
        val refreshed = snapshot("new", "d", "c", "focused", "a", "b")
        assertEquals(key("focused"), refreshed.resolve(anchor))
    }

    @Test fun missingVideoChoosesItsOldImmediateNextEvenAfterReordering() {
        val refreshed = snapshot("new", "b", "d", "a", "c")
        assertEquals(key("c"), refreshed.resolve(anchor))
    }

    @Test fun missingNextChoosesOnlyTheOldImmediatePrevious() {
        val refreshed = snapshot("new", "d", "a", "b")
        assertEquals(key("b"), refreshed.resolve(anchor))
    }

    @Test fun bothImmediateNeighboursGoneResetToIndexZeroDespiteFartherSurvivors() {
        val refreshed = snapshot("new", "d", "a")
        assertEquals(key("new"), refreshed.resolve(anchor))
    }

    @Test fun missingWholeRowResetsToFirstRowAndFirstVideo() {
        val refreshed = BrowseRefreshFocusSnapshot(listOf(
            "first" to listOf("first-video", "other"),
            "later" to listOf("focused", "b", "c"),
        ))
        assertEquals(key("first-video", "first"), refreshed.resolve(anchor))
    }

    @Test fun duplicateVideoInAnotherRowDoesNotReplaceTheRemovedOccurrence() {
        val refreshed = BrowseRefreshFocusSnapshot(listOf(
            "unrelated" to listOf("focused", "c"),
            "row" to listOf("new", "b", "d"),
        ))
        assertEquals(key("b"), refreshed.resolve(anchor))
    }

    @Test fun deletingFirstOrLastItemHandlesTheAbsentNeighbour() {
        val old = snapshot("a", "b", "c")
        assertEquals(key("b"), snapshot("b", "c").resolve(checkNotNull(old.anchor(key("a")))))
        assertEquals(key("b"), snapshot("a", "b").resolve(checkNotNull(old.anchor(key("c")))))
    }

    @Test fun duplicateRegistrationDoesNotTurnTheSameTileIntoItsOwnNeighbour() {
        val old = BrowseRefreshFocusSnapshot(listOf(
            "row" to listOf("a", "focused", "c"),
            "row" to listOf("a", "focused", "c"),
        ))
        assertEquals(key("c"), snapshot("new", "a", "c").resolve(checkNotNull(old.anchor(key("focused")))))
    }

    @Test fun emptyFeedHasNoFocusTarget() {
        assertNull(BrowseRefreshFocusSnapshot(emptyList()).resolve(anchor))
    }

    @Test fun flatGridUsesTheSameImmediateNeighbourPolicy() {
        val old = BrowseRefreshFocusSnapshot(listOf(null to listOf("a", "focused", "c")))
        val refreshed = BrowseRefreshFocusSnapshot(listOf(null to listOf("new", "a", "c")))
        assertEquals(key("c", null), refreshed.resolve(checkNotNull(old.anchor(key("focused", null)))))
    }

    @Test fun contentMemoryStartsAtIndexZeroWithoutASavedFocus() {
        val memory = ContentRefreshFocusMemory()
        assertEquals(ContentFocusTarget("a", "row"), memory.resolve(null, null, listOf("row" to listOf("a", "b"))))
    }

    @Test fun contentMemoryCapturesTheLastDisplayedImmediateNeighbours() {
        val memory = ContentRefreshFocusMemory()
        memory.resolve("focused", "row", listOf("row" to listOf("a", "focused", "c")))
        memory.resolve("focused", "row", listOf("row" to listOf("a", "focused", "inserted", "c")))
        assertEquals(ContentFocusTarget("inserted", "row"),
            memory.resolve("focused", "row", listOf("row" to listOf("new", "a", "inserted", "c"))))
    }

    @Test fun toolbarReturnKeepsOriginalNeighboursAcrossSeveralSnapshots() {
        val memory = ContentRefreshFocusMemory()
        memory.resolve("focused", "row", listOf("row" to listOf("a", "b", "focused", "c", "d")))
        assertEquals(ContentFocusTarget("c", "row"),
            memory.resolve("focused", "row", listOf("row" to listOf("new", "a", "b", "c", "d"))))
        assertEquals(ContentFocusTarget("b", "row"),
            memory.resolve("focused", "row", listOf("row" to listOf("new", "a", "b", "d"))))
        assertEquals(ContentFocusTarget("new", "row"),
            memory.resolve("focused", "row", listOf("row" to listOf("new", "a", "d"))))
    }

    @Test fun contentMemoryTracksAUserFocusChangeBeforeTheRefresh() {
        val memory = ContentRefreshFocusMemory()
        memory.resolve("a", "row", listOf("row" to listOf("a", "b", "c", "d")))
        assertEquals(ContentFocusTarget("d", "row"),
            memory.resolve("c", "row", listOf("row" to listOf("new", "a", "b", "d"))))
    }

    @Test fun newPlaybackReturnMemoryKeepsExactVideoLookupAcrossRows() {
        // A fresh memory belongs to the new return revision, not the feed refresh transaction.
        val memory = ContentRefreshFocusMemory()
        assertEquals(ContentFocusTarget("played", "new-row"), memory.resolve(
            "played", "old-row", listOf("first" to listOf("a"), "new-row" to listOf("played")),
        ))
    }
}
