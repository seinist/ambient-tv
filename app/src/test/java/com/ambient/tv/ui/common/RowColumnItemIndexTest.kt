package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RowColumnItemIndexTest {
    @Test fun repeatedEntryAndRightPressesDoNotTraverseUnchangedRowSnapshots() {
        val values = List(10_000) { "item-$it" }
        val items = CountingKeys(values)
        val enabled = CountingKeys(values.filterIndexed { index, _ -> index % 2 == 1 })
        val index = RowColumnItemIndex()
        assertEquals(9_999, index.entryIndex(items, "item-9999", null, enabled))
        val itemReads = items.reads
        val enabledReads = enabled.reads
        repeat(200) {
            assertEquals(9_999, index.entryIndex(items, "item-9999", null, enabled))
            assertEquals(1, index.entryIndex(items, "removed", "disabled", enabled))
            assertNull(index.wrapRightIndex(items, "item-3", enabled))
            assertEquals(1, index.wrapRightIndex(items, "item-9999", enabled))
            assertEquals(9_999, index.wrapLeftIndex(items, "item-1", enabled))
            assertNull(index.wrapLeftIndex(items, "item-3", enabled))
        }
        assertEquals(itemReads, items.reads)
        assertEquals(enabledReads, enabled.reads)
    }

    @Test fun reorderedRemovedAndEnabledSnapshotsInvalidatePhysicalIndicesAndEndpoints() {
        val index = RowColumnItemIndex()
        var items = listOf("disabled", "a", "b", "c")
        var enabled = listOf("a", "b", "c")
        assertEquals(2, index.entryIndex(items, "b", "a", enabled))
        assertEquals(1, index.wrapRightIndex(items, "c", enabled))
        assertEquals(3, index.wrapLeftIndex(items, "a", enabled))
        assertNull(index.wrapLeftIndex(items, "b", enabled))
        items = listOf("c", "disabled", "a", "b")
        assertEquals(3, index.entryIndex(items, "b", "a", enabled))
        assertNull(index.wrapRightIndex(items, "c", enabled))
        assertEquals(0, index.wrapRightIndex(items, "b", enabled))
        assertEquals(3, index.wrapLeftIndex(items, "c", enabled))
        enabled = listOf("a")
        assertEquals(2, index.entryIndex(items, "b", "a", enabled))
        assertNull(index.wrapRightIndex(items, "b", enabled))
        assertEquals(2, index.wrapRightIndex(items, "a", enabled))
        assertEquals(2, index.wrapLeftIndex(items, "a", enabled))
        items = listOf("c", "disabled")
        assertNull(index.entryIndex(items, "a", "b", enabled))
        assertNull(index.wrapRightIndex(items, "a", enabled))
        assertNull(index.wrapLeftIndex(items, "a", enabled))
        enabled = emptyList()
        assertNull(index.entryIndex(items, "c", null, enabled))
    }

    @Test fun duplicateIdsKeepFirstMatchAndRememberedEntryPrecedesTheSuggestion() {
        val items = listOf("a", "b", "a", "disabled", "b")
        val index = RowColumnItemIndex()
        assertEquals(0, index.entryIndex(items, "a", "b"))
        assertEquals(1, index.entryIndex(items, "b", "a"))
        assertEquals(0, index.wrapRightIndex(items, "b"))
        assertNull(index.wrapRightIndex(items, "a"))
        assertEquals(4, index.wrapLeftIndex(items, "a"))
        assertNull(index.wrapLeftIndex(items, "b"))
    }

    private class CountingKeys(private val values: List<String>) : AbstractList<Any>() {
        var reads = 0
            private set
        override val size: Int get() = values.size
        override fun get(index: Int): Any {
            reads++
            return values[index]
        }
    }
}
