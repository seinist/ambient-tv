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

    @Test fun explicitRefreshInsertsInSourceOrderAndRetainsMissingIdsUntilCompletion() {
        val cache = YouTubeContentCache()
        cache.replace("home", listOf(
            YouTubeRow("Recommended", listOf(item("a"), item("b"), item("c"))),
            YouTubeRow("Still pending", listOf(item("pending"))),
        ))
        val incoming = listOf(
            YouTubeRow("New section", listOf(item("section-video"))),
            YouTubeRow("Recommended", listOf(item("new"), item("b"), item("a").copy(title = "Updated A"))),
        )

        val partial = cache.merge("home", incoming, preferIncomingOrder = true)
        assertEquals(listOf("New section", "Recommended", "Still pending"), partial.map { it.id })
        assertEquals(listOf("new", "b", "a", "c"), partial[1].items.map { it.videoId })
        assertEquals("Updated A", partial[1].items[2].title)
        // Reopening during a partial refresh cannot resurrect the earlier deferred ordering.
        cache.adoptFreshOrder("home")
        assertEquals(partial, cache.get("home"))

        val completed = cache.replace("home", incoming, preferIncomingOrder = true)
        assertEquals(listOf("New section", "Recommended"), completed.map { it.id })
        assertEquals(listOf("new", "b", "a"), completed[1].items.map { it.videoId })
    }

    @Test fun explicitSourceOrderKeepsNewlyLoadedPagesOnReopening() {
        val cache = YouTubeContentCache()
        cache.replace("subscriptions", listOf(YouTubeRow("Subscriptions", listOf(item("a"), item("b")))))
        cache.replace("subscriptions", listOf(YouTubeRow("Subscriptions", listOf(item("new"), item("a")))),
            preferIncomingOrder = true)
        cache.appendItems("subscriptions", "Subscriptions", listOf(item("a"), item("page-two")))

        cache.adoptFreshOrder("subscriptions")

        assertEquals(listOf("new", "a", "page-two"), cache.get("subscriptions")!!.single().items.map { it.videoId })
    }

    @Test fun deferredSourceOrderAlsoRetainsPagesLoadedAfterItsSnapshot() {
        val cache = YouTubeContentCache()
        cache.replace("home", listOf(YouTubeRow("Recommended", listOf(item("a"), item("b")))))
        cache.replace("home", listOf(YouTubeRow("Recommended", listOf(item("new"), item("a")))))
        cache.appendItems("home", "Recommended", listOf(item("a"), item("continued")))

        cache.adoptFreshOrder("home")

        assertEquals(listOf("new", "a", "continued"), cache.get("home")!!.single().items.map { it.videoId })
    }

    @Test fun retainedPartialSnapshotSupersedesAnOlderDeferredCompleteSnapshot() {
        val cache = YouTubeContentCache()
        cache.replace("home", listOf(YouTubeRow("Recommended", listOf(item("a"), item("b")))))
        val partial = cache.persistPartial("home", listOf(YouTubeRow("Recommended", listOf(item("new"), item("a")))),
            preferIncomingOrder = true)

        cache.adoptFreshOrder("home")

        assertEquals(listOf("new", "a", "b"), partial.single().items.map { it.videoId })
        assertEquals(partial, cache.get("home"))
    }

    @Test fun partialInsertionAtCapacityCannotEvictPendingRowsOrVideos() {
        val cache = YouTubeContentCache()
        val oldRows = (0 until YouTubeContentCache.MAX_ROWS).map { row ->
            YouTubeRow("row-$row", (0 until YouTubeContentCache.MAX_ITEMS_PER_ROW).map { item("$row-$it") })
        }
        cache.replace("home", oldRows)
        val incoming = listOf(
            YouTubeRow("new-row", listOf(item("new-row-video"))),
            YouTubeRow("row-0", listOf(item("new-video"), item("0-1"), item("0-0"))),
        )

        val partial = cache.merge("home", incoming, preferIncomingOrder = true)

        assertEquals(oldRows.map { it.id }.toSet(), partial.map { it.id }.toSet())
        assertEquals(oldRows.first().items.map { it.videoId }.toSet(), partial.first().items.map { it.videoId }.toSet())
        assertEquals(listOf("0-1", "0-0"), partial.first().items.take(2).map { it.videoId })

        val completed = cache.replace("home", incoming, preferIncomingOrder = true)
        assertEquals(listOf("new-row", "row-0"), completed.map { it.id })
        assertEquals(listOf("new-video", "0-1", "0-0"), completed[1].items.map { it.videoId })
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

    @Test fun cachedFeedHumanDateRemainsRelativeRatherThanPretendingToBeExact() {
        val cache = YouTubeContentCache()
        cache.replace("home", listOf(YouTubeRow("Featured", listOf(
            item("dated").copy(publishedText = "vor 3 Tagen", publishedAtMs = 1_700_000_000_000L),
        ))))
        val cached = cache.metadata("dated")!!
        assertEquals("vor 3 Tagen", cached.publishedDate)
        assertNull(cached.publishedAtMs)
    }

    @Test fun cachedFeedRetainsProviderAbsolutePublicationTimeIfNoLabelExists() {
        val cache = YouTubeContentCache()
        cache.replace("home", listOf(YouTubeRow("Featured", listOf(
            item("dated").copy(publishedAtMs = 1_700_000_000L),
        ))))
        val cached = cache.metadata("dated")!!
        assertNull(cached.publishedDate)
        assertEquals(1_700_000_000_000L, cached.publishedAtMs)
    }

    private fun item(id: String) = YouTubeItem(videoId = id, title = id)
}
