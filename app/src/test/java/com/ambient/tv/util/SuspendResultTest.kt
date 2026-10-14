package com.ambient.tv.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class SuspendResultTest {
    @Test fun normalFailuresRemainResults() = runBlocking {
        val error = IllegalStateException("offline")
        assertSame(error, suspendResult<Unit> { throw error }.exceptionOrNull())
        assertEquals(42, suspendResult { 42 }.getOrThrow())
    }

    @Test fun cancelledWorkCannotPublishAFallback() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var published = false
        val job = launch {
            suspendResult {
                started.complete(Unit)
                try { awaitCancellation() } catch (_: CancellationException) {
                    // Models a provider which wrongly swallows cancellation.
                    withContext(NonCancellable) { "late answer" }
                }
            }.onSuccess { published = true }
        }
        started.await()
        job.cancelAndJoin()
        assertFalse(published)
    }
}
