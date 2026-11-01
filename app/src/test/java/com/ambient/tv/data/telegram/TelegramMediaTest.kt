package com.ambient.tv.data.telegram

import org.junit.Assert.*
import org.junit.Test

class TelegramMediaTest {
    private val video = TelegramMedia(
        chatId = -1001234567890L,
        messageId = 42,
        channel = "Test Channel",
        title = "Demo",
        kind = TelegramMediaKind.VIDEO,
        durationMs = 90_000L,
        fileSize = 4_000_000L,
    )

    @Test fun mediaKeysAreStableAndRejectMalformedInput() {
        assertEquals("telegram:-1001234567890:42", video.mediaKey)
        assertEquals(-1001234567890L to 42, telegramMediaIds(video.mediaKey))
        assertNull(telegramMediaIds("telegram:not-a-chat:42"))
        assertNull(telegramMediaIds("telegram:1:0"))
        assertNull(telegramMediaIds("telegram:1:42:99"))
        assertNull(telegramMediaIds("radio:1:42"))
    }

    @Test fun telegramMetadataUsesSharedPlayerAndHomeFields() {
        val tile = video.toMediaItem()
        assertEquals(video.title, tile.title)
        assertEquals(video.channel, tile.parentTitle)
        assertEquals(video.mediaKey, tile.ratingKey)
        assertEquals(90_000L, tile.durationMs)
        val player = video.playerMetadata()
        assertEquals("telegram", player.source)
        assertEquals("Test Channel", player.subtitle)
        assertEquals(video.mediaKey, player.mediaId)
        assertEquals(video.durationMs, player.durationMs)
    }

    @Test fun protectedFilesAndYoutubeDoNotEnterTelegramByteStreaming() {
        assertFalse(video.copy(protectedContent = true).playableOnTelegram)
        assertFalse(video.copy(kind = TelegramMediaKind.YOUTUBE).playableOnTelegram)
        assertTrue(video.copy(kind = TelegramMediaKind.AUDIO).playableOnTelegram)
    }

    @Test fun seekOffsetsAlignToMonogramsFiveHundredTwelveKiBParts() {
        assertEquals(0L, telegramPartOffset(0))
        assertEquals(0L, telegramPartOffset(512L * 1024L - 1))
        assertEquals(512L * 1024L, telegramPartOffset(512L * 1024L))
        assertEquals(512L * 1024L, telegramPartOffset(512L * 1024L + 1))
    }

    @Test fun youtubeIdsComeFromKnownHostsAndMessageFormats() {
        val text = """
            https://youtu.be/dQw4w9WgXcQ
            https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10
            https://m.youtube.com/shorts/9bZkp7q19f0
            https://youtube.com/live/9bZkp7q19f0
            https://youtube.com.evil.example/watch?v=9bZkp7q19f0
        """.trimIndent()
        assertEquals(listOf("dQw4w9WgXcQ", "9bZkp7q19f0"), telegramYouTubeIds(text))
    }
    @Test fun youtubeTabExpandsEveryLinkedVideoEvenInAttachedMedia() {
        val combined = video.copy(
            summary = "Watch https://youtu.be/dQw4w9WgXcQ and https://youtube.com/shorts/9bZkp7q19f0",
            linkedYouTubeVideoIds = listOf("E7wJTI-1dvQ"),
        )
        val items = combined.youtubePosts()
        assertEquals(3, items.size)
        assertEquals(3, items.map { it.mediaKey }.distinct().size)
        assertTrue(items.all { it.kind == TelegramMediaKind.YOUTUBE })
        assertTrue(items.all { it.previewUrl?.startsWith("https://i.ytimg.com/vi/") == true })
        assertEquals(video.chatId to video.messageId, telegramPostIds(items[1].mediaKey))
        assertEquals(video.chatId to video.messageId, telegramPostIds(video.mediaKey))
        assertNull(telegramPostIds("telegram-youtube:1:2:invalid"))
    }

    @Test fun youtubeLinksMustBeFromRealYoutubeHost() {
        val text = """
            https://maliciousyoutube.com/watch?v=dQw4w9WgXcQ
            https://evil-youtube.com/watch?v=dQw4w9WgXcQ
            https://evil.youtube.com/watch?v=dQw4w9WgXcQ
            https://youtube.com/watch?v=9bZkp7q19f0
        """.trimIndent()
        assertEquals(listOf("9bZkp7q19f0"), telegramYouTubeIds(text))
    }

}
