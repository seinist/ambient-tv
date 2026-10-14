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
}
