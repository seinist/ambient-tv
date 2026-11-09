package com.ambient.tv.ui.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class DeferredBrowsePublisherTest {
    @Test fun backgroundRowsConflateWhileNavigatingAndPublishTheNewestOnIdle() = runBlocking {
        var moving = true
        val published = mutableListOf<Int>()
        val queue = DeferredBrowsePublisher(this, deferWhile = { moving }) { published += it }

        (1..60).forEach(queue::submit)
        delay(90L)
        assertEquals(emptyList<Int>(), published)
        moving = false
        withTimeout(2_000L) {
            while (published.isEmpty()) delay(8L)
        }
        assertEquals(listOf(60), published)
        queue.cancelPending()
    }

    @Test fun immediateFirstResultInvalidatesOlderDeferredSnapshots() = runBlocking {
        var moving = true
        val published = mutableListOf<String>()
        val queue = DeferredBrowsePublisher(this, deferWhile = { moving }) { published += it }

        queue.submit("obsolete background response")
        moving = false
        queue.submit("first usable result")
        delay(80L)
        assertEquals(listOf("first usable result"), published)
        queue.cancelPending()
    }

    @Test fun navigationAwayCancelsAnyPendingPublication() = runBlocking {
        var moving = true
        val published = mutableListOf<Int>()
        val queue = DeferredBrowsePublisher(this, deferWhile = { moving }) { published += it }

        queue.submit(1)
        queue.cancelPending()
        moving = false
        delay(80L)
        assertEquals(emptyList<Int>(), published)
    }
}
