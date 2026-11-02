package com.ambient.tv.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonogramThumbModelTest {
    @Test fun modelRoundTripsWithInlineFallback() {
        val inline = "file:/data/cache/monogram/thumb-%2D100-42.bin"
        val model = monogramThumbModel(-1001234567890L, 42, inline)
        assertEquals(MonogramThumbRef(-1001234567890L, 42, inline), parseMonogramThumbModel(model))
        assertEquals(MonogramThumbRef(5L, 7, null), parseMonogramThumbModel(monogramThumbModel(5L, 7, null)))
    }

    @Test fun otherUrlsAreNotMonogramThumbs() {
        assertNull(parseMonogramThumbModel("https://example.test/a.jpg"))
        assertNull(parseMonogramThumbModel("monogram-thumb://0/1"))
        assertNull(parseMonogramThumbModel("monogram-thumb://5/0"))
        assertNull(parseMonogramThumbModel("monogram-thumb://5"))
    }

    @Test fun cardsUseTheRealThumbnailForVideosAndPhotos() {
        val video = TelegramMedia(chatId = 5, messageId = 7, channel = "K", title = "T",
            kind = TelegramMediaKind.VIDEO, thumbnailUrl = "file:/inline.bin")
        assertEquals(monogramThumbModel(5, 7, "file:/inline.bin"), video.cardImageUrl)
        assertEquals(video.cardImageUrl, video.toMediaItem().thumbUrl)
        val photo = video.copy(kind = TelegramMediaKind.PHOTO)
        assertEquals(monogramThumbModel(5, 7, "file:/inline.bin"), photo.cardImageUrl)
        val audioWithoutCover = video.copy(kind = TelegramMediaKind.AUDIO, thumbnailUrl = null)
        assertNull(audioWithoutCover.cardImageUrl)
    }
}
