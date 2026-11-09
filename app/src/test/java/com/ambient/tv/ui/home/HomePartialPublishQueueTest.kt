package com.ambient.tv.ui.home

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class HomePartialPublishQueueTest {
    @Test fun simultaneousRepliesArePublishedOnlyOnce() = runBlocking {
        var published = 0
        val queue = HomePartialPublishQueue(this, minIntervalMs = 60_000L) { published++ }
        repeat(200) { queue.request() }
        yield()
        assertEquals(1, published)

        repeat(200) { queue.request() }
        yield()
        assertEquals(1, published)
        queue.stop()
        assertEquals(1, published)
    }

    @Test fun cachedUpdatesAreConflatedDuringNavigationAndPublishedOnIdle() = runBlocking {
        var navigating = true
        var published = 0
        val queue = HomePartialPublishQueue(this, minIntervalMs = 0L, deferWhile = { navigating }) {
            published++
        }
        repeat(80) { queue.request() }
        delay(80)
        assertEquals("No background snapshot should rebuild the rows during D-pad travel", 0, published)
        navigating = false
        withTimeout(2_000) {
            while (published == 0) delay(8)
        }
        assertEquals("Conflated provider updates should publish one latest snapshot", 1, published)
        queue.stop()
    }

    @Test fun separateRepliesCanPublishAfterCooldown() = runBlocking {
        var published = 0
        val queue = HomePartialPublishQueue(this, minIntervalMs = 0L) { published++ }
        queue.request()
        yield()
        assertEquals(1, published)
        queue.request()
        yield()
        assertEquals(2, published)
        queue.stop()
    }
}
