package com.ambient.tv.ui.common

import org.junit.Assert.*
import org.junit.Test

class BrowseFocusBufferTest {
    @Test fun heldDpadBurstPersistsOnlyTheLastTargetAndKeepsItReadable() {
        val buffer = BrowseFocusBuffer()
        repeat(500) { buffer.update("movies:library", "item-$it", "row-$it") }
        val expected = BrowseFocusSnapshot("item-499", "row-499")
        assertEquals(expected, buffer.get("movies:library"))
        assertEquals(listOf("movies:library" to expected), buffer.drain())
        assertEquals(expected, buffer.get("movies:library"))
        assertTrue(buffer.drain().isEmpty())
    }

    @Test fun pagesStayIndependentAndAPlainGridClearsTheOldRecommendationRow() {
        val buffer = BrowseFocusBuffer()
        buffer.update("recommendations", "shared", "second-row")
        buffer.update("library", "other", null)
        assertEquals(2, buffer.drain().size)
        buffer.update("recommendations", "shared", null)
        assertEquals(listOf("recommendations" to BrowseFocusSnapshot("shared", null)), buffer.drain())
        buffer.update("library", "other", null)
        assertTrue(buffer.drain().isEmpty())
    }
}
