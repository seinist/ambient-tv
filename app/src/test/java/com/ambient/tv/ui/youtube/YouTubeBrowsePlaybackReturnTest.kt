package com.ambient.tv.ui.youtube

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.memoryPreferences
import com.ambient.tv.youtube.SmartTubeEngine
import com.ambient.tv.youtube.YouTubeContentCache
import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeRow
import com.ambient.tv.youtube.YouTubeRowPage
import com.ambient.tv.ui.common.ContentFocusTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Back or the end of the queue returns to the channel with focus on the last played video. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class YouTubeBrowsePlaybackReturnTest {
    private fun video(id: String) = YouTubeItem(videoId = id, title = id)

    private fun context(cache: YouTubeContentCache = YouTubeContentCache()): YouTubeBrowsePlaybackContext = YouTubeBrowsePlaybackContext(
        SmartTubeEngine(ApplicationProvider.getApplicationContext(), AppPreferences(memoryPreferences())),
        cache,
    )

    @Test fun returnAfterNextLandsOnALazilyLoadedChannelPage() {
        val playback = context()
        val row = YouTubeRow("Videos", listOf(video("a"), video("b")), id = "videos")
        val token = playback.start("channel:UC1", row, "b")!!
        playback.played(token, "b")
        // The player reached the end of the loaded page; the next page is appended to the queue.
        playback.appendContinuation(token, "videos", YouTubeRowPage(listOf(video("c"), video("d")), hasMore = true))
        assertEquals("c", playback.nextLoaded(token, "b")?.videoId)
        playback.played(token, "c")
        playback.played(token, "d")
        assertEquals("c", playback.previousLoaded(token, "d")?.videoId)

        // A different page must not claim the return address.
        assertNull(playback.consumeReturn("feed:home"))
        val target = playback.consumeReturn("channel:UC1")
        assertEquals("videos", target?.rowId)
        assertEquals("d", target?.videoId)
        // Claimed once: a later visit of the channel keeps the user's own focus.
        assertNull(playback.consumeReturn("channel:UC1"))
    }

    @Test fun continuationPagesKeepTheChannelsSortAndFilter() {
        val playback = context()
        val row = YouTubeRow("Videos", listOf(video("a")), id = "videos")
        val token = playback.start("channel:UC1", row, "a")!!
        playback.appendContinuation(token, "videos", YouTubeRowPage(listOf(
            YouTubeItem("live", "Live", isLive = true), video("a"), video("b"),
        ), hasMore = false))
        // Live tiles and duplicates are never queued; the real end stops instead of wrapping.
        assertEquals("b", playback.nextLoaded(token, "a")?.videoId)
        assertNull(playback.nextLoaded(token, "b"))
    }

    @Test fun aNewChannelSessionReplacesTheOldPlayersQueue() {
        val playback = context()
        val old = playback.start("feed:home", YouTubeRow("Home", listOf(video("h1")), id = "home"), "h1")!!
        playback.played(old, "h1")
        val channel = playback.start("channel:UC1", YouTubeRow("Videos", listOf(video("a")), id = "videos"), "a")!!
        playback.played(channel, "a")
        playback.played(old, "h1")
        assertNull(playback.consumeReturn("feed:home"))
        assertEquals("a", playback.consumeReturn("channel:UC1")?.videoId)
    }

    @Test fun nextAndPreviousUpdateThePreviewBeforeTheBrowsePageEnters() {
        val playback = context()
        val row = YouTubeRow("Videos", listOf(video("a"), video("b"), video("c")), id = "videos")
        val token = playback.start("channel:UC1", row, "b")!!
        val navigation = YouTubePlayerNavigation(playback, token, isTrailer = false)
        assertTrue(navigation.loaded(video("b")))
        val first = playback.pendingReturn.value!!
        assertTrue(navigation.requestNext("b", "c"))
        assertTrue(navigation.loaded(video("c")))
        assertEquals("c", playback.pendingReturn.value?.target?.videoId)
        assertTrue(navigation.requestPrevious("c", "b"))
        assertTrue(navigation.loaded(video("b")))
        val last = playback.pendingReturn.value!!
        assertEquals("b", last.target.videoId)
        assertTrue(last.revision > first.revision)
        assertNull(playback.consumeReturn("feed:home"))
        assertSame(last, playback.pendingReturn.value)
    }

    @Test fun lazyPagesAreReadySynchronouslyEvenWithoutACachedFeed() {
        val playback = context() // Its cache has no entry for this channel.
        val row = YouTubeRow("Videos", listOf(video("a"), video("b")), id = "videos")
        val token = playback.start("channel:UC1", row, "b")!!
        playback.played(token, "b")
        val beforePage = playback.pendingReturn.value!!
        playback.appendContinuation(token, "videos", YouTubeRowPage(listOf(video("b"), video("c"), video("d")), true))
        playback.played(token, "d")
        val pending = playback.pendingReturn.value!!
        val prepared = prepareYouTubeBrowseReturn("channel:UC1", pending.cacheRevision, pending,
            YouTubeSort.DEFAULT, YouTubeFilter.ALL, descending = false)!!
        assertEquals(listOf("a", "b", "c", "d"), prepared.rows.single().items.map { it.videoId })
        assertEquals(ContentFocusTarget("d", "videos"), prepared.target)
        assertEquals(3, prepared.rows.single().items.indexOfFirst { it.videoId == prepared.target.itemId })
        // Published snapshots remain immutable when a later page arrives.
        assertEquals(listOf("a", "b"), beforePage.rows.single().items.map { it.videoId })
    }

    @Test fun commitKeepsThePreviewUntilTheSameEpochAndRowsHaveBeenInstalledThenConsumesOnce() {
        val playback = context()
        val row = YouTubeRow("Videos", listOf(video("a")), id = "videos")
        val token = playback.start("channel:UC1", row, "a")!!
        playback.played(token, "a")
        val pending = playback.pendingReturn.value!!
        var installed: PreparedYouTubeBrowseReturn? = null
        assertTrue(playback.commitReturn("channel:UC1", pending.revision) { snapshot ->
            assertSame(snapshot, playback.pendingReturn.value)
            installed = prepareYouTubeBrowseReturn("channel:UC1", snapshot.cacheRevision, snapshot,
                YouTubeSort.DEFAULT, YouTubeFilter.ALL, descending = false)
        })
        assertEquals(pending.revision, installed?.revision)
        assertSame(pending.rows, installed?.rows)
        assertNull(playback.pendingReturn.value)
        assertFalse(playback.commitReturn("channel:UC1", pending.revision) { error("Already consumed") })
        assertNull(playback.consumeReturn("channel:UC1"))
        // A late page alone must not create a stale return on a later visit.
        playback.appendContinuation(token, "videos", YouTubeRowPage(listOf(video("b")), false))
        assertNull(playback.pendingReturn.value)
    }

    @Test fun playingTheSameVideoAgainPublishesANewReturnAndRejectsAnOlderCommit() {
        val playback = context()
        val row = YouTubeRow("Videos", listOf(video("a")), id = "videos")
        val token = playback.start("channel:UC1", row, "a")!!
        playback.played(token, "a")
        val old = playback.pendingReturn.value!!
        playback.played(token, "a")
        val fresh = playback.pendingReturn.value!!
        assertEquals(old.target, fresh.target)
        assertTrue(fresh.revision > old.revision)
        assertFalse(playback.commitReturn("channel:UC1", old.revision) { error("Stale return") })
        assertFalse(playback.commitReturn("feed:home", fresh.revision) { error("Wrong source") })
        assertSame(fresh, playback.pendingReturn.value)
        assertEquals("a", playback.consumeReturn("channel:UC1")?.videoId)
        playback.played(token, "a")
        assertTrue(playback.pendingReturn.value!!.revision > fresh.revision)
    }

    @Test fun replacingAQueueClearsItsPreviewAndInvalidatesOldCallbacksEvenInTheSameScope() {
        val playback = context()
        val row = YouTubeRow("Videos", listOf(video("a"), video("b")), id = "videos")
        val oldToken = playback.start("channel:UC1", row, "a")!!
        playback.played(oldToken, "a")
        val old = playback.pendingReturn.value!!
        val newToken = playback.start("channel:UC1", row, "b")!!
        assertNull(playback.pendingReturn.value)
        playback.played(newToken, "b")
        val fresh = playback.pendingReturn.value!!
        playback.played(oldToken, "a")
        assertFalse(playback.commitReturn("channel:UC1", old.revision) { error("Replaced session") })
        assertSame(fresh, playback.pendingReturn.value)
        assertEquals("b", fresh.target.videoId)
    }

    @Test fun aFlatQueueReturnsToTheActualRowOfTheLastPlayedVideo() {
        val playback = context()
        val first = YouTubeRow("First", listOf(video("a")), id = "first")
        val second = YouTubeRow("Second", listOf(video("b")), id = "second")
        val rows = listOf(first, second)
        val queue = youtubePlaybackSourceRow(rows, "a", "first", sectioned = false)!!
        val token = playback.start("feed:subscriptions", queue, "a",
            continuationRowIds = rows.map { it.id }, returnRowId = "first", sourceRows = rows)!!
        playback.played(token, "b")
        assertEquals("second", playback.pendingReturn.value?.target?.rowId)
        assertEquals(rows, playback.pendingReturn.value?.rows)
        assertEquals("second", playback.consumeReturn("feed:subscriptions")?.rowId)
    }

    @Test fun accountChangesInvalidateThePreviewAndRejectOldQueuePagesInTheNewCache() {
        val cache = YouTubeContentCache()
        val playback = context(cache)
        val old = YouTubeRow("Videos", listOf(video("old-account")), id = "videos")
        cache.merge("feed:subscriptions", listOf(old))
        val token = playback.start("feed:subscriptions", old, "old-account")!!
        playback.played(token, "old-account")

        cache.clear()
        val fresh = YouTubeRow("Videos", listOf(video("new-account")), id = "videos")
        cache.merge("feed:subscriptions", listOf(fresh))
        playback.appendContinuation(token, "videos", YouTubeRowPage(listOf(video("old-page")), false))

        assertNull(prepareYouTubeBrowseReturn("feed:subscriptions", cache.revision(), playback.pendingReturn.value,
            YouTubeSort.DEFAULT, YouTubeFilter.ALL, descending = false))
        assertEquals(listOf(fresh), cache.get("feed:subscriptions"))
    }

    @Test fun aBackgroundCacheRefreshCannotReorderThePreparedPlaybackVisit() {
        val cache = YouTubeContentCache()
        val playback = context(cache)
        val original = YouTubeRow("Videos", listOf(video("b"), video("a")), id = "videos")
        cache.merge("feed:subscriptions", listOf(original))
        val token = playback.start("feed:subscriptions", original, "a")!!
        playback.played(token, "a")
        cache.replace("feed:subscriptions", listOf(original.copy(items = listOf(video("new"), video("a"), video("b")))),
            preferIncomingOrder = true)
        playback.appendContinuation(token, "videos", YouTubeRowPage(listOf(video("c")), false))
        playback.played(token, "c")

        val prepared = prepareYouTubeBrowseReturn("feed:subscriptions", cache.revision(), playback.pendingReturn.value,
            YouTubeSort.DEFAULT, YouTubeFilter.ALL, descending = false)!!
        assertEquals(listOf("b", "a", "c"), prepared.rows.single().items.map { it.videoId })
        assertEquals(ContentFocusTarget("c", "videos"), prepared.target)
        assertEquals(listOf("new", "a", "b", "c"), cache.get("feed:subscriptions")!!.single().items.map { it.videoId })
    }
}
