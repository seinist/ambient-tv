package com.ambient.tv.data.browse

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseLoadCoordinatorTest {
    @Test fun aDepartingViewDoesNotCancelOtherWaiters() = runBlocking {
        val scheduler = BrowseLoadScheduler()
        val started = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            scheduler.load("plex:sections", BrowseLoadPriority.HOME) {
                calls.incrementAndGet()
                started.complete(Unit)
                unblock.await()
                "done"
            }
        }
        withTimeout(2_000) { started.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            scheduler.load("plex:sections", BrowseLoadPriority.VISIBLE) {
                error("A duplicate provider request was started")
            }
        }
        first.cancelAndJoin()
        unblock.complete(Unit)
        assertEquals("done", withTimeout(2_000) { second.await() })
        assertEquals(1, calls.get())
        assertEquals(0, scheduler.inFlightCount)
    }

    @Test fun visibleWorkOvertakesQueuedSpeculation() = runBlocking {
        val gate = BrowseLoadGate(limit = 1)
        val active = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit({ BrowseLoadPriority.VISIBLE }) {
                active.complete(Unit)
                unblock.await()
            }
        }
        active.await()
        val background = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit({ BrowseLoadPriority.BACKGROUND }) { order += "background" }
        }
        val visible = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit({ BrowseLoadPriority.VISIBLE }) { order += "visible" }
        }
        unblock.complete(Unit)
        withTimeout(2_000) { first.await(); visible.await(); background.await() }
        assertEquals(listOf("visible", "background"), order)
    }

    @Test fun aVisibleConsumerPromotesIdleWaitingBackgroundWork() = runBlocking {
        var idle = false
        val scheduler = BrowseLoadScheduler(maxConcurrent = 1) { idle }
        val background = async(start = CoroutineStart.UNDISPATCHED) {
            scheduler.load("same-key", BrowseLoadPriority.BACKGROUND) { "promoted" }
        }
        val visible = async(start = CoroutineStart.UNDISPATCHED) {
            scheduler.load("same-key", BrowseLoadPriority.VISIBLE) { error("duplicate") }
        }
        assertEquals("promoted", withTimeout(2_000) { visible.await() })
        assertEquals("promoted", withTimeout(2_000) { background.await() })
        assertEquals(0, scheduler.inFlightCount)
    }

    @Test fun lastConsumerCancelsUnusedWork() = runBlocking {
        val scheduler = BrowseLoadScheduler()
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            scheduler.load("retired", BrowseLoadPriority.HOME) {
                started.complete(Unit)
                never.await()
            }
        }
        started.await()
        pending.cancelAndJoin()
        assertEquals(0, scheduler.inFlightCount)
        assertEquals("next", scheduler.load("retired", BrowseLoadPriority.VISIBLE) { "next" })
    }
}
