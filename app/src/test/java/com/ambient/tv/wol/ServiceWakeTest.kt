package com.ambient.tv.wol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceWakeTest {
    private class Clock { var now = 0L; fun read(): Long { now += 1_000L; return now } }

    @Test
    fun `a machine answering before its service keeps waiting without more bursts`() = runBlocking {
        val sent = mutableListOf<Int>()
        val progress = mutableListOf<ServiceWakeProgress>()
        var polls = 0
        val clock = Clock()
        // The machine answers from the 2nd poll on (another port), the service only from the 30th.
        val ready = awaitServiceAfterWake(
            retries = 2, windowMs = 100_000L, pollMs = 1L,
            serviceReady = { ++polls >= 30 },
            machineAwake = { polls >= 2 },
            send = { sent += it },
            onProgress = { progress += it },
            nowMs = clock::read,
        )
        assertTrue(ready)
        assertEquals(listOf(0), sent)
        val waiting = progress.filterIsInstance<ServiceWakeProgress.Waiting>()
        assertFalse(waiting.first().machineAwake)
        assertTrue(waiting.last().machineAwake)
    }

    @Test
    fun `an open foreign port alone is no success`() = runBlocking {
        val clock = Clock()
        val ready = awaitServiceAfterWake(
            retries = 1, windowMs = 5_000L, pollMs = 1L,
            serviceReady = { false },
            machineAwake = { true },
            send = {},
            nowMs = clock::read,
        )
        assertFalse(ready)
    }

    @Test
    fun `a silent machine gets every burst of the budget`() = runBlocking {
        val sent = mutableListOf<Int>()
        val clock = Clock()
        val ready = awaitServiceAfterWake(2, 5_000L, 1L, { false }, { false }, { sent += it }, nowMs = clock::read)
        assertFalse(ready)
        assertEquals(listOf(0, 1, 2), sent)
    }

    @Test
    fun `an awake machine is only waited for without sending packets`() = runBlocking {
        val sent = mutableListOf<Int>()
        val progress = mutableListOf<ServiceWakeProgress>()
        var polls = 0
        val clock = Clock()
        val ready = awaitServiceAfterWake(
            retries = 2, windowMs = 20_000L, pollMs = 1L,
            serviceReady = { ++polls >= 5 },
            machineAwake = { true },
            send = { sent += it },
            onProgress = { progress += it },
            sendPackets = false,
            nowMs = clock::read,
        )
        assertTrue(ready)
        assertTrue(sent.isEmpty())
        assertTrue(progress.none { it is ServiceWakeProgress.Sending })
        assertTrue(progress.filterIsInstance<ServiceWakeProgress.Waiting>().all { it.machineAwake && it.packets == 0 })
    }
}
