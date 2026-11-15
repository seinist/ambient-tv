package com.ambient.tv.ui.player.backend

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MpvOwnerDispatcherTest {
    @Test fun initializationWaitsForReadinessAndMailboxOrderIsPreserved() {
        val ready = CompletableFuture<Unit>()
        val owner = ArrayDeque<() -> Unit>()
        val order = mutableListOf<String>()
        val dispatcher = MpvOwnerDispatcher(ready) { owner.addLast(it) }
        val queue = MpvCommandQueue(dispatcher::dispatch, { throw AssertionError(it) })
        queue.submit("initialize") { order += "initialize" }
        queue.submit("surface-attach") { order += "attach" }

        assertTrue(owner.isEmpty())
        ready.complete(Unit)
        while (owner.isNotEmpty()) owner.removeFirst()()

        assertEquals(listOf("initialize", "attach"), order)
    }

    @Test fun idleOwnerDetachesWhileUiThreadWaits() {
        val owner = Executors.newSingleThreadExecutor()
        val ui = Executors.newSingleThreadExecutor()
        val dispatcher = MpvOwnerDispatcher(CompletableFuture.completedFuture(Unit)) { owner.execute(it) }
        val queue = MpvCommandQueue(dispatcher::dispatch, { throw AssertionError(it) })
        val gate = MpvSurfaceDetachGate()
        try {
            gate.attaching()
            val detached = ui.submit<Boolean> {
                val ticket = gate.requestDetach()
                queue.submit("surface-detach", keepOnClose = true) { gate.detached(ticket) }
                // This is the SurfaceHolder callback: its event loop cannot run another
                // callback until await returns, so native dispatch must not go through it.
                gate.await(2_000)
            }

            assertTrue(detached.get(3, TimeUnit.SECONDS))
            assertFalse(gate.inUse)
        } finally {
            ui.shutdownNow()
            owner.shutdownNow()
        }
    }

    @Test fun detachQueuedBehindInFlightWorkDoesNotNeedUiDispatch() {
        val owner = Executors.newSingleThreadExecutor()
        val ui = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val dispatcher = MpvOwnerDispatcher(CompletableFuture.completedFuture(Unit)) { owner.execute(it) }
        val queue = MpvCommandQueue(dispatcher::dispatch, { throw AssertionError(it) })
        val gate = MpvSurfaceDetachGate()
        try {
            queue.submit("surface-attach") {
                entered.countDown()
                check(resume.await(3, TimeUnit.SECONDS))
                gate.attaching()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val detached = ui.submit<Boolean> {
                val ticket = gate.requestDetach()
                queue.submit("surface-detach", keepOnClose = true) { gate.detached(ticket) }
                resume.countDown()
                gate.await(2_000)
            }

            assertTrue(detached.get(3, TimeUnit.SECONDS))
            assertFalse(gate.inUse)
        } finally {
            resume.countDown()
            ui.shutdownNow()
            owner.shutdownNow()
        }
    }

    @Test fun closeBeforeReadinessDiscardsAttachAndKeepsDetachBeforeCleanup() {
        val ready = CompletableFuture<Unit>()
        val owner = ArrayDeque<() -> Unit>()
        val order = mutableListOf<String>()
        var releasedCopies = 0
        val dispatcher = MpvOwnerDispatcher(ready) { owner.addLast(it) }
        val queue = MpvCommandQueue(dispatcher::dispatch, { throw AssertionError(it) })
        queue.submit("initialize") { fail("Closed controller must not initialize") }
        queue.submit("surface-attach", discard = { releasedCopies++ }) { fail("Closed controller must not attach") }
        queue.submit("surface-detach", keepOnClose = true) { order += "detach" }
        queue.close { order += "cleanup" }

        assertTrue(owner.isEmpty())
        assertEquals(1, releasedCopies)
        ready.complete(Unit)
        while (owner.isNotEmpty()) owner.removeFirst()()

        assertEquals(listOf("detach", "cleanup"), order)
    }
}
