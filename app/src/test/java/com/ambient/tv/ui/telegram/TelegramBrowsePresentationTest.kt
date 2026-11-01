package com.ambient.tv.ui.telegram

import com.ambient.tv.data.telegram.TelegramMedia
import com.ambient.tv.data.telegram.TelegramMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramBrowsePresentationTest {
    private val media = listOf(
        TelegramMedia(1L, 1, "A", "Show older", TelegramMediaKind.VIDEO, publishedAtMs = 10L),
        TelegramMedia(1L, 2, "A", "New show", TelegramMediaKind.VIDEO, publishedAtMs = 40L),
        TelegramMedia(2L, 3, "B", "New show", TelegramMediaKind.VIDEO, publishedAtMs = 30L),
    )
    private val rows = telegramRows("recent", media)

    @Test fun namesAndChannelIdsRemainStableWhileFiltering() {
        val filtered = visibleMonogramRows(rows, "SHOW", "telegram.recent.1", MonogramBrowseSort.DATE, true)
        assertEquals(listOf("telegram.recent.1"), filtered.map { it.id })
        assertEquals(listOf("telegram:1:2", "telegram:1:1"), filtered.single().items.map { it.ratingKey })
    }

    @Test fun globalGridItemsRespectDateOrderWithoutCollapsingSameTitles() {
        assertEquals(listOf("telegram:1:2", "telegram:2:3", "telegram:1:1"),
            flatMonogramItems(rows, MonogramBrowseSort.DATE, true).map { it.ratingKey })
        assertTrue(visibleMonogramRows(rows, "missing", null, MonogramBrowseSort.DATE, true).isEmpty())
    }
}
