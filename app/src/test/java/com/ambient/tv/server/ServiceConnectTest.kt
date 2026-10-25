package com.ambient.tv.server

import com.ambient.tv.connectivity.HostHealth
import com.ambient.tv.domain.model.PlexServerIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class ServiceConnectTest {
    private fun http(code: Int) = retrofit2.HttpException(Response.error<Any>(code, "".toResponseBody()))
    private val online = ServerConnectionState.Online(PlexServerIdentity(machineIdentifier = "m", version = "1", claimed = true))

    @Test
    fun `starting server errors are transient, login and not found are final`() {
        assertTrue(isTransientConnectFailure(http(503)))
        assertTrue(isTransientConnectFailure(http(500)))
        assertTrue(isTransientConnectFailure(http(429)))
        assertTrue(isTransientConnectFailure(java.net.ConnectException("refused")))
        assertTrue(isTransientConnectFailure(java.net.SocketTimeoutException()))
        assertTrue(isTransientConnectFailure(RuntimeException(java.io.IOException("wrapped"))))
        assertTrue(isTransientConnectFailure(com.ambient.tv.tvheadend.data.TvheadendHttpException(502, "bad gateway")))
        assertFalse(isTransientConnectFailure(http(401)))
        assertFalse(isTransientConnectFailure(http(404)))
        assertFalse(isTransientConnectFailure(IllegalStateException("not configured")))
        assertFalse(isTransientConnectFailure(kotlinx.coroutines.CancellationException()))
    }

    @Test
    fun `identity is retried after a 503 until it answers`() = runBlocking {
        var now = 0L
        val sleeps = mutableListOf<Long>()
        var calls = 0
        val result = retryTransient(deadlineMs = 60_000L, nowMs = { now }, sleep = { sleeps += it; now += it }) {
            if (++calls < 4) Result.failure(http(503)) else Result.success("identity")
        }
        assertEquals("identity", result.getOrNull())
        assertEquals(listOf(2_000L, 4_000L, 8_000L), sleeps)
    }

    @Test
    fun `identity retries stop at the deadline and report the last error`() = runBlocking {
        var now = 0L
        var calls = 0
        val result = retryTransient<String>(deadlineMs = 10_000L, nowMs = { now }, sleep = { now += it }) {
            calls++; Result.failure(http(503))
        }
        assertTrue(result.isFailure)
        // 0 s, 2 s, 6 s; the next (14 s) would pass the deadline.
        assertEquals(3, calls)
    }

    @Test
    fun `a final error is not retried`() = runBlocking {
        var calls = 0
        val result = retryTransient<String>(deadlineMs = Long.MAX_VALUE, sleep = {}) { calls++; Result.failure(http(401)) }
        assertTrue(result.isFailure)
        assertEquals(1, calls)
    }

    @Test
    fun `callers join one running connect and it survives a cancelled caller`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val run = SharedConnectRun(scope)
        val gate = CompletableDeferred<Unit>()
        var started = 0
        val first = async(Dispatchers.Default) { run.joinOrStart { started++; gate.await(); true } }
        while (!run.active) kotlinx.coroutines.delay(1)
        val second = async(Dispatchers.Default) { run.joinOrStart { started++; true } }
        kotlinx.coroutines.delay(20)
        first.cancel()
        assertTrue(run.active)
        gate.complete(Unit)
        assertTrue(withTimeout(5_000) { second.await() })
        assertEquals(1, started)
    }

    @Test
    fun `a cancelled connect never leaves a waking state behind`() {
        assertEquals(ServerConnectionState.Offline, settledStateAfterCancel(ServerConnectionState.Checking))
        assertEquals(ServerConnectionState.Offline, settledStateAfterCancel(ServerConnectionState.Waking(3, 60)))
        assertEquals(online, settledStateAfterCancel(online))
        assertEquals(ServerConnectionState.WakeTimedOut, settledStateAfterCancel(ServerConnectionState.WakeTimedOut))
        assertTrue(isInFlightConnectState(ServerConnectionState.SendingWakePacket))
        assertTrue(isInFlightConnectState(ServerConnectionState.Waking(1, 60)))
        assertFalse(isInFlightConnectState(online))
    }

    private class Clock { var now = 100_000_000L; fun read(): Long { now += 1_000L; return now } }

    @Test
    fun `a running service connects at once`() = runBlocking {
        val states = mutableListOf<ServerConnectionState>()
        val result = waitForService({ true }, { error("not asked") }, { HostHealth.UNKNOWN }, canWake = true, send = { error("no wake") },
            retries = 2, windowMs = 10_000L, pollMs = 1L, setState = { states += it })
        assertNull(result.failure)
        assertFalse(result.waited)
        assertTrue(states.isEmpty())
    }

    @Test
    fun `a sleeping machine is woken and the wait ends only when the service answers`() = runBlocking {
        val clock = Clock()
        val states = mutableListOf<ServerConnectionState>()
        val sent = mutableListOf<Int>()
        var polls = 0
        val result = waitForService(
            serviceReady = { ++polls >= 25 },
            machineAwake = { polls >= 10 },
            hostHealth = { HostHealth.OFFLINE },
            canWake = true, send = { sent += it },
            retries = 2, windowMs = 60_000L, pollMs = 1L,
            setState = { states += it }, lastWakeAtMs = { 0L }, nowMs = clock::read,
        )
        assertNull(result.failure)
        assertTrue(result.waited)
        assertEquals(listOf(0), sent)
        val waking = states.filterIsInstance<ServerConnectionState.Waking>()
        assertFalse(waking.first().serviceStarting)
        // "Machine awake, service starting" once its other ports answer.
        assertTrue(waking.last().serviceStarting)
        assertEquals(180, waking.last().totalSeconds)
    }

    @Test
    fun `an awake machine whose service still starts after a wake is waited for without packets`() = runBlocking {
        val clock = Clock()
        val states = mutableListOf<ServerConnectionState>()
        var polls = 0
        val result = waitForService(
            serviceReady = { ++polls >= 5 }, machineAwake = { true }, hostHealth = { HostHealth.OFFLINE },
            canWake = true, send = { error("no packets for an awake machine") },
            retries = 2, windowMs = 60_000L, pollMs = 1L,
            setState = { states += it }, lastWakeAtMs = { 0L }, nowMs = clock::read,
        )
        assertNull(result.failure)
        assertTrue(states.filterIsInstance<ServerConnectionState.Waking>().all { it.serviceStarting })
        assertTrue(states.none { it == ServerConnectionState.SendingWakePacket })
    }

    @Test
    fun `an awake machine with a long dead service is not waited for`() = runBlocking {
        val clock = Clock()
        val result = waitForService({ false }, { true }, { HostHealth.ONLINE }, canWake = true, send = { error("no wake") },
            retries = 2, windowMs = 60_000L, pollMs = 1L, setState = { error("no waiting state") },
            lastWakeAtMs = { 0L }, nowMs = clock::read)
        assertNull(result.failure)
        assertFalse(result.waited)
    }

    @Test
    fun `a machine that never answers ends as wake timed out, without wake as offline`() = runBlocking {
        val timedOut = waitForService({ false }, { false }, { HostHealth.OFFLINE }, canWake = true, send = {},
            retries = 1, windowMs = 5_000L, pollMs = 1L, setState = {}, lastWakeAtMs = { 0L }, nowMs = Clock()::read)
        assertEquals(ServerConnectionState.WakeTimedOut, timedOut.failure)
        val offline = waitForService({ false }, { false }, { HostHealth.OFFLINE }, canWake = false, send = {},
            retries = 1, windowMs = 5_000L, pollMs = 1L, setState = {}, lastWakeAtMs = { 0L }, nowMs = Clock()::read)
        assertEquals(ServerConnectionState.Offline, offline.failure)
    }
}
