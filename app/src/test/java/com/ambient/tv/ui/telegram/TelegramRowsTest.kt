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
        assertEquals(listOf("telegram:1:10", "telegram-youtube:1:11:dQw4w9WgXcQ"), rows.first().items.map { it.ratingKey })
        assertEquals(listOf("telegram:2:12"), rows.last().items.map { it.ratingKey })
    }

    @Test fun filteringYoutubeDoesNotBorrowOtherChannelVideoQueues() {
        val rows = telegramRows("youtube", entries)
        assertEquals(1, rows.size)
        assertEquals(listOf("telegram-youtube:1:11:dQw4w9WgXcQ"), rows.single().items.map { it.ratingKey })
    }

    @Test fun audioAndVideoFiltersKeepOnlyTheirOwnTypes() {
        assertEquals(listOf("telegram:2:12"), telegramRows("audio", entries).flatMap { it.items }.map { it.ratingKey })
        assertEquals(listOf("telegram:1:10"), telegramRows("videos", entries).flatMap { it.items }.map { it.ratingKey })
        assertTrue(telegramRows("photos", entries).isEmpty())
    }

    @Test fun withoutSmartTubeYouTubeLinkCardsAreLeftOut() {
        val rows = telegramRows("recent", entries, includeYouTubeLinks = false)
        assertEquals(listOf("telegram:1:10", "telegram:2:12"), rows.flatMap { it.items }.map { it.ratingKey })
    }

    @Test fun prefetchUsesOnlyPublishedNativeCards() {
        val recent = telegramRows("recent", entries)
        assertEquals(listOf(1L to 10), monogramPrefetchTargets(recent).map { it.chatId to it.messageId })
        assertTrue(monogramPrefetchTargets(telegramRows("youtube", entries)).isEmpty())
        assertTrue(monogramPrefetchTargets(recent, limit = 0).isEmpty())
        assertEquals(1, monogramPrefetchTargets(recent + recent).size)
    }

    @Test fun prefetchIncludesChannelAvatarAndHonorsLimit() {
        val channel = com.ambient.tv.data.telegram.TelegramChannel(3, "Channel C").toBrowseItem()
        val rows = listOf(com.ambient.tv.ui.common.ContentRowData(
            id = "channels", title = "Channels",
            style = com.ambient.tv.ui.common.MediaTileStyle.LANDSCAPE, items = listOf(channel),
        )) + telegramRows("recent", entries)
        assertEquals(listOf(3L to 0), monogramPrefetchTargets(rows, 1).map { it.chatId to it.messageId })
        assertEquals(listOf(3L to 0, 1L to 10), monogramPrefetchTargets(rows).map { it.chatId to it.messageId })
    }

    @Test fun viewportPrioritizesVisibleThenAheadAndBehind() {
        assertEquals(listOf(4, 5, 6, 7, 3, 2),
            monogramViewportItems((0..9).toList(), 4, 5, ahead = 2, behind = 2))
    }

    @Test fun viewportIgnoresPlaceholdersAndClampsEdges() {
        assertEquals(listOf(8, 9, 7),
            monogramViewportItems((0..9).toList(), 8, 14, ahead = 8, behind = 1))
        assertTrue(monogramViewportItems((0..9).toList(), 10, 11, 8, 2).isEmpty())
        assertTrue(monogramViewportItems(emptyList<Int>(), 0, 0, 8, 2).isEmpty())
    }
}
