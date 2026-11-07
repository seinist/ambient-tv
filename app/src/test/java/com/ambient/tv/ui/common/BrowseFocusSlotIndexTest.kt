package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BrowseFocusSlotIndexTest {
    @Test fun repeatedFocusLookupsDoNotTraverseAnUnchangedLargeLibrary() {
        val entries = List(10_000) { slot("item-$it", it) }
        val slots = CountingSlots(entries)
        val index = BrowseFocusSlotIndex { slots }

        assertSame(entries.last(), index[entries.last().key])
        val readsAfterInitialLookup = slots.itemReads
        val missing = BrowseFocusKey(null, "removed", true)
        repeat(200) { step ->
            val expected = entries[(step * 97) % entries.size]
            assertSame(expected, index[expected.key])
            assertNull(index[missing])
        }

        // Both normal focus transitions and a missing-key check must use the published index.
        // A list scan here grows with library size even when only two visible cards change.
        assertEquals(readsAfterInitialLookup, slots.itemReads)
    }

    @Test fun replacementSnapshotsRefreshReorderedRemovedAndClearedSlots() {
        val first = slot("first", 2)
        val second = slot("second", 7)
        var slots = listOf(first, second)
        val index = BrowseFocusSlotIndex { slots }
        assertEquals(2, index[first.key]?.index)
        assertEquals(7, index[second.key]?.index)

        slots = listOf(second.copy(index = 1), first.copy(index = 9))
        assertEquals(9, index[first.key]?.index)
        assertEquals(1, index[second.key]?.index)

        slots = listOf(second.copy(index = 3))
        assertNull(index[first.key])
        assertEquals(3, index[second.key]?.index)
        assertEquals(second.key, missingBrowseFocusFallback(first.key, slots))

        slots = emptyList()
        assertNull(index[second.key])
        assertNull(missingBrowseFocusFallback(second.key, slots))
    }

    @Test fun duplicateIdsPreserveRowContentAndFirstPhysicalSlot() {
        val firstRow = slot("shared", 1, row = "first")
        val secondRow = slot("shared", 5, row = "second")
        val header = BrowseFocusSlot(BrowseFocusKey("second", "shared", false), 0)
        val slots = listOf(firstRow, secondRow, secondRow.copy(index = 12), header)
        val index = BrowseFocusSlotIndex { slots }

        assertSame(firstRow, index[firstRow.key])
        assertSame(secondRow, index[secondRow.key])
        assertSame(header, index[header.key])
        assertNull(index[BrowseFocusKey(null, "shared", true)])
        assertEquals(secondRow.key,
            missingBrowseFocusFallback(BrowseFocusKey("second", "removed", true), slots))
    }

    private fun slot(item: String, index: Int, row: Any? = null) =
        BrowseFocusSlot(BrowseFocusKey(row, item, true), index)

    private class CountingSlots(private val entries: List<BrowseFocusSlot>) : AbstractList<BrowseFocusSlot>() {
        var itemReads = 0
            private set
        override val size: Int get() = entries.size
        override fun get(index: Int): BrowseFocusSlot {
            itemReads++
            return entries[index]
        }
    }
}
