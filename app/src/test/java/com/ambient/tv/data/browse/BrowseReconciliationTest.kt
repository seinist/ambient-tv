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
    @Test fun artworkRequestIdentityIgnoresCredentialsButKeepsRealImageParameters() {
        val cached = "http://plex.local:32400/photo/:/transcode?width=360&height=540&url=%2Flibrary%2Fmetadata%2F42%2Fthumb"
        val live = "$cached&X-Plex-Token=fresh-secret"
        assertEquals(artworkRequestIdentity(cached), artworkRequestIdentity(live))

        val jellyCached = "https://jf.local:8096/Items/42/Images/Primary?tag=revision1&maxWidth=640"
        val jellyLive = "$jellyCached&ApiKey=secret"
        assertEquals(artworkRequestIdentity(jellyCached), artworkRequestIdentity(jellyLive))

        // Unknown query values can alter CDN transforms/revisions and therefore must not alias.
        assertNotEquals(
            artworkRequestIdentity("https://cdn.example/poster.jpg?width=640&revision=1"),
            artworkRequestIdentity("https://cdn.example/poster.jpg?width=640&revision=2"),
        )
    }

    @Test fun artworkPersistenceStripsCredentialsAndNestedTokens() {
        val value = displayArtworkUrl("https://user:password@server/photo/:/transcode?width=400&X-Plex-Token=secret&url=https%3A%2F%2Fserver%2Fthumb%3Ftoken%3Dnested")!!
        assertFalse(value.contains("password"))
        assertFalse(value.contains("secret"))
        assertFalse(value.contains("nested"))
        assertTrue(value.contains("width=400"))
    }
}
