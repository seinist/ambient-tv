package com.ambient.tv.wol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeBurstCoalescerTest {

    @Test
    fun `recent requests for one NIC share one burst`() = runBlocking {
        var now = 1_000L
        var dispatchCount = 0
        val coalescer = WakeBurstCoalescer { now }

        val first = coalescer.run("aabbccddeeff", 60_000L) {
            dispatchCount++
            Result.success(Unit)
        }
        now += 10_000L
        val second = coalescer.run("aabbccddeeff", 60_000L) {
            dispatchCount++
            Result.success(Unit)
        }

        assertEquals(WakeBurstOutcome.SENT, first.getOrThrow())
        assertEquals(WakeBurstOutcome.COALESCED, second.getOrThrow())
        assertEquals(1, dispatchCount)
    }

    @Test
    fun `in-flight requests for one NIC await and share the first result`() = runBlocking {
        val enteredDispatch = CompletableDeferred<Unit>()
        val finishDispatch = CompletableDeferred<Unit>()
        var dispatchCount = 0
        val coalescer = WakeBurstCoalescer { 1_000L }

        val first = async {
            coalescer.run("aabbccddeeff", 60_000L) {
                dispatchCount++
                enteredDispatch.complete(Unit)
                finishDispatch.await()
                Result.success(Unit)
            }
        }
        enteredDispatch.await()
        val second = async {
            coalescer.run("aabbccddeeff", 60_000L) {
                dispatchCount++
                Result.success(Unit)
            }
        }
        finishDispatch.complete(Unit)

        assertEquals(WakeBurstOutcome.SENT, first.await().getOrThrow())
        assertEquals(WakeBurstOutcome.COALESCED, second.await().getOrThrow())
        assertEquals(1, dispatchCount)
    }

    @Test
    fun `a new attempt after the short overlap window sends a fresh packet`() = runBlocking {
        var now = 1_000L
        var sent = 0
        val coalescer = WakeBurstCoalescer { now }
        val send = suspend { sent++; Result.success(Unit) }
        assertEquals(WakeBurstOutcome.SENT, coalescer.run("aabbccddeeff", 5_000L, send).getOrThrow())
        now += 1_000L
        assertEquals(WakeBurstOutcome.COALESCED, coalescer.run("aabbccddeeff", 5_000L, send).getOrThrow())
        now += 4_000L
        assertEquals(WakeBurstOutcome.SENT, coalescer.run("aabbccddeeff", 5_000L, send).getOrThrow())
        assertEquals(2, sent)
    }

    @Test
    fun `different NICs and expired requests dispatch independently`() = runBlocking {
        var now = 1_000L
        var dispatchCount = 0
        val coalescer = WakeBurstCoalescer { now }
        val dispatch = suspend {
            dispatchCount++
            Result.success(Unit)
        }

        assertEquals(WakeBurstOutcome.SENT, coalescer.run("aabbccddeeff", 60_000L, dispatch).getOrThrow())
        assertEquals(WakeBurstOutcome.SENT, coalescer.run("112233445566", 60_000L, dispatch).getOrThrow())
        now += 60_000L
        assertEquals(WakeBurstOutcome.SENT, coalescer.run("aabbccddeeff", 60_000L, dispatch).getOrThrow())
        assertEquals(3, dispatchCount)
    }

    @Test
    fun `failed burst can be retried immediately`() = runBlocking {
        var dispatchCount = 0
        val coalescer = WakeBurstCoalescer { 1_000L }

        val failed = coalescer.run("aabbccddeeff", 60_000L) {
            dispatchCount++
            Result.failure(IllegalStateException("network unavailable"))
        }
        val retried = coalescer.run("aabbccddeeff", 60_000L) {
            dispatchCount++
            Result.success(Unit)
        }

        assertTrue(failed.isFailure)
        assertEquals(WakeBurstOutcome.SENT, retried.getOrThrow())
        assertEquals(2, dispatchCount)
    }
    @Test
    fun `same canonical NIC key coalesces regardless of requesting system`() = runBlocking {
        var dispatchCount = 0
        val coalescer = WakeBurstCoalescer { 1_000L }

        val plex = coalescer.run("aabbccddeeff", 60_000L) {
            dispatchCount++
            Result.success(Unit)
        }
        val jellyfin = coalescer.run("aabbccddeeff", 60_000L) {
            dispatchCount++
            Result.success(Unit)
        }
        val tvheadend = coalescer.run("aabbccddeeff", 60_000L) {
            dispatchCount++
            Result.success(Unit)
        }

        assertEquals(WakeBurstOutcome.SENT, plex.getOrThrow())
        assertEquals(WakeBurstOutcome.COALESCED, jellyfin.getOrThrow())
        assertEquals(WakeBurstOutcome.COALESCED, tvheadend.getOrThrow())
        assertEquals(1, dispatchCount)
    }

}
