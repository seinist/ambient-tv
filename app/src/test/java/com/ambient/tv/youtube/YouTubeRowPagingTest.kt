package com.ambient.tv.youtube

import com.ambient.tv.youtube.YouTubeRowPaging.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class YouTubeRowPagingTest {
    private var now = 0L
    private fun paging(session: String? = "feed:home") = YouTubeRowPaging { now }.also { it.reset(session) }
    private fun item(id: String) = YouTubeItem(id, id)
    private fun row(id: String, count: Int, prefix: String = id) = YouTubeRow(id, (0 until count).map { item("$prefix-$it") })

    @Test
    fun appendDeduplicatesByVideoIdAndKeepsOrder() {
        val rows = listOf(row("A", 3), row("B", 2))
        val result = appendYouTubeRowItems(rows, "A", listOf(item("A-2"), item("x"), item("x"), item("y")))

        assertEquals(listOf("A-0", "A-1", "A-2", "x", "y"), result[0].items.map { it.videoId })
        assertSame(rows[1], result[1])
    }

    @Test
    fun appendWithOnlyDuplicatesReturnsSameList() {
        val rows = listOf(row("A", 3))
        assertSame(rows, appendYouTubeRowItems(rows, "A", listOf(item("A-0"))))
        assertSame(rows, appendYouTubeRowItems(rows, "missing", listOf(item("z"))))
    }

    @Test
    fun appendIsBoundedPerRow() {
        val result = appendYouTubeRowItems(listOf(row("A", 118)), "A", (0 until 10).map { item("n$it") })
        assertEquals(YouTubeRowPaging.MAX_ITEMS_PER_ROW, result.single().items.size)
    }

    @Test
    fun focusLoadsOnlyNearTheRowEnd() {
        val p = paging()
        assertFalse(p.shouldLoadOnFocus("A", 0, 20, grid = false))
        assertFalse(p.shouldLoadOnFocus("A", 15, 20, grid = false))
        assertTrue(p.shouldLoadOnFocus("A", 16, 20, grid = false))
        assertTrue(p.shouldLoadOnFocus("A", 19, 20, grid = false))
        assertTrue(p.shouldLoadOnFocus("A", 10, 20, grid = true))
        assertFalse(p.shouldLoadOnFocus("A", -1, 20, grid = false))
    }

    @Test
    fun onlyOneRequestPerRowRuns() {
        val p = paging()
        assertTrue(p.begin("A", 5))
        assertFalse(p.begin("A", 5))
        assertFalse(p.shouldLoadOnFocus("A", 4, 5, grid = false))
        assertTrue(p.begin("B", 5))
    }

    @Test
    fun nullOrFinalPageEndsTheRow() {
        val p = paging()
        p.begin("A", 5); p.finish("A", p.token, null, 5)
        assertEquals(Status.ENDED, p.status("A"))
        p.begin("B", 5); p.finish("B", p.token, YouTubeRowPage(listOf(item("n")), hasMore = false), 6)
        assertEquals(Status.ENDED, p.status("B"))
        p.begin("C", 5); p.finish("C", p.token, YouTubeRowPage(listOf(item("n")), hasMore = true), 6)
        assertEquals(Status.IDLE, p.status("C"))
        assertTrue(p.canLoad("C", 6))
    }

    @Test
    fun fullRowEndsEvenWithMorePages() {
        val p = paging()
        p.begin("A", 100)
        p.finish("A", p.token, YouTubeRowPage(listOf(item("n")), hasMore = true), YouTubeRowPaging.MAX_ITEMS_PER_ROW)
        assertEquals(Status.ENDED, p.status("A"))
        assertFalse(paging().canLoad("B", YouTubeRowPaging.MAX_ITEMS_PER_ROW))
    }

    @Test
    fun failureBlocksTheRowForThirtySeconds() {
        val p = paging()
        p.begin("A", 5)
        p.fail("A", p.token)
        now += YouTubeRowPaging.RETRY_AFTER_MS - 1
        assertFalse(p.canLoad("A", 5))
        now += 1
        assertTrue(p.canLoad("A", 5))
    }

    @Test
    fun resetDiscardsStateAndIgnoresStaleResults() {
        val p = paging()
        p.begin("A", 5)
        val stale = p.token
        p.reset("feed:home")
        assertFalse(p.finish("A", stale, YouTubeRowPage(emptyList(), hasMore = false), 5))
        p.fail("A", stale)
        assertEquals(Status.IDLE, p.status("A"))
        p.reset(null)
        assertFalse(p.canLoad("A", 5))
    }

    @Test
    fun cancelledRequestLeavesRowLoadable() {
        val p = paging()
        p.begin("A", 5)
        p.abort("A", p.token)
        assertTrue(p.canLoad("A", 5))
    }

    @Test
    fun initialContinuationOnlyBelowMinimum() {
        val p = paging()
        val rows = listOf(row("small", 4), row("full", YouTubeRowPaging.INITIAL_MIN_ITEMS), YouTubeRow("empty", emptyList()), row("tiny", 1))
        assertEquals(listOf("small", "tiny"), p.initialCandidates(rows))
        assertTrue(paging(null).initialCandidates(rows).isEmpty())
    }

    /** Fake SmartTube group chain: page keys until the last page, then null like continueGroup. */
    @Test
    fun continuationChainFillsRowUntilEnd() {
        val pages = ArrayDeque(listOf(
            YouTubeRowPage(listOf(item("r-4"), item("r-5"), item("r-6")), hasMore = true),
            YouTubeRowPage(listOf(item("r-6"), item("r-7")), hasMore = false),
        ))
        val p = paging()
        var rows = listOf(row("r", 4, prefix = "r"))
        var requests = 0
        while (true) {
            val current = rows.single().items
            if (!p.shouldLoadOnFocus("r", current.lastIndex, current.size, grid = false) || !p.begin("r", current.size)) break
            requests++
            val page = pages.removeFirstOrNull()
            rows = page?.let { appendYouTubeRowItems(rows, "r", it.items) } ?: rows
            p.finish("r", p.token, page, rows.single().items.size)
        }
        assertEquals(2, requests)
        assertEquals((0..7).map { "r-$it" }, rows.single().items.map { it.videoId })
        assertEquals(Status.ENDED, p.status("r"))
    }

    @Test
    fun cacheAppendIsAtomicAndRespectsGeneration() {
        val cache = YouTubeContentCache()
        cache.replace("feed:home", listOf(row("A", 2), row("B", 1)))
        val generation = cache.revision()

        val rows = cache.appendItems("feed:home", "A", listOf(item("A-1"), item("n")), generation)!!
        assertEquals(listOf("A-0", "A-1", "n"), rows.first().items.map { it.videoId })
        assertEquals(rows, cache.get("feed:home"))

        cache.clear()
        assertNull(cache.appendItems("feed:home", "A", listOf(item("z")), generation))
    }

    @Test
    fun rowEndPlaceholdersUseTheSharedDampedPulse() {
        val source = listOf(File("src/main/java/com/ambient/tv/ui/youtube/YouTubeScreen.kt"), File("app/src/main/java/com/ambient/tv/ui/youtube/YouTubeScreen.kt"))
            .first { it.exists() }.readText()
        val placeholder = source.substringAfter("private fun RowEndPlaceholder").substringBefore("private fun Card(")
        assertTrue(placeholder.contains("SharedArtworkPulse"))
        assertTrue(placeholder.contains("skeletonPulseAlpha"))
        assertTrue(placeholder.contains("reducedEffects"))
    }
}
