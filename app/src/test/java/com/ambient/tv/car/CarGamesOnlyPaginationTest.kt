package com.ambient.tv.car

import com.ambient.tv.data.files.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarGamesOnlyPaginationTest {
    private fun file(name: String) = FileEntry(
        sourceId = "nas", path = name, name = name, directory = false, mime = "audio/mpeg")

    @Test fun hiddenGameFoldersDoNotShiftTheNextMusicCursor() {
        val raw = listOf(file("hidden1"), file("track-a"), file("hidden2"),
            file("track-b"), file("track-c"))
        val visible: (FileEntry) -> Boolean = { !it.name.startsWith("hidden") }
        val first = sliceVisibleCarFiles(raw, 0, 2, false, 2000, visible)
        assertEquals(listOf("track-a", "track-b"), first.items.map { it.name })
        assertEquals(4, first.nextOffset)
        assertTrue(first.hasMore)
        val second = sliceVisibleCarFiles(raw, first.nextOffset, 2, false, 2000, visible)
        assertEquals(listOf("track-c"), second.items.map { it.name })
        assertFalse(second.hasMore)
    }

    @Test fun entirelyHiddenPrefixCannotInventVisibleItems() {
        val raw = listOf(file("hidden1"), file("hidden2"), file("track-a"))
        val page = sliceVisibleCarFiles(raw, 0, 2, false, 2000) { it.name == "track-a" }
        assertEquals(listOf("track-a"), page.items.map { it.name })
        assertEquals(3, page.nextOffset)
        assertFalse(page.hasMore)
    }
}
