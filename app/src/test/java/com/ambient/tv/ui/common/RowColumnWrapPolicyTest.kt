package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RowColumnWrapPolicyTest {
    @Test fun rightAtTheLastItemTargetsTheFirstItem() {
        assertEquals(0, rowColumnWrapRightIndex(listOf("a", "b", "c"), "c"))
    }

    @Test fun rightInsideTheRowDoesNotWrap() {
        val keys = listOf("a", "b", "c")
        assertNull(rowColumnWrapRightIndex(keys, "a"))
        assertNull(rowColumnWrapRightIndex(keys, "b"))
    }

    @Test fun lastAndFirstFollowCurrentPhysicalOrderAfterARefresh() {
        assertEquals(0, rowColumnWrapRightIndex(listOf("a", "b", "c"), "c"))
        assertNull(rowColumnWrapRightIndex(listOf("c", "a", "b"), "c"))
        assertEquals(0, rowColumnWrapRightIndex(listOf("c", "a", "b"), "b"))
    }

    @Test fun removedOrMissingSourceKeysCannotWrapAnotherItem() {
        assertNull(rowColumnWrapRightIndex(listOf("a", "b"), "removed"))
        assertNull(rowColumnWrapRightIndex(listOf("a", "b"), null))
    }

    @Test fun disabledCardsKeepTheirPhysicalIndicesButAreSkippedForWrapping() {
        val keys = listOf("disabled:0", "a", "disabled:2", "b", "disabled:4")
        // Membership controls eligibility; physical order still comes from the actual row.
        val enabled = listOf("b", "a")
        assertEquals(1, rowColumnWrapRightIndex(keys, "b", enabled))
        assertNull(rowColumnWrapRightIndex(keys, "a", enabled))
        assertNull(rowColumnWrapRightIndex(keys, "disabled:4", enabled))
    }

    @Test fun emptyDisabledAndStaleRowsHaveNoWrapTarget() {
        assertNull(rowColumnWrapRightIndex(emptyList(), "a"))
        assertNull(rowColumnWrapRightIndex(listOf("a"), "a", emptyList()))
        assertNull(rowColumnWrapRightIndex(listOf("a"), "a", listOf("removed")))
        assertEquals(1, rowColumnWrapRightIndex(listOf("disabled", "a", "b"), "b", listOf("removed", "a", "b")))
    }

    @Test fun aSingleEnabledItemStaysAtItsActualIndex() {
        assertEquals(0, rowColumnWrapRightIndex(listOf("a"), "a"))
        assertEquals(1, rowColumnWrapRightIndex(listOf("disabled:0", "a", "disabled:2"), "a", listOf("a")))
    }
}
