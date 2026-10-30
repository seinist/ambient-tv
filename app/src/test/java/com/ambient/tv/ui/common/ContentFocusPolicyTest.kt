package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentFocusPolicyTest {
    private val rows = listOf(
        "row-a" to listOf("a1", "shared"),
        "row-b" to listOf("b1", "shared"),
    )

    @Test fun initialOpenTargetsFirstItemOfFirstRow() {
        assertEquals(ContentFocusTarget("a1", "row-a"), resolveContentFocusTarget(null, null, rows))
    }

    @Test fun exactRowDisambiguatesDuplicateItems() {
        assertEquals(ContentFocusTarget("shared", "row-b"), resolveContentFocusTarget("shared", "row-b", rows))
    }

    @Test fun missingSavedItemFallsBackToTopLeft() {
        assertEquals(ContentFocusTarget("a1", "row-a"), resolveContentFocusTarget("gone", "row-b", rows))
    }

    @Test fun missingSavedRowStillFindsTheItem() {
        assertEquals(ContentFocusTarget("b1", "row-b"), resolveContentFocusTarget("b1", "gone", rows))
    }
    @Test fun removingFocusedCardKeepsItsRowAndTargetsFirstRemainingItem() {
        val gone = BrowseFocusKey("second", "removed", true)
        val slots = listOf(
            BrowseFocusSlot(BrowseFocusKey("first", "a", true), 0),
            BrowseFocusSlot(BrowseFocusKey("second", "b", true), 1),
            BrowseFocusSlot(BrowseFocusKey("second", "c", true), 2),
        )
        assertEquals(BrowseFocusKey("second", "b", true), missingBrowseFocusFallback(gone, slots))
        assertEquals(BrowseFocusKey("first", "a", true),
            missingBrowseFocusFallback(BrowseFocusKey("missing-row", "removed", true), slots))
        assertEquals(null, missingBrowseFocusFallback(gone, emptyList()))
    }


}
