package com.ambient.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeContentCacheTest {
    @Test
    fun mergeKeepsExistingRowsAndDeduplicatesVideos() {
        val cache = YouTubeContentCache()
        cache.merge("home", listOf(YouTubeRow("Featured", listOf(item("one"))), YouTubeRow("Saved", listOf(item("saved")))))

        val rows = cache.merge("home", listOf(YouTubeRow("Featured", listOf(item("one"), item("two")))))

        assertEquals(listOf("one", "two"), rows.first().items.map { it.videoId })
        assertEquals("Saved", rows.last().title)
    }

    @Test fun partialAndCompleteRefreshDoNotReindexVisibleRowsOrCards() {
        val cache = YouTubeContentCache()
        cache.replace("subscriptions", listOf(
            YouTubeRow("Subscriptions", listOf(item("a"), item("b"))),
            YouTubeRow("Later", listOf(item("c"))),
        ))
        val partial = cache.merge("subscriptions", listOf(
            YouTubeRow("New at source top", listOf(item("new"))),
            YouTubeRow("Subscriptions", listOf(item("fresh"), item("a"), item("b"))),
        ))
        assertEquals(listOf("Subscriptions", "Later", "New at source top"), partial.map { it.id })
        assertEquals(listOf("a", "b", "fresh"), partial.first().items.map { it.videoId })
        val completed = cache.replace("subscriptions", listOf(
            YouTubeRow("New at source top", listOf(item("new"))),
            YouTubeRow("Subscriptions", listOf(item("fresh"), item("a"), item("b"))),
        ))
        assertEquals(listOf("Subscriptions", "New at source top"), completed.map { it.id })
        assertEquals(listOf("a", "b", "fresh"), completed.first().items.map { it.videoId })
    }

    @Test
    fun cacheEvictsLeastRecentlyUsedEntryAtCapacity() {
        val cache = YouTubeContentCache()
        repeat(24) { index -> cache.merge("feed-$index", listOf(YouTubeRow("Row", listOf(item("$index"))))) }
        cache.get("feed-0")

        cache.merge("feed-new", listOf(YouTubeRow("Row", listOf(item("new")))))

        assertNull(cache.get("feed-1"))
        assertEquals("0", cache.get("feed-0")!!.single().items.single().videoId)
    }

    @Test
    fun mergeBoundsRowsAndItems() {
        val cache = YouTubeContentCache()
        val rows = (0 until 30).map { row ->
            YouTubeRow("Row $row", (0 until 140).map { item("$row-$it") })
        }

        val cached = cache.merge("large", rows)

        assertEquals(24, cached.size)
        assertEquals(120, cached.first().items.size)
    }

    @Test fun completeRefreshRemovesRowsAndVideosWhilePartialRefreshRetainsThem() {
        val cache = YouTubeContentCache()
        cache.replace("home", listOf(YouTubeRow("Featured", listOf(item("a"), item("b"))), YouTubeRow("Old", listOf(item("c")))))
        val partial = cache.merge("home", listOf(YouTubeRow("Featured", listOf(item("a").copy(title = "Updated")))))
        assertEquals("Updated", partial.first().items.first().title)
        assertEquals(2, partial.size)
        assertEquals(2, partial.first().items.size)
        val completed = cache.replace("home", listOf(YouTubeRow("Featured", listOf(item("a")))))
        assertEquals(1, completed.size)
        assertEquals(listOf("a"), completed.single().items.map { it.videoId })
        assertEquals(emptyList<YouTubeRow>(), cache.replace("home", emptyList()))
    }

    @Test fun logoutRejectsLateResponsesFromPreviousAccount() {
        val cache = YouTubeContentCache()
        val revision = cache.revision()
        cache.clear()
        cache.replace("home", listOf(YouTubeRow("Private", listOf(item("a")))), revision)
        assertNull(cache.get("home"))
    }

    private fun item(id: String) = YouTubeItem(videoId = id, title = id)
}
