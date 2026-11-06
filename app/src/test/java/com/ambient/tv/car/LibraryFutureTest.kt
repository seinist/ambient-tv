package com.ambient.tv.car

import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class LibraryFutureTest {
    @Test fun cancellingTheCallerCancelsItsSuspendingWork() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var finished = false
        val future = scope.libraryFuture<Unit> {
            try { awaitCancellation() } finally { finished = true }
        }
        assertFalse(future.isDone)
        future.cancel(false)
        assertTrue(finished)
        assertTrue(future.isCancelled)
        scope.cancel()
    }

    @Test fun aDestroyedServiceCompletesPendingAndNotYetStartedRequests() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pending = scope.libraryFuture<Unit> { awaitCancellation() }
        scope.cancel()
        assertTrue(pending.isCancelled)
        var entered = false
        val later = scope.libraryFuture { entered = true }
        assertTrue(later.isCancelled)
        assertFalse(entered)
    }

    @Test fun failedRequestDoesNotCancelOtherRequests() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val failure = IllegalArgumentException("Unavailable media ID")
        try {
            scope.libraryFuture<Unit> { throw failure }.get()
            fail("Future must expose its failure")
        } catch (error: ExecutionException) {
            assertSame(failure, error.cause)
        }
        assertEquals("next", scope.libraryFuture { "next" }.get())
        scope.cancel()
    }
}
