package com.ambient.tv.vpn

/**
 * Handshake and keepalive timing of the tunnel loop, separated from sockets for tests.
 * [tick] runs after every datagram (or receive timeout), so it returns bit flags instead of
 * allocating a result.
 */
internal class WgTunnelTimers(
    private val keepaliveSeconds: Int,
    private val retryDelayMs: () -> Long,
    private val rekeyAfterMs: Long = 120_000L,
    private val handshakeTimeoutMs: Long = 30_000L,
) {
    private var lastHandshakeSuccess = 0L
    private var everCompleted = false
    private var nextRetryAt = 0L
    private var pendingSince = 0L
    private var lastKeepalive = 0L
    var handshakePending = false
        private set

    /** The first initiation went out when the tunnel started. */
    fun start(nowMs: Long) {
        lastKeepalive = nowMs
        handshakePending = true
        pendingSince = nowMs
        nextRetryAt = nowMs + retryDelayMs()
    }

    /** A fresh initiation was sent outside [tick] (after a cookie reply). */
    fun initiationSent(nowMs: Long) {
        nextRetryAt = nowMs + retryDelayMs()
        if (!handshakePending) pendingSince = nowMs
        handshakePending = true
    }

    fun handshakeCompleted(nowMs: Long) {
        lastHandshakeSuccess = nowMs
        everCompleted = true
        handshakePending = false
    }

    /** What is due now; the caller sends the initiation/keepalive the flags name. */
    fun tick(nowMs: Long, established: Boolean): Int {
        var due = 0
        if (handshakePending && nowMs >= nextRetryAt) {
            due = due or INITIATE
            nextRetryAt = nowMs + retryDelayMs()
        }
        if (established && keepaliveSeconds > 0 && nowMs - lastKeepalive > keepaliveSeconds * 1000L) {
            due = due or KEEPALIVE
            lastKeepalive = nowMs
        }
        if (established && !handshakePending && everCompleted && nowMs - lastHandshakeSuccess > rekeyAfterMs) {
            due = due or INITIATE
            nextRetryAt = nowMs + retryDelayMs()
            pendingSince = nowMs
            handshakePending = true
        }
        // Only a tunnel that never came up gives up. One that was up keeps retrying through a
        // network outage (Wi-Fi switch, router restart) and recovers by itself when it ends.
        if (handshakePending && !everCompleted && nowMs - pendingSince > handshakeTimeoutMs) due = due or TIMED_OUT
        return due
    }

    companion object {
        const val INITIATE = 1
        const val KEEPALIVE = 2
        const val TIMED_OUT = 8
    }
}

/**
 * Reports the route state from the keypair alone: up while a transport keypair is valid,
 * connecting otherwise. Reports only changes, so per-datagram calls stay cheap.
 */
internal class WgRouteAnnouncer(private val announce: (up: Boolean) -> Unit) {
    // The tunnel starts by reporting "connecting" itself.
    private var up = false

    fun update(established: Boolean) {
        if (established == up) return
        up = established
        announce(established)
    }
}
