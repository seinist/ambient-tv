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

    @Test fun failedRangeDoesNotRelabelPreviousChunkOnRetry() {
        val count = TELEGRAM_MEDIA_PART_BYTES
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val source = TelegramSeekableMedia(
            media(count.toLong() + 1),
            { offset ->
                if (offset == 0L) ByteArray(count) { 0x11 }
                else if (attempts.incrementAndGet() <= 2) throw IOException("temporary failure")
                else byteArrayOf(0x22)
            }, { true },
        )
        try {
            val dst = ByteArray(1)
            assertEquals(1, source.read(0, dst, 1))
            assertEquals(0x11.toByte(), dst[0])
            assertTrue(runCatching { source.read(count.toLong(), dst, 1) }.exceptionOrNull() is IOException)
            assertEquals(1, source.read(count.toLong(), dst, 1))
            assertEquals(0x22.toByte(), dst[0])
        } finally { source.close() }
    }

    @Test fun shortInteriorPartIsRejected() {
        val source = TelegramSeekableMedia(media(TELEGRAM_MEDIA_PART_BYTES.toLong()), { byteArrayOf(1) }, { true })
        try {
            assertTrue(runCatching { source.read(0, ByteArray(1), 1) }.exceptionOrNull() is IOException)
        } finally { source.close() }
    }

    @Test fun closeInterruptsBlockedReadWithoutWaitingForReadMonitor() {
        val started = java.util.concurrent.CountDownLatch(1)
        val interrupted = java.util.concurrent.CountDownLatch(1)
        val source = TelegramSeekableMedia(media(3), {
            started.countDown()
            try {
                java.util.concurrent.CountDownLatch(1).await()
                byteArrayOf(1, 2, 3)
            } catch (failure: InterruptedException) {
                interrupted.countDown()
                throw failure
            }
        }, { true })
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val reading = workers.submit<Boolean> {
                runCatching { source.read(0, ByteArray(1), 1) }.isFailure
            }
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            workers.submit { source.close() }.get(2, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(interrupted.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(reading.get(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(runCatching { source.read(0, ByteArray(1), 1) }.isFailure)
        } finally { workers.shutdownNow(); source.close() }
    }

    @Test fun foregroundReadDoesNotWaitForSpeculativeAdmission() {
        val count = TELEGRAM_MEDIA_PART_BYTES
        val prefetchStarted = java.util.concurrent.CountDownLatch(1)
        val source = TelegramSeekableMedia(
            media(count.toLong() + 1),
            partLoader = { offset -> if (offset == 0L) ByteArray(count) else byteArrayOf(7) },
            authorized = { true },
            prefetchLoader = {
                prefetchStarted.countDown()
                java.util.concurrent.CountDownLatch(1).await()
                byteArrayOf(8)
            },
        )
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            assertEquals(1, source.read(0, ByteArray(1), 1))
            assertTrue(prefetchStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val result = worker.submit<Byte> { ByteArray(1).also { source.read(count.toLong(), it, 1) }[0] }
            assertEquals(7.toByte(), result.get(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { source.close(); worker.shutdownNow() }
    }

    @Test fun readAheadUsesBitrateAndWaitButResetsOnSeek() {
        val low = TelegramReadAhead(media(1024 * 1024L).copy(durationMs = 60_000), 3)
        val high = TelegramReadAhead(media(100 * 1024 * 1024L).copy(durationMs = 10_000), 3)
        assertEquals(1, low.observe(true, 5_000_000))
        assertEquals(3, high.observe(true, 150_000_000))
        assertEquals(1, high.observe(false, 500_000_000))
        val bounded = TelegramReadAhead(media(100 * 1024 * 1024L).copy(durationMs = 10_000), 2)
        assertEquals(2, bounded.observe(true, 150_000_000))
    }

    @Test fun priorityPromotionWorksBeforeAndAfterNativeBinding() {
        val first = TelegramTransferPriority(false)
        var promoted = 0
        first.promote()
        first.bind { promoted++ }
        assertEquals(1, promoted)
        first.promote()
        assertEquals(1, promoted)
        first.unbind()
        val second = TelegramTransferPriority(false)
        second.bind { promoted++ }
        assertEquals(1, promoted)
        second.promote()
        assertEquals(2, promoted)
        second.unbind()
    }

    @Test fun sequentialReadAheadUsesAdjacentWindowAndDoesNotRefetchItsSecondPart() {
        val count = TELEGRAM_MEDIA_PART_BYTES
        val calls = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val grouped = java.util.concurrent.CountDownLatch(1)
        val loader: (Long) -> ByteArray = { at ->
            calls.add(at)
            ByteArray(count) { (at / count).toByte() }
        }
        val source = TelegramSeekableMedia(
            media(100L * count).copy(durationMs = 10_000),
            partLoader = loader,
            authorized = { true },
            prefetchWindow = { at ->
                grouped.countDown()
                listOf(ByteArray(count) { (at / count).toByte() },
                    ByteArray(count) { (at / count + 1).toByte() })
            },
        )
        try {
            val out = ByteArray(1)
            assertEquals(1, source.read(0, out, 1))
            assertEquals(1, source.read(count.toLong(), out, 1))
            assertTrue(grouped.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, source.read(2L * count, out, 1))
            assertEquals(2.toByte(), out[0])
            assertEquals(1, source.read(3L * count, out, 1))
            assertEquals(3.toByte(), out[0])
            assertFalse(calls.contains(2L * count))
            assertFalse(calls.contains(3L * count))
        } finally { source.close() }
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
