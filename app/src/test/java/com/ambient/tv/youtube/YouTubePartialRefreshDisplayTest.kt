package com.ambient.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePartialRefreshDisplayTest {
    private val key = "feed:subscriptions"
    private val playerRows = listOf(YouTubeRow("Subscriptions", (0 until 150).map { item("video-$it") }))

    @Test fun partialBatchPreservesPlayerPagesBeyondTheCacheLimit() {
        val cache = YouTubeContentCache()
        cache.merge(key, playerRows)
        val incoming = listOf(YouTubeRow("Subscriptions", listOf(item("video-149"), item("video-0"))))

        val cached = cache.merge(key, incoming, preferIncomingOrder = true)
        val visible = youtubeRowsAfterPartialRefresh(playerRows, incoming, pinned = false)

        assertEquals(YouTubeContentCache.MAX_ITEMS_PER_ROW, cached.single().items.size)
        assertEquals(playerRows.single().items.map { it.videoId }.toSet(), visible.single().items.map { it.videoId }.toSet())
        assertEquals("video-149", visible.single().items.first().videoId)
        assertEquals(150, visible.single().items.size)
    }

    @Test fun incompleteFinalKeepsMissingPlayerPagesEvenAfterCacheEviction() {
        val cache = YouTubeContentCache()
        val incoming = listOf(YouTubeRow("Subscriptions", listOf(item("new"), item("video-0"))))

        // The cache entry is absent, but the returned playback visit remains on screen.
        cache.persistPartial(key, incoming, preferIncomingOrder = true)
        val visible = youtubeRowsAfterPartialRefresh(playerRows, incoming, pinned = false)

        assertEquals(150, visible.single().items.size)
        assertTrue(visible.single().items.any { it.videoId == "video-149" })
        assertEquals(2, cache.get(key)!!.single().items.size)

        // A subsequently completed refresh is allowed to replace that old membership.
        val completed = cache.replace(key, incoming, preferIncomingOrder = true)
        assertEquals(listOf("new", "video-0"), youtubeRowsAfterRefresh(visible, completed, pinned = false)
            .single().items.map { it.videoId })
    }

    @Test fun backgroundPartialKeepsThePinnedVisitUnchanged() {
        val incoming = listOf(YouTubeRow("Subscriptions", listOf(item("video-149"), item("new"))))

        assertSame(playerRows, youtubeRowsAfterPartialRefresh(playerRows, incoming, pinned = true))
    }

    private fun item(id: String) = YouTubeItem(id, id)
}
