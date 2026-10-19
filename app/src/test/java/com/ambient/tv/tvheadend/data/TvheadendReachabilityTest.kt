package com.ambient.tv.tvheadend.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TvheadendReachabilityTest {
    @Test fun onlyAnUnreachableServerMarksTvheadendOffline() {
        assertTrue(tvhFallbackMarksOffline(null))
        assertTrue(tvhFallbackMarksOffline(java.net.ConnectException()))
        assertTrue(tvhFallbackMarksOffline(java.net.SocketTimeoutException()))
        assertTrue(tvhFallbackMarksOffline(java.net.UnknownHostException()))
        assertTrue(tvhFallbackMarksOffline(TvheadendWakeTimeoutException("wake")))
        assertTrue(tvhFallbackMarksOffline(java.io.IOException("x", java.net.NoRouteToHostException())))
        assertFalse(tvhFallbackMarksOffline(TvheadendHttpException(500, "dvr grid")))
        assertFalse(tvhFallbackMarksOffline(TvheadendHttpException(503, "busy")))
        assertFalse(tvhFallbackMarksOffline(kotlinx.serialization.SerializationException("bad json")))
    }

    @Test fun concurrentProbesShareOneCheckForAShortTime() = runBlocking {
        var now = 0L
        var probes = 0
        val probe = SharedReachabilityProbe(2_000L) { now }
        val results = (1..5).map { async { probe.check("host") { probes++; delay(10); true } } }.awaitAll()
        assertEquals(List(5) { true }, results)
        assertEquals(1, probes)
        now = 1_999L
        probe.check("host") { probes++; false }
        assertEquals(1, probes)
        probe.check("other") { probes++; false }
        assertEquals(2, probes)
        now = 5_000L
        assertFalse(probe.check("other") { probes++; false })
        assertEquals(3, probes)
        probe.invalidate()
        probe.check("other") { probes++; true }
        assertEquals(4, probes)
    }
}
