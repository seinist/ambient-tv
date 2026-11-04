package com.ambient.tv.ui.home

import com.ambient.tv.data.repository.MediaSourceChangedException
import org.junit.Assert.*
import org.junit.Test

class HomeFailureTransientTest {
    @Test fun connectionSwitchAndCancelledCallsAreNotRowFailures() {
        assertTrue(homeFailureIsTransient(MediaSourceChangedException()))
        assertTrue(homeFailureIsTransient(java.io.IOException("Canceled")))
        assertTrue(homeFailureIsTransient(kotlinx.coroutines.CancellationException("x")))
        assertFalse(homeFailureIsTransient(java.net.SocketTimeoutException("timeout")))
        assertFalse(homeFailureIsTransient(java.io.IOException("unexpected end of stream")))
    }
}
