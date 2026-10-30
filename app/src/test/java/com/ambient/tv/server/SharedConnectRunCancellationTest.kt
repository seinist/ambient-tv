package com.ambient.tv.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test

class SharedConnectRunCancellationTest {
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
