package com.ambient.tv.data.browse

import org.junit.Assert.*
import org.junit.Test

class BrowseReconciliationTest {
    private data class Card(val id: String, val title: String)
    @Test fun completeRefreshRemovesMissingItemsUpdatesFieldsAndReusesUnchangedObjects() {
        val unchanged = Card("a", "A")
        val before = listOf(unchanged, Card("b", "old"), Card("gone", "Gone"))
        val after = reconcileComplete(before, listOf(Card("b", "new"), Card("a", "A"), Card("new", "N"))) { it.id }
        assertEquals(listOf("b", "a", "new"), after.map { it.id })
        assertEquals("new", after.first().title)
        assertSame(unchanged, after[1])
        assertSame(after, reconcileComplete(after, after.map { it.copy() }) { it.id })
        assertTrue(reconcileComplete(before, emptyList()) { it.id }.isEmpty())
    }
    @Test fun focusSurvivesReorderingAndUsesNextThenPreviousNeighbourWhenRemoved() {
        val old = listOf("a", "b", "c", "d")
        assertEquals("b", survivingFocus(old, listOf("d", "b", "a"), "b"))
        assertEquals("c", survivingFocus(old, listOf("new", "c", "a"), "b"))
        assertEquals("a", survivingFocus(old, listOf("new", "a"), "b"))
        assertNull(survivingFocus(old, emptyList(), "b"))
        assertNull(survivingFocus(old, listOf("a"), null))
    }
    @Test fun artworkPersistenceStripsCredentialsAndNestedTokens() {
        val value = displayArtworkUrl("https://user:password@server/photo/:/transcode?width=400&X-Plex-Token=secret&url=https%3A%2F%2Fserver%2Fthumb%3Ftoken%3Dnested")!!
        assertFalse(value.contains("password"))
        assertFalse(value.contains("secret"))
        assertFalse(value.contains("nested"))
        assertTrue(value.contains("width=400"))
    }
}
