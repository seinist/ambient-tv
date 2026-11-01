package com.ambient.tv.data.telegram

import org.junit.Assert.*
import org.junit.Test

class MonogramRecentTimelineTest {
    private fun media(chat: Long, id: Int, time: Long): TelegramMedia = TelegramMedia(
        chatId = chat, messageId = id, channel = "Channel $chat", title = "$id",
        kind = TelegramMediaKind.VIDEO, publishedAtMs = time,
    )

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
}
