package com.ambient.tv.vpn

import com.ambient.tv.vpn.WgTunnelTimers.Companion.INITIATE
import com.ambient.tv.vpn.WgTunnelTimers.Companion.KEEPALIVE
import com.ambient.tv.vpn.WgTunnelTimers.Companion.TIMED_OUT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WgTunnelTimersTest {
    private fun timers(keepalive: Int = 25) = WgTunnelTimers(keepalive, retryDelayMs = { 5_000L })

    @Test
    fun `unanswered initiations are retried and time out after thirty seconds`() {
        val t = timers().apply { start(0) }
        assertEquals(0, t.tick(4_999, established = false))
        assertEquals(INITIATE, t.tick(5_000, established = false))
        assertEquals(0, t.tick(9_999, established = false))
        assertEquals(INITIATE, t.tick(10_000, established = false))
        assertEquals(0, t.tick(30_000, established = false) and TIMED_OUT)
        assertEquals(TIMED_OUT, t.tick(30_001, established = false) and TIMED_OUT)
    }

    @Test
    fun `completed handshake stops retries and keepalives follow the interval`() {
        val t = timers().apply { start(0) }
        t.handshakeCompleted(1_000)
        assertFalse(t.handshakePending)
        assertEquals(0, t.tick(25_000, established = true))
        assertEquals(KEEPALIVE, t.tick(25_001, established = true))
        assertEquals(0, t.tick(40_000, established = true))
        assertEquals(KEEPALIVE, t.tick(50_002, established = true))
    }

    @Test
    fun `keepalive disabled sends nothing`() {
        val t = timers(keepalive = 0).apply { start(0) }
        t.handshakeCompleted(0)
        assertEquals(0, t.tick(100_000, established = true))
    }

    @Test
    fun `sessions are rekeyed after two minutes and keep retrying through an outage`() {
        val t = timers(keepalive = 0).apply { start(0) }
        t.handshakeCompleted(1_000)
        assertEquals(0, t.tick(121_000, established = true))
        assertEquals(INITIATE, t.tick(121_001, established = true))
        assertTrue(t.handshakePending)
        assertEquals(0, t.tick(126_000, established = true))
        assertEquals(INITIATE, t.tick(126_001, established = true))
        // An outage during a rekey never ends a tunnel that was up: it keeps retrying.
        assertEquals(0, t.tick(151_002, established = true) and TIMED_OUT)
        assertEquals(INITIATE, t.tick(400_000, established = true) and INITIATE)
        assertEquals(0, t.tick(400_001, established = true) and TIMED_OUT)
        t.handshakeCompleted(401_000)
        assertEquals(0, t.tick(402_000, established = true))
    }

    @Test
    fun `cookie reply restarts the retry delay but keeps the pending deadline`() {
        val t = timers().apply { start(0) }
        t.initiationSent(4_000)
        assertEquals(0, t.tick(8_999, established = false))
        assertEquals(INITIATE, t.tick(9_000, established = false))
        assertEquals(TIMED_OUT, t.tick(30_001, established = false) and TIMED_OUT)
    }

    @Test
    fun `no rekey before the first completed handshake`() {
        val t = timers(keepalive = 0)
        assertEquals(0, t.tick(500_000, established = true))
    }

    @Test
    fun `the route stays up through a rekey and reports only while the keypair is gone`() {
        val reported = mutableListOf<Boolean>()
        val route = WgRouteAnnouncer { reported += it }
        val t = timers(keepalive = 0).apply { start(0) }
        route.update(established = false)
        t.handshakeCompleted(1_000)
        route.update(established = true)
        // Periodic rekey: an initiation goes out while the current keypair keeps working.
        assertEquals(INITIATE, t.tick(121_001, established = true))
        route.update(established = true)
        assertEquals(listOf(true), reported)
        // The rekey went unanswered until the keypair expired (180 s): now the route is down.
        route.update(established = false)
        route.update(established = false)
        route.update(established = true)
        assertEquals(listOf(true, false, true), reported)
    }
}
