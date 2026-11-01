package com.ambient.tv.ui.telegram

import com.ambient.tv.data.telegram.TelegramMedia
import com.ambient.tv.data.telegram.TelegramMediaKind
import org.junit.Assert.*
import org.junit.Test

class TelegramRowsTest {
    private val entries = listOf(
        TelegramMedia(1, 10, "Channel A", "First", TelegramMediaKind.VIDEO),
        TelegramMedia(1, 11, "Channel A", "Link", TelegramMediaKind.YOUTUBE, youtubeVideoId = "dQw4w9WgXcQ"),
        TelegramMedia(2, 12, "Channel B", "Podcast", TelegramMediaKind.AUDIO),
        TelegramMedia(1, 10, "Channel A", "First", TelegramMediaKind.VIDEO),
    )

    @Test fun groupByChannelWithStableRowsAndNoDuplicates() {
        val rows = telegramRows("recent", entries)
        assertEquals(listOf("telegram.recent.1", "telegram.recent.2"), rows.map { it.id })
        assertEquals(listOf("telegram:1:10", "telegram:1:11"), rows.first().items.map { it.ratingKey })
        assertEquals(listOf("telegram:2:12"), rows.last().items.map { it.ratingKey })
    }

    @Test fun filteringYoutubeDoesNotBorrowOtherChannelVideoQueues() {
        val rows = telegramRows("youtube", entries)
        assertEquals(1, rows.size)
        assertEquals(listOf("telegram:1:11"), rows.single().items.map { it.ratingKey })
    }

    @Test fun audioAndVideoFiltersKeepOnlyTheirOwnTypes() {
        assertEquals(listOf("telegram:2:12"), telegramRows("audio", entries).flatMap { it.items }.map { it.ratingKey })
        assertEquals(listOf("telegram:1:10"), telegramRows("videos", entries).flatMap { it.items }.map { it.ratingKey })
        assertTrue(telegramRows("photos", entries).isEmpty())
    }
}
