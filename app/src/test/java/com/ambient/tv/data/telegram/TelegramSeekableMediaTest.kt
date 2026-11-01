package com.ambient.tv.data.telegram

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TelegramSeekableMediaTest {
    private fun media(size: Long) = TelegramMedia(
        chatId = -100123L,
        messageId = 42,
        channel = "Test",
        title = "Sample",
        kind = TelegramMediaKind.VIDEO,
        fileSize = size,
    )

    @Test fun readsAcrossChunkBoundaryAndReusesBufferedPartOnSeek() {
        val count = TELEGRAM_MEDIA_PART_BYTES
        val expectedStart = ByteArray(count) { 0x11 }
        val expectedEnd = byteArrayOf(0x21, 0x22, 0x23, 0x24)
        val requests = mutableListOf<Long>()
        val source = TelegramSeekableMedia(
            media = media(count.toLong() + 4),
            partLoader = { offset ->
                requests += offset
                if (offset == 0L) expectedStart else expectedEnd
            },
            authorized = { true },
        )
        val dst = ByteArray(8)
        assertEquals(8, source.read(count.toLong() - 4, dst, 8))
        assertArrayEquals(
            byteArrayOf(0x11, 0x11, 0x11, 0x11, 0x21, 0x22, 0x23, 0x24), dst)
        assertEquals(listOf(0L, count.toLong()), requests)
        val again = ByteArray(2)
        assertEquals(2, source.read(count.toLong() + 1, again, 2))
        assertArrayEquals(byteArrayOf(0x22, 0x23), again)
        assertEquals(2, requests.size)
        assertEquals(-1, source.read(count.toLong() + 4, dst, 8))
        source.close()
    }

    @Test fun sessionRevocationStopsReadsEvenWhenPreviousChunkIsBuffered() {
        var authorized = true
        var fetchCount = 0
        val source = TelegramSeekableMedia(
            media = media(3),
            partLoader = { fetchCount++; byteArrayOf(1, 2, 3) },
            authorized = { authorized },
        )
        assertEquals(1, source.read(0, ByteArray(1), 1))
        authorized = false
        val failure = runCatching { source.read(1, ByteArray(1), 1) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(1, fetchCount)
        source.close()
    }

    @Test fun invalidAndProtectedMediaNeverStartLoopbackStream() {
        val protected = media(100).copy(protectedContent = true)
        assertTrue(runCatching {
            TelegramSeekableMedia(protected, { byteArrayOf(1) }, { true })
        }.isFailure)
        assertTrue(runCatching {
            TelegramSeekableMedia(media(0), { byteArrayOf(1) }, { true })
        }.isFailure)
    }
}
