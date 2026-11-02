package com.ambient.tv.data.telegram

import org.junit.Assert.*
import org.junit.Test

class MonogramRecentTimelineTest {
    private fun media(chat: Long, id: Int, time: Long): TelegramMedia = TelegramMedia(
        chatId = chat, messageId = id, channel = "Channel $chat", title = "$id",
        kind = TelegramMediaKind.VIDEO, publishedAtMs = time,
    )

    @Test fun aRepostInAnotherChannelAppearsOnceTheNewest() {
        val original = media(1, 10, 1000).copy(fileSize = 4242, durationMs = 9000)
        val repost = media(2, 20, 5000).copy(fileSize = 4242, durationMs = 9000)
        val other = media(2, 19, 3000).copy(fileSize = 77, durationMs = 9000)
        val timeline = MonogramRecentTimeline(
            listOf(MonogramRecentTimeline.Channel(1, 1000), MonogramRecentTimeline.Channel(2, 5000)),
        ) { chat, _, _ ->
            MonogramRecentTimeline.HistoryBatch(if (chat == 1L) listOf(original) else listOf(repost, other), null, 0)
        }
        assertEquals(listOf("telegram:2:20", "telegram:2:19"), timeline.nextPage(10).media.map { it.mediaKey })
    }

    @Test fun mergesAllChannelMediaByDateNotChannelOrder() {
        val all = mapOf(
            1L to listOf(media(1, 11, 9000), media(1, 10, 3000)),
            2L to listOf(media(2, 21, 10000), media(2, 20, 2000)),
            3L to listOf(media(3, 31, 7000)),
        )
        val calls = mutableListOf<Long>()
        val timeline = MonogramRecentTimeline(
            listOf(MonogramRecentTimeline.Channel(1, 9000),
                MonogramRecentTimeline.Channel(2, 10000),
                MonogramRecentTimeline.Channel(3, 7000)),
        ) { chat, offset, _ ->
            calls += chat
            val messages = all.getValue(chat).filter { offset == 0 || it.messageId < offset }
            MonogramRecentTimeline.HistoryBatch(messages, null, 0)
        }
        val first = timeline.nextPage(2)
        assertEquals(listOf("telegram:2:21", "telegram:1:11"), first.media.map { it.mediaKey })
        assertTrue(first.hasMore)
        val second = timeline.nextPage(3)
        assertEquals(listOf("telegram:3:31", "telegram:1:10", "telegram:2:20"),
            second.media.map { it.mediaKey })
        assertFalse(second.hasMore)
        assertEquals(3, calls.distinct().size)
    }

    @Test fun defersOldChannelsUntilTheyCouldChangeTheNextResult() {
        val calls = mutableListOf<Long>()
        val timeline = MonogramRecentTimeline(
            listOf(MonogramRecentTimeline.Channel(1, 20000),
                MonogramRecentTimeline.Channel(2, 1000)),
        ) { chat, _, _ ->
            calls += chat
            MonogramRecentTimeline.HistoryBatch(
                if (chat == 1L) listOf(media(1, 100, 19000), media(1, 99, 18000))
                else listOf(media(2, 1, 1000)),
                null, 0)
        }
        val first = timeline.nextPage(1)
        assertEquals(listOf(1L), calls)
        assertEquals(19000L, first.media.single().publishedAtMs)
        assertTrue(first.hasMore)
        assertEquals(listOf(18000L, 1000L), timeline.nextPage(2).media.map { it.publishedAtMs })
    }

    @Test fun emptyTextPagesAreContinuedWithoutDiscardingRemainingChannels() {
        val timeline = MonogramRecentTimeline(listOf(MonogramRecentTimeline.Channel(1, 5000))) {
                _, offset, _ ->
            when (offset) {
                0 -> MonogramRecentTimeline.HistoryBatch(emptyList(), 42, 4000)
                42 -> MonogramRecentTimeline.HistoryBatch(listOf(media(1, 10, 3000)), null, 3000)
                else -> error("bad offset")
            }
        }
        val empty = timeline.nextPage(1, requestBudget = 1)
        assertTrue(empty.media.isEmpty())
        assertTrue(empty.hasMore)
        assertEquals(listOf(3000L), timeline.nextPage(1).media.map { it.publishedAtMs })
        assertFalse(timeline.nextPage(1).hasMore)
    }

    @Test fun catalogIsPagedOnlyWhileUnloadedChannelsCouldBeNewer() {
        var catalogPages = 0
        val timeline = MonogramRecentTimeline(
            listOf(MonogramRecentTimeline.Channel(1, 10_000)),
            unloadedBoundMs = 5_000,
            moreChannels = {
                catalogPages++
                MonogramRecentTimeline.CatalogPage(listOf(MonogramRecentTimeline.Channel(2, 5_000)), null)
            },
        ) { chat, _, _ ->
            MonogramRecentTimeline.HistoryBatch(
                if (chat == 1L) listOf(media(1, 10, 9_000), media(1, 9, 8_000)) else listOf(media(2, 5, 5_000)),
                null, 0)
        }
        // Channel 1's items are newer than anything unloaded: no catalog request yet.
        assertEquals(listOf(9_000L, 8_000L), timeline.nextPage(2).media.map { it.publishedAtMs })
        assertEquals(0, catalogPages)
        // Now an unloaded channel could be next: one catalog page, then its media.
        assertEquals(listOf(5_000L), timeline.nextPage(5).media.map { it.publishedAtMs })
        assertEquals(1, catalogPages)
    }

    @Test fun aCatalogPageWithoutChannelsDoesNotEndPagingUnlessNothingMoves() {
        val bounds = ArrayDeque(listOf(3_000L, 3_000L))
        var calls = 0
        val timeline = MonogramRecentTimeline(
            emptyList(), unloadedBoundMs = 4_000,
            moreChannels = { calls++; MonogramRecentTimeline.CatalogPage(emptyList(), bounds.removeFirst()) },
        ) { _, _, _ -> MonogramRecentTimeline.HistoryBatch(emptyList(), null, 0) }
        val page = timeline.nextPage(5)
        assertTrue(page.media.isEmpty())
        // 4000 -> 3000 moved the bound; 3000 -> 3000 changed nothing and ended paging.
        assertEquals(2, calls)
        assertFalse(page.hasMore)
    }
}
