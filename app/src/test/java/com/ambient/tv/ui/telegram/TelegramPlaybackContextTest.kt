package com.ambient.tv.ui.telegram

import com.ambient.tv.data.telegram.TelegramFloodWaitException
import com.ambient.tv.data.telegram.TelegramMedia
import com.ambient.tv.data.telegram.TelegramMediaKind
import com.ambient.tv.data.telegram.TelegramMediaPage
import com.ambient.tv.data.telegram.toMediaItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramPlaybackContextTest {
    private fun video(message: Int, chat: Long = 1, published: Long = 10_000L - message,
                      kind: TelegramMediaKind = TelegramMediaKind.VIDEO, protected: Boolean = false) =
        TelegramMedia(chat, message, "Channel $chat", "Post $message", kind,
            publishedAtMs = published, protectedContent = protected,
            youtubeVideoId = if (kind == TelegramMediaKind.YOUTUBE) "dQw4w9WgXcQ" else null)

    private class FakePages(val pages: Map<String, TelegramMediaPage>) {
        val requests = mutableListOf<Pair<String, String>>()
        var failure: Exception? = null
        suspend fun load(section: String, cursor: String): TelegramMediaPage {
            requests += section to cursor
            failure?.let { throw it }
            return pages.getValue(cursor)
        }
    }

    private var now = 0L
    private fun context(pages: FakePages = FakePages(emptyMap()), firstToken: Long = 100L) =
        TelegramPlaybackContext(pages::load, { now }, firstToken)

    private fun keys(vararg messages: Int) = messages.map { "telegram:1:$it" }

    @Test fun queueKeepsShownOrderAndSkipsPhotosYouTubeAndProtectedPosts() {
        val shown = listOf(video(5), video(4, kind = TelegramMediaKind.PHOTO),
            video(3, kind = TelegramMediaKind.YOUTUBE), video(2, protected = true),
            video(1, kind = TelegramMediaKind.AUDIO))
        val ctx = context()
        val token = ctx.start("telegram:recent", shown.map(TelegramMedia::toMediaItem), "telegram:1:5",
            shown.associateBy(TelegramMedia::mediaKey))!!
        assertEquals("telegram:1:1", ctx.nextLoaded(token, "telegram:1:5")?.ratingKey)
        assertEquals("telegram:1:5", ctx.previousLoaded(token, "telegram:1:1")?.ratingKey)
        assertFalse(ctx.contains(token, "telegram:1:4"))
    }

    @Test fun anUnplayableSelectionHasNoQueue() {
        val photo = video(4, kind = TelegramMediaKind.PHOTO)
        assertNull(context().start("telegram:recent", listOf(photo.toMediaItem()), photo.mediaKey,
            mapOf(photo.mediaKey to photo)))
    }

    @Test fun noWrapAroundAtEitherEnd() = runTest {
        val shown = listOf(video(3), video(2)).map(TelegramMedia::toMediaItem)
        val ctx = context()
        val token = ctx.start("home:row", shown, "telegram:1:3")!!
        assertNull(ctx.previousLoaded(token, "telegram:1:3"))
        assertNull(ctx.nextLoaded(token, "telegram:1:2"))
        // Without a section and cursor the loaded posts are the whole queue: the real end.
        assertNull(ctx.next(token, "telegram:1:2"))
    }

    @Test fun nextLoadsFollowingPagesInThePagesOrderAndEndsWithTheLastPage() = runTest {
        val pages = FakePages(mapOf(
            "c1" to TelegramMediaPage(listOf(video(7, kind = TelegramMediaKind.PHOTO), video(6)), "c2"),
            "c2" to TelegramMediaPage(listOf(video(5)), null),
        ))
        val ctx = context(pages)
        val token = ctx.start("telegram:videos", listOf(video(9), video(8)).map(TelegramMedia::toMediaItem),
            "telegram:1:9", section = "videos", cursor = "c1")!!
        assertEquals("telegram:1:6", ctx.next(token, "telegram:1:8")?.ratingKey)
        assertEquals(listOf("videos" to "c1"), pages.requests)
        assertEquals("telegram:1:5", ctx.next(token, "telegram:1:6")?.ratingKey)
        assertNull(ctx.next(token, "telegram:1:5"))
        assertEquals(2, pages.requests.size)
    }

    @Test fun pagesWithoutPlayablePostsAreSkippedUpToThePageCap() = runTest {
        val pages = FakePages((1..10).associate { page ->
            "c$page" to TelegramMediaPage(listOf(video(100 - page, kind = TelegramMediaKind.PHOTO)), "c${page + 1}")
        })
        val ctx = context(pages)
        val token = ctx.start("telegram:recent", listOf(video(200).toMediaItem()), "telegram:1:200",
            section = "recent", cursor = "c1")!!
        assertNull(ctx.next(token, "telegram:1:200"))
        assertEquals(TelegramPlaybackContext.MAX_CONTINUATION_PAGES, pages.requests.size)
    }

    @Test fun lazyPagesFollowTheShownRowFilterAndSort() = runTest {
        val pages = FakePages(mapOf("c1" to TelegramMediaPage(listOf(
            video(5, chat = 2), video(4, chat = 1, published = 1L), video(3, chat = 1, published = 2L)), null)))
        val ctx = context(pages)
        val token = ctx.start("telegram:recent", listOf(video(9, chat = 1).toMediaItem()), "telegram:1:9",
            section = "recent", cursor = "c1",
            view = TelegramQueueView(sort = MonogramBrowseSort.DATE, descending = false, rowId = "telegram.recent.1"))!!
        assertEquals("telegram:1:4", ctx.next(token, "telegram:1:9")?.ratingKey)
        assertEquals("telegram:1:3", ctx.nextLoaded(token, "telegram:1:4")?.ratingKey)
        assertNull(ctx.nextLoaded(token, "telegram:1:3"))
        assertFalse(ctx.contains(token, "telegram:2:5"))
    }

    @Test fun floodWaitBlocksPageRequestsUntilItHasPassed() = runTest {
        val pages = FakePages(mapOf("c1" to TelegramMediaPage(listOf(video(1)), null)))
        pages.failure = TelegramFloodWaitException(30)
        val ctx = context(pages)
        val token = ctx.start("telegram:videos", listOf(video(2).toMediaItem()), "telegram:1:2",
            section = "videos", cursor = "c1")!!
        assertNull(ctx.next(token, "telegram:1:2"))
        pages.failure = null
        now = 29_000L
        assertNull(ctx.next(token, "telegram:1:2"))
        assertEquals(1, pages.requests.size)
        now = 31_000L
        // An error is not the end: the cursor was kept and the page is requested again.
        assertEquals("telegram:1:1", ctx.next(token, "telegram:1:2")?.ratingKey)
    }

    @Test fun queueIsCappedAtFiveHundredPosts() = runTest {
        val shown = (1..600).map { video(it).toMediaItem() }
        val pages = FakePages(emptyMap())
        val ctx = context(pages)
        val token = ctx.start("telegram:videos", shown, "telegram:1:1", section = "videos", cursor = "c1")!!
        assertTrue(ctx.contains(token, "telegram:1:500"))
        assertFalse(ctx.contains(token, "telegram:1:501"))
        assertNull(ctx.next(token, "telegram:1:500"))
        assertTrue(pages.requests.isEmpty())
    }

    @Test fun returnTargetIsTheLastPlayedPostOnceAndOnlyForItsPage() = runTest {
        val pages = FakePages(mapOf("c1" to TelegramMediaPage(listOf(video(1)), "c2")))
        val ctx = context(pages)
        assertNull(ctx.consumeReturn("telegram:videos"))
        val token = ctx.start("telegram:videos", keys(3, 2).map { video(it.substringAfterLast(':').toInt()).toMediaItem() },
            "telegram:1:3", section = "videos", cursor = "c1")!!
        ctx.played(token, "telegram:1:3")
        val next = ctx.next(token, "telegram:1:3")!!
        ctx.played(token, next.ratingKey)
        ctx.played(token, ctx.next(token, next.ratingKey)!!.ratingKey)
        assertNull(ctx.consumeReturn("telegram:recent"))
        val target = ctx.consumeReturn("telegram:videos")!!
        assertEquals("telegram:1:1", target.mediaKey)
        assertEquals(listOf("telegram:1:1"), target.loadedMedia.map(TelegramMedia::mediaKey))
        assertEquals("c1", target.startCursor)
        assertEquals("c2", target.nextCursor)
        assertNull(ctx.consumeReturn("telegram:videos"))
    }

    @Test fun aNewQueueReplacesTheOldOneAndOldTokensFindNothing() {
        val ctx = context()
        val items = listOf(video(2), video(1)).map(TelegramMedia::toMediaItem)
        val first = ctx.start("telegram:videos", items, "telegram:1:2")!!
        val second = ctx.start("telegram:audio", items, "telegram:1:2")!!
        assertFalse(ctx.contains(first, "telegram:1:2"))
        assertNull(ctx.nextLoaded(first, "telegram:1:2"))
        ctx.played(first, "telegram:1:2")
        assertNull(ctx.consumeReturn("telegram:videos"))
        assertTrue(ctx.contains(second, "telegram:1:2"))
    }

    @Test fun aRestartedAppNeverMatchesARestoredToken() {
        val items = listOf(video(2), video(1)).map(TelegramMedia::toMediaItem)
        val restored = context(firstToken = 100L).start("telegram:videos", items, "telegram:1:2")!!
        val fresh = context(firstToken = 5_000L)
        fresh.start("telegram:videos", items, "telegram:1:2")
        assertFalse(fresh.contains(restored, "telegram:1:2"))
        assertNull(fresh.nextLoaded(restored, "telegram:1:2"))
    }
}
