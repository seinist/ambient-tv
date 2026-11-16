package com.ambient.tv.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedConnectRunCancellationTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun immediateRetryWaitsForCancelledRunCleanup() = runTest {
        val connect = SharedConnectRun(backgroundScope)
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = async {
            connect.joinOrStart("server") {
                order += "first-start"
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) {
                        cleanupEntered.complete(Unit)
                        releaseCleanup.await()
                        order += "first-reset"
                    }
                }
            }
        }
        runCurrent()
        connect.cancel()
        runCurrent()
        assertTrue(cleanupEntered.isCompleted)
        val retry = async { connect.joinOrStart("server") { order += "retry-start"; true } }
        runCurrent()
        assertFalse(retry.isCompleted)
        assertEquals(listOf("first-start"), order)
        releaseCleanup.complete(Unit)
        runCurrent()
        assertFalse(first.await())
        assertTrue(retry.await())
        assertEquals(listOf("first-start", "first-reset", "retry-start"), order)
    }

    @Test fun cancelledSharedWakeStopsUnderlyingRunNotJustItsCaller() = runBlocking {
        val connect = SharedConnectRun(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val caller = async {
            connect.joinOrStart("server") {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        started.await()
        connect.cancel()
        cancelled.await()
        assertFalse(caller.await())
    }
}
