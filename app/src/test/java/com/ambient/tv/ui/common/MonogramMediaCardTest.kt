package com.ambient.tv.ui.common

import com.ambient.tv.data.telegram.TelegramMedia
import com.ambient.tv.data.telegram.TelegramMediaKind
import com.ambient.tv.data.telegram.toMediaItem
import com.ambient.tv.data.telegram.youtubePosts
import com.ambient.tv.ui.telegram.toBrowseItem
import com.ambient.tv.data.telegram.TelegramChannel
import org.junit.Assert.*
import org.junit.Test

class MonogramMediaCardTest {
    private val media = TelegramMedia(
        chatId = -1004567L,
        messageId = 37,
        channel = "Channel A",
        title = "Release",
        kind = TelegramMediaKind.VIDEO,
        publishedAtMs = 1_748_000_000_000,
        durationMs = 480_000L,
        fileSize = 5_000_000L,
    )

    @Test fun monogramOnlySkinDoesNotAffectOtherMediaSystems() {
        assertTrue(isMonogramMediaTile(media.toMediaItem()))
        assertTrue(isMonogramMediaTile(TelegramChannel(19L, "News").toBrowseItem()))
        val youtube = media.copy(summary = "https://youtu.be/dQw4w9WgXcQ").youtubePosts().first()
        assertTrue(isMonogramMediaTile(youtube.toMediaItem()))
        assertFalse(isMonogramMediaTile(media.toMediaItem().copy(ratingKey = "plex:movie:37")))
    }

    @Test fun cardMetaUsesPublishedDateChannelAndDurationWithoutInventingValues() {
        assertEquals(
            "Channel A  ·  Date  ·  8min",
            monogramCardMeta(media.toMediaItem(), { "Date" }, { "8min" }),
        )
        assertEquals(
            "Channel A",
            monogramCardMeta(media.copy(publishedAtMs = 0, durationMs = 0).toMediaItem(),
                { "Date" }, { "8min" }),
        )
    }

    @Test fun channelAndYoutubeCardUseStableMetadataAndArt() {
        val channel = TelegramChannel(19L, "News").toBrowseItem()
        assertEquals("", monogramCardMeta(channel, { "Date" }, { "8min" }))
        val youtube = media.copy(summary = "https://youtu.be/dQw4w9WgXcQ").youtubePosts().first()
        assertEquals("Channel A  ·  Date  ·  8min",
            monogramCardMeta(youtube.toMediaItem(), { "Date" }, { "8min" }))
        assertTrue(youtube.previewUrl?.contains("i.ytimg.com/vi/") == true)
    }
}
