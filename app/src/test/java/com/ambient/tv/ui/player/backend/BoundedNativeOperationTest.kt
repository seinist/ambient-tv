package com.ambient.tv.ui.player.backend

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Test

class BoundedNativeOperationTest {
    @Test fun resultAndExceptionArePropagated() {
        assertEquals(7, boundedNativeOperation(1000L) { 7 })
        val failure = IllegalStateException("driver")
        val caught = runCatching { boundedNativeOperation(1000L) { throw failure } }.exceptionOrNull()
        assertSame(failure, caught)
    }

    @Test fun timeoutDoesNotInterruptOrCancelNativeOwner() {
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val failure = runCatching {
                boundedNativeOperation(25L) {
                    try { release.await() }
                    catch (_: InterruptedException) { interrupted.set(true) }
                    finally { finished.countDown() }
                }
            }.exceptionOrNull()
            assertTrue(failure is TimeoutException)
            assertFalse(finished.await(25, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertFalse(interrupted.get())
        } finally { release.countDown() }
    }
}
