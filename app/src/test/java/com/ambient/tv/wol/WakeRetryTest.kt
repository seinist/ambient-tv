package com.ambient.tv.wol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeRetryTest {

    /** Every clock read advances 1 s, so a 10 s window holds a handful of polls. */
    private class Clock { var now = 0L; fun read(): Long { now += 1_000L; return now } }

    @Test
    fun `server answering in the first window sends one burst`() = runBlocking {
        val sent = mutableListOf<Int>()
        var polls = 0
        val clock = Clock()
        val woke = wakeWithRetries(2, 10_000L, 1L, { sent += it }, { ++polls >= 2 }, nowMs = clock::read)
        assertTrue(woke)
        assertEquals(listOf(0), sent)
    }

    @Test
    fun `server answering after a retry stops further bursts`() = runBlocking {
        val sent = mutableListOf<Int>()
        val clock = Clock()
        val woke = wakeWithRetries(3, 10_000L, 1L, { sent += it }, { sent.size >= 2 }, nowMs = clock::read)
        assertTrue(woke)
        assertEquals(listOf(0, 1), sent)
    }

    @Test
    fun `silent server gets exactly retries plus one bursts and the loop ends`() = runBlocking {
        val sent = mutableListOf<Int>()
        val clock = Clock()
        val woke = wakeWithRetries(2, 10_000L, 1L, { sent += it }, { false }, nowMs = clock::read)
        assertFalse(woke)
        assertEquals(listOf(0, 1, 2), sent)
    }

    @Test
    fun `zero retries keeps the old single wake cycle`() = runBlocking {
        val sent = mutableListOf<Int>()
        val clock = Clock()
        assertFalse(wakeWithRetries(0, 10_000L, 1L, { sent += it }, { false }, nowMs = clock::read))
        assertEquals(listOf(0), sent)
    }

    @Test
    fun `out of range retry counts are clamped`() = runBlocking {
        val sent = mutableListOf<Int>()
        val clock = Clock()
        wakeWithRetries(Int.MAX_VALUE, 10_000L, 1L, { sent += it }, { false }, nowMs = clock::read)
        assertEquals(WOL_RETRY_COUNTS.max() + 1, sent.size)
        sent.clear()
        wakeWithRetries(-4, 10_000L, 1L, { sent += it }, { false }, nowMs = clock::read)
        assertEquals(1, sent.size)
    }

    @Test
    fun `elapsed seconds keep counting across retries`() = runBlocking {
        val elapsed = mutableListOf<Int>()
        val clock = Clock()
        wakeWithRetries(1, 5_000L, 1L, {}, { false }, onWaiting = { elapsed += it }, nowMs = clock::read)
        assertTrue(elapsed.isNotEmpty())
        assertEquals(elapsed.sorted(), elapsed)
        assertTrue(elapsed.last() > 5)
    }

    @Test
    fun `unavailable wake network aborts without polling or retries`() = runBlocking {
        var attempts = 0
        var polls = 0
        var waiting = 0
        val failure = IllegalStateException("Wake network unavailable")
        val result = runCatching {
            wakeWithRetries(3, 60_000L, 1L,
                send = { attempts++; throw failure },
                reachable = { polls++; false },
                onWaiting = { waiting++ })
        }
        assertEquals(failure, result.exceptionOrNull())
        assertEquals(1, attempts)
        assertEquals(0, polls)
        assertEquals(0, waiting)
    }

    @Test
    fun `stored values outside the offered choices fall back to the default`() {
        assertEquals(WOL_RETRY_COUNT_DEFAULT, normalizedWolRetryCount(4))
        assertEquals(WOL_RETRY_COUNT_DEFAULT, normalizedWolRetryCount(-1))
        assertEquals(5, normalizedWolRetryCount(5))
        assertEquals(0, normalizedWolRetryCount(0))
    }

    @Test
    fun `wake budget is one window per send`() {
        assertEquals(60_000L, wakeBudgetMs(0, 60_000L))
        assertEquals(180_000L, wakeBudgetMs(2, 60_000L))
        assertEquals(6 * 60_000L, wakeBudgetMs(99, 60_000L))
    }
}
