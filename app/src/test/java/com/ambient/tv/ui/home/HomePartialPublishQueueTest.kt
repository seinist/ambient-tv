package com.ambient.tv.ui.home

import kotlinx.coroutines.runBlocking
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
