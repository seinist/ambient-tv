package com.ambient.tv.ui.telegram

import com.ambient.tv.data.telegram.TelegramMedia
import com.ambient.tv.data.telegram.TelegramMediaKind
import com.ambient.tv.data.telegram.TelegramMediaPage
import com.ambient.tv.data.telegram.toMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TelegramBrowseReturnPreparationTest {
    private fun video(message: Int, chat: Long = 1L) = TelegramMedia(
        chat, message, "Channel $chat", "Post $message", TelegramMediaKind.VIDEO,
        publishedAtMs = 10_000L - message,
    )

    @Test fun firstReturnStateAlreadyContainsOffscreenNextTargetAndContinuation() {
        val source = (1..160).map { video(it) }
        val page = (161..180).map { video(it, chat = 2) }
        val context = TelegramPlaybackContext({ _, _ -> error("return must not fetch") }, { 0L }, 100L)
        val token = context.start("telegram:videos", source.map(TelegramMedia::toMediaItem), source[0].mediaKey,
            media = source.associateBy(TelegramMedia::mediaKey), section = "videos", cursor = "next",
            sourceRows = telegramRows("videos", source))!!
        context.appendPage(token, "next", TelegramMediaPage(page, "more"))
        context.played(token, page.last().mediaKey)
        val pending = checkNotNull(context.pendingReturn.value)
        val prepared = checkNotNull(prepareTelegramBrowseReturn("videos", 0L, pending))

        assertEquals(180, prepared.rows.sumOf { it.items.size })
        assertEquals(page.last(), prepared.media[prepared.playbackReturnKey])
        assertEquals("more", prepared.nextCursor)
        assertFalse(prepared.loading)
        assertFalse(prepared.loadingMore)
        assertNull(prepared.error)
        assertNull(prepared.pageError)
        val visible = visibleMonogramRows(prepared.rows, "", null, MonogramBrowseSort.DATE, true)
        val flat = flatMonogramItems(visible, MonogramBrowseSort.DATE, true)
        val index = MonogramBrowseFocusIndex(visible, flat)
        assertEquals(179, index.flatKeys.indexOf(prepared.playbackReturnKey))
        assertEquals(1, visible.indexOfFirst { it.id == index.rowForItem(prepared.playbackReturnKey) })
        assertEquals(19, index.indexInRow("telegram.videos.2", checkNotNull(prepared.playbackReturnKey)))
        assertSame(pending, context.pendingReturn.value)
        assertEquals(prepared, prepareTelegramBrowseReturn("videos", 0L, pending))
    }

    @Test fun onlyMatchingPageAndAuthorizedSourceCanPrepareReturn() {
        val media = video(1)
        val context = TelegramPlaybackContext({ _, _ -> error("unexpected fetch") }, { 0L }, 100L) { 7L }
        val token = context.start("telegram:channel:1", listOf(media.toMediaItem()), media.mediaKey,
            sourceRows = telegramRows("channel:1", listOf(media)))!!
        context.played(token, media.mediaKey)
        val snapshot = checkNotNull(context.pendingReturn.value)
        assertNotNull(prepareTelegramBrowseReturn("channel:1", 7L, snapshot))
        assertNull(prepareTelegramBrowseReturn("recent", 7L, snapshot))
        assertNull(prepareTelegramBrowseReturn("channel:2", 7L, snapshot))
        assertNull(prepareTelegramBrowseReturn("channel:1", 8L, snapshot))
        assertNull(prepareTelegramBrowseReturn("channel:1", 7L, null))
    }

    @Test fun disabledSmartTubeLinksStayHiddenInPlayerLoadedPages() {
        val first = video(1)
        val linked = video(3).copy(kind = TelegramMediaKind.YOUTUBE, youtubeVideoId = "dQw4w9WgXcQ")
        val next = video(2)
        val context = TelegramPlaybackContext({ _, _ -> error("unexpected fetch") }, { 0L }, 100L)
        val token = context.start("telegram:recent", listOf(first.toMediaItem()), first.mediaKey,
            section = "recent", cursor = "next", sourceRows = telegramRows("recent", listOf(first)))!!
        context.appendPage(token, "next", TelegramMediaPage(listOf(next, linked), null))
        context.played(token, next.mediaKey)
        val snapshot = checkNotNull(context.pendingReturn.value)
        assertEquals(3, snapshot.rows.single().items.size)
        val prepared = checkNotNull(prepareTelegramBrowseReturn("recent", 0L, snapshot, includeYouTubeLinks = false))
        assertEquals(listOf(first.mediaKey, next.mediaKey), prepared.rows.single().items.map { it.ratingKey })
        assertEquals(next.mediaKey, prepared.playbackReturnKey)
    }
}
