package com.ambient.tv.ui.youtube

import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeLocalProgress
import com.ambient.tv.youtube.YouTubeRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeDisplaySnapshotTest {
    private fun row(id: String, vararg items: YouTubeItem) = YouTubeRow(id, items.toList(), id = id)
    private fun video(id: String, title: String = id, percent: Int = 0) =
        YouTubeItem(id, title, percentWatched = percent)

    @Test fun focusAndViewportLookupsDoNotRescanTheFeed() {
        val rows = ReadCountingList((0 until 20).map { row ->
            YouTubeRow("Row $row", (0 until 100).map { video("$row:$it") }, id = "row-$row")
        })
        val cache = YouTubeDisplaySnapshotCache()
        val initial = cache.get(rows, YouTubeSort.DEFAULT, YouTubeFilter.ALL, false)
        val readsAfterBuild = rows.reads
        assertTrue(readsAfterBuild > 0)
        repeat(200) { hop ->
            val displayed = cache.get(rows, YouTubeSort.DEFAULT, YouTubeFilter.ALL, false)
            assertSame(initial, displayed)
            assertEquals(hop % 100, displayed.itemIndex("row-19", "19:${hop % 100}"))
            assertEquals(100, displayed.row("row-19")?.items?.size)
            assertEquals(100, displayed.sourceSize("row-19"))
        }
        assertEquals("Each focus hop must reuse the index", readsAfterBuild, rows.reads)
    }

    @Test fun replacementsAndOptionsRefreshOrderAndSourcePositions() {
        val cache = YouTubeDisplaySnapshotCache()
        val rows = listOf(row("one", video("z", "Zulu"), video("a", "Alpha")))
        val original = cache.get(rows, YouTubeSort.DEFAULT, YouTubeFilter.ALL, false)
        val sorted = cache.get(rows, YouTubeSort.TITLE, YouTubeFilter.ALL, false)
        assertEquals(listOf("a", "z"), sorted.rows.single().items.map { it.videoId })
        assertEquals(1, sorted.itemIndex("one", "z"))
        val reversed = cache.get(rows, YouTubeSort.TITLE, YouTubeFilter.ALL, true)
        assertEquals(listOf("z", "a"), reversed.rows.single().items.map { it.videoId })
        val replacement = listOf(row("one", video("b", "Beta"), video("a", "Alpha")))
        val refreshed = cache.get(replacement, YouTubeSort.TITLE, YouTubeFilter.ALL, true)
        assertNotSame(original, refreshed)
        assertEquals(-1, refreshed.itemIndex("one", "z"))
        assertEquals(0, refreshed.itemIndex("one", "b"))
        val appended = cache.get(replacement + row("two", video("new")), YouTubeSort.TITLE, YouTubeFilter.ALL, true)
        assertNull(appended.row(null))
        assertEquals(0, appended.itemIndex("two", "new"))
    }

    @Test fun filtersKeepRawSourceExtentsAndRowIdentityForPaging() {
        val rows = listOf(
            row("one", video("hidden", percent = 100), video("first"), video("first")),
            row("two", video("first"), video("second")),
        )
        val displayed = YouTubeDisplaySnapshotCache().get(rows, YouTubeSort.DEFAULT, YouTubeFilter.UNWATCHED, false)
        assertEquals(3, displayed.sourceSize("one"))
        assertEquals(2, displayed.row("one")?.items?.size)
        assertEquals(0, displayed.itemIndex("one", "first"))
        assertEquals(0, displayed.itemIndex("two", "first"))
        assertEquals(1, displayed.itemIndex("two", "second"))
        assertEquals(-1, displayed.itemIndex("one", "hidden"))
    }

    @Test fun localPlaybackAndCompletionInvalidateUnchangedSourceRows() {
        val id = "display-cache-local-completion"
        val rows = listOf(row("row", video(id)))
        val cache = YouTubeDisplaySnapshotCache()
        assertEquals(listOf(id), cache.get(rows, YouTubeSort.DEFAULT, YouTubeFilter.UNWATCHED, false)
            .rows.single().items.map { it.videoId })
        YouTubeLocalProgress.record(id, 40_000, 100_000)
        assertTrue(cache.get(rows, YouTubeSort.DEFAULT, YouTubeFilter.UNWATCHED, false).rows.isEmpty())
        assertEquals(listOf(id), cache.get(rows, YouTubeSort.DEFAULT, YouTubeFilter.IN_PROGRESS, false)
            .rows.single().items.map { it.videoId })
        YouTubeLocalProgress.record(id, 100_000, 100_000, completed = true)
        assertTrue(cache.get(rows, YouTubeSort.DEFAULT, YouTubeFilter.IN_PROGRESS, false).rows.isEmpty())
    }

    @Test fun remoteResetCachedProgressAndRemovalInvalidateWatchFilters() {
        val id = "display-cache-remote-reset"
        val rows = listOf(row("row", video(id)))
        val cache = YouTubeDisplaySnapshotCache()
        assertFalse(cache.get(rows, YouTubeSort.DEFAULT, YouTubeFilter.UNWATCHED, false).rows.isEmpty())
        YouTubeLocalProgress.observeCached(id, 50)
        assertTrue(cache.get(rows, YouTubeSort.DEFAULT, YouTubeFilter.UNWATCHED, false).rows.isEmpty())
        YouTubeLocalProgress.observeRemote(id, 0)
        assertFalse(cache.get(rows, YouTubeSort.DEFAULT, YouTubeFilter.UNWATCHED, false).rows.isEmpty())
        YouTubeLocalProgress.record(id, 50_000, 100_000)
        assertTrue(cache.get(rows, YouTubeSort.DEFAULT, YouTubeFilter.UNWATCHED, false).rows.isEmpty())
        YouTubeLocalProgress.discardLive(id)
        assertFalse(cache.get(rows, YouTubeSort.DEFAULT, YouTubeFilter.UNWATCHED, false).rows.isEmpty())
    }

    @Test fun exactMillisecondsAndAcknowledgementsDoNotRebuildUnchangedFilters() {
        val id = "display-cache-exact-position"
        val rows = listOf(row("row", video(id)))
        YouTubeLocalProgress.observeRemote(id, 0)
        YouTubeLocalProgress.record(id, 40_010, 100_000)
        val cache = YouTubeDisplaySnapshotCache()
        val displayed = cache.get(rows, YouTubeSort.DEFAULT, YouTubeFilter.IN_PROGRESS, false)
        val revision = YouTubeLocalProgress.revision
        YouTubeLocalProgress.record(id, 40_020, 100_000)
        YouTubeLocalProgress.observeRemote(id, 40)
        assertEquals(40_020L, YouTubeLocalProgress.resumePositionMs(id, 100_000))
        assertEquals(revision, YouTubeLocalProgress.revision)
        assertSame(displayed, cache.get(rows, YouTubeSort.DEFAULT, YouTubeFilter.IN_PROGRESS, false))
    }

    @Test fun nonWatchFiltersIgnoreProgressButStillTrackRowChanges() {
        val id = "display-cache-all-progress"
        val rows = listOf(row("row", video(id)))
        val cache = YouTubeDisplaySnapshotCache()
        val all = cache.get(rows, YouTubeSort.DEFAULT, YouTubeFilter.ALL, false)
        YouTubeLocalProgress.record(id, 100_000, 100_000, completed = true)
        assertSame(all, cache.get(rows, YouTubeSort.DEFAULT, YouTubeFilter.ALL, false))
        assertNotSame(all, cache.get(rows.toMutableList(), YouTubeSort.DEFAULT, YouTubeFilter.ALL, false))
    }

    private class ReadCountingList<T>(private val values: List<T>) : AbstractList<T>() {
        var reads = 0
        override val size: Int get() = values.size
        override fun get(index: Int): T { reads++; return values[index] }
    }
}
