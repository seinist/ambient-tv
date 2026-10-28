package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackBookmarksTest {
    @Test fun marksStaySortedWithoutNearDuplicates() {
        var all = addBookmark(emptyList(), "plex:1", 90_000)
        all = addBookmark(all, "plex:1", 30_000)
        all = addBookmark(all, "plex:1", 31_000)
        assertEquals(listOf(30_000L, 90_000L), all.single().positionsMs)
    }

    @Test fun recentTitleMovesToTheFrontAndOldOnesFallOff() {
        var all = (0 until PlaybackBookmarks.MAX_TITLES).fold(emptyList<TitleBookmarks>()) { acc, i -> addBookmark(acc, "t$i", 10_000) }
        all = addBookmark(all, "new", 5_000)
        assertEquals("new", all.first().key)
        assertEquals(PlaybackBookmarks.MAX_TITLES, all.size)
        assertTrue(all.none { it.key == "t0" })
    }

    @Test fun removingTheLastMarkDropsTheTitle() {
        val all = addBookmark(addBookmark(emptyList(), "a", 1_000), "b", 2_000)
        assertEquals(listOf("a"), removeBookmark(all, "b", 2_000).map { it.key })
    }
}
