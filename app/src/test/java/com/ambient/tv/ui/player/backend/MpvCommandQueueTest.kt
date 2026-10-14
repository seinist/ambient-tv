package com.ambient.tv.ui.player.backend

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MpvCommandQueueTest {
    @Test fun newestSettingWinsAndReplacedSurfaceIsReleased() {
        val scheduled = ArrayDeque<() -> Unit>()
        val executed = mutableListOf<String>()
        var discarded = 0
        val queue = MpvCommandQueue({ scheduled.addLast(it) }, { throw AssertionError(it) })
        queue.submit("filter", discard = { discarded++ }) { executed += "old" }
        queue.submit("audio") { executed += "audio" }
        queue.submit("filter") { executed += "latest" }
        while (scheduled.isNotEmpty()) scheduled.removeFirst()()
        assertEquals(listOf("audio", "latest"), executed)
        assertEquals(1, discarded)
    }

    @Test fun closeReturnsWhileNativeOperationIsBlockedAndCleanupWaitsForIt() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        var obsoleteRan = false
        var discarded = 0
        val queue = MpvCommandQueue({ executor.execute(it) }, { throw AssertionError(it) })
        try {
            queue.submit("native") { entered.countDown(); check(unblock.await(5, TimeUnit.SECONDS)) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            queue.submit("obsolete", discard = { discarded++ }) { obsoleteRan = true }
            queue.close { cleaned.countDown() }
            queue.close { fail("cleanup twice") }
            assertEquals(1L, cleaned.count) // no concurrent handle destruction
            assertEquals(1, discarded)
            assertFalse(queue.submit("after-close") { fail("accepted after close") })
            unblock.countDown()
            assertTrue(cleaned.await(2, TimeUnit.SECONDS))
            assertFalse(obsoleteRan)
        } finally { unblock.countDown(); executor.shutdownNow() }
    }

    @Test fun backlogIsBoundedButExistingSettingCanStillBeReplaced() {
        val scheduled = ArrayDeque<() -> Unit>()
        val queue = MpvCommandQueue({ scheduled.addLast(it) }, { throw AssertionError(it) }, capacity = 2)
        var discarded = 0
        assertTrue(queue.submit("a") {})
        assertTrue(queue.submit("b") {})
        assertFalse(queue.submit("c", discard = { discarded++ }) {})
        assertTrue(queue.submit("a") {})
        queue.close {}
        while (scheduled.isNotEmpty()) scheduled.removeFirst()()
        assertEquals(1, discarded)
    }

    @Test fun failedOperationDoesNotStrandCleanup() {
        val scheduled = ArrayDeque<() -> Unit>()
        val errors = mutableListOf<Throwable>()
        val queue = MpvCommandQueue({ scheduled.addLast(it) }, errors::add)
        var cleaned = false
        queue.submit("failure") { error("native rejected operation") }
        scheduled.removeFirst()()
        queue.close { cleaned = true }
        while (scheduled.isNotEmpty()) scheduled.removeFirst()()
        assertEquals(1, errors.size)
        assertTrue(cleaned)
    }

    @Test fun cleanupDoesNotScheduleOnTheTerminatedOwner() {
        val scheduled = ArrayDeque<() -> Unit>()
        var ownerStopped = false
        val queue = MpvCommandQueue({
            check(!ownerStopped) { "Posted on a terminated owner" }
            scheduled.addLast(it)
        }, { throw AssertionError(it) })
        queue.close { ownerStopped = true }
        scheduled.removeFirst()()
        assertTrue(ownerStopped)
        assertTrue(scheduled.isEmpty())
    }
}
