package com.ambient.tv.youtube

import org.junit.Assert.*
import org.junit.Test

class YouTubePublishedAndOrderTest {
    private val now = 1_000_000_000_000L
    private val day = 86_400_000L

    @Test fun relativeLabelsInEnglishAndGerman() {
        assertEquals(now - 3 * day, youtubePublishedAtMs("3 days ago", now))
        assertEquals(now - 2 * 7 * day, youtubePublishedAtMs("vor 2 Wochen", now))
        assertEquals(now - 5 * 3_600_000L, youtubePublishedAtMs("Streamed 5 hours ago", now))
        assertEquals(now - day, youtubePublishedAtMs("Gestern", now))
        assertNull(youtubePublishedAtMs("Premiere", now))
        assertNull(youtubePublishedAtMs(null, now))
    }

    @Test fun nextVisitAdoptsTheServerOrderOfTheLastCompletedLoad() {
        val cache = YouTubeContentCache()
        val old = listOf(YouTubeRow("subs", listOf(YouTubeItem("1", "1"), YouTubeItem("2", "2"))))
        cache.merge("feed:subscriptions", old)
        val fresh = listOf(YouTubeRow("subs", listOf(YouTubeItem("new", "new"), YouTubeItem("1", "1"), YouTubeItem("2", "2"))))
        // During the visit the order stays stable: the new video is appended.
        assertEquals(listOf("1", "2", "new"), cache.replace("feed:subscriptions", fresh).single().items.map { it.videoId })
        cache.adoptFreshOrder("feed:subscriptions")
        assertEquals(listOf("new", "1", "2"), cache.get("feed:subscriptions")!!.single().items.map { it.videoId })
    }
}
