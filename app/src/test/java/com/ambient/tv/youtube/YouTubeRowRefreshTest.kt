package com.ambient.tv.youtube

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeRowRefreshTest {
    private fun item(id: Int) = YouTubeItem("video-$id", "Fresh $id")
    private fun row(id: String, size: Int) = YouTubeRow(id, (0 until size).map(::item))

    @Test fun refreshRestoresLoadedExtentWithFreshPagesAndStableItemKeys() = runBlocking {
        val old = listOf(row("Uploads", 60).copy(items = (0 until 60).map { item(it).copy(title = "Old $it") }))
        var next = 20
        var calls = 0
        val result = refreshYouTubeRowExtents(listOf(row("Uploads", 20)), old) { id ->
            assertEquals("Uploads", id)
            calls++
            YouTubeRowPage((next - 1 until next + 20).map(::item), hasMore = true).also { next += 20 }
        }

        assertEquals(2, calls)
        assertEquals((0 until 60).map { "video-$it" }, result.single().items.map { it.videoId })
        assertTrue(result.single().items.all { it.title.startsWith("Fresh") })
        assertEquals("Old 40", old.single().items[40].title)
    }

    @Test fun onlyPreviouslyExpandedRowsContinueAndDeletedRowsStayDeleted() = runBlocking {
        val fresh = listOf(row("Existing", 4), row("New", 2))
        val result = refreshYouTubeRowExtents(fresh, listOf(row("Existing", 4), row("Deleted", 70))) {
            error("No row needs continuation")
        }
        assertEquals(listOf("Existing", "New"), result.map { it.id })
        assertEquals(listOf(4, 2), result.map { it.items.size })
    }

    @Test fun normalNavigationDoesNotPreloadContinuationPages() = runBlocking {
        val fresh = listOf(row("Uploads", 20))
        assertSame(fresh, refreshYouTubeRowExtents(fresh, emptyList()) { error("Unexpected continuation") })
    }

    @Test fun actualEndCanReplaceAnOlderLongerRow() = runBlocking {
        var calls = 0
        val result = refreshYouTubeRowExtents(listOf(row("Uploads", 20)), listOf(row("Uploads", 60))) {
            calls++
            YouTubeRowPage((20 until 27).map(::item), hasMore = false)
        }
        assertEquals(1, calls)
        assertEquals(27, result.single().items.size)
    }

    @Test fun rowAndContinuationSizeAreBoundedByExistingLimit() = runBlocking {
        var calls = 0
        val result = refreshYouTubeRowExtents(listOf(row("Uploads", 20)), listOf(row("Uploads", 300))) {
            calls++
            YouTubeRowPage((20 until 300).map(::item), hasMore = true)
        }
        assertEquals(1, calls)
        assertEquals(YouTubeRowPaging.MAX_ITEMS_PER_ROW, result.single().items.size)
    }

    @Test fun duplicateOnlyLoopFailsWithoutReplacingVisibleSnapshot() {
        val old = listOf(row("Uploads", 60))
        var calls = 0
        assertThrows(IOException::class.java) {
            runBlocking {
                refreshYouTubeRowExtents(listOf(row("Uploads", 20)), old) {
                    calls++
                    YouTubeRowPage((0 until 20).map(::item), hasMore = true)
                }
            }
        }
        assertEquals(YouTubeRowPaging.MAX_EMPTY_PAGES, calls)
        assertEquals(60, old.single().items.size)
    }

    @Test fun progressResetsEmptyPageGuardButTotalRequestsRemainBounded() {
        var calls = 0
        assertThrows(IOException::class.java) {
            runBlocking {
                refreshYouTubeRowExtents(listOf(row("Uploads", 1)), listOf(row("Uploads", 120))) {
                    calls++
                    val items = if (calls % 2 == 0) listOf(item(calls)) else emptyList()
                    YouTubeRowPage(items, hasMore = true)
                }
            }
        }
        assertEquals(YouTubeRowPaging.MAX_ITEMS_PER_ROW, calls)
    }

    @Test fun cancellationPropagatesAndDoesNotPublishAPartialReplacement() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var replaced = false
        val job = async {
            refreshYouTubeRowExtents(listOf(row("Uploads", 20)), listOf(row("Uploads", 60))) {
                entered.complete(Unit)
                awaitCancellation()
            }
            replaced = true
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(replaced)
    }

    @Test fun refreshUsesAtMostTwoParallelRowRequests() = runBlocking {
        val entered = Channel<String>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        val job = async {
            refreshYouTubeRowExtents(
                listOf(row("A", 1), row("B", 1), row("C", 1)),
                listOf(row("A", 2), row("B", 2), row("C", 2)),
            ) { id ->
                active++
                peak = maxOf(peak, active)
                entered.send(id)
                release.await()
                active--
                YouTubeRowPage(listOf(item(1)), hasMore = false)
            }
        }
        repeat(YouTubeRowPaging.MAX_INITIAL_PARALLEL) { entered.receive() }
        yield()
        assertEquals(YouTubeRowPaging.MAX_INITIAL_PARALLEL, peak)
        assertTrue(entered.tryReceive().isFailure)
        release.complete(Unit)
        assertEquals(listOf(2, 2, 2), job.await().map { it.items.size })
        assertEquals(YouTubeRowPaging.MAX_INITIAL_PARALLEL, peak)
    }
}
