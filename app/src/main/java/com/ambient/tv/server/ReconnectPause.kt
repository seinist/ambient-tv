package com.ambient.tv.server

/**
 * "Dismissed" server failure: automatic reconnects stay off for this endpoint until the user
 * reconnects, the endpoint changes, or a passive background check finds the server online again.
 * Without the last clear a later outage would never auto-reconnect.
 */
internal class ReconnectPause {
    @Volatile private var pausedEndpoint: String? = null
    fun pause(endpoint: String) { pausedEndpoint = endpoint }
    fun clear() { pausedEndpoint = null }
    fun isPaused(endpoint: String): Boolean = pausedEndpoint == endpoint
    /** A passive check reached the server: the dismissed outage is over. */
    fun onlineAgain() = clear()
}

/** Passive checks must try offline services regardless of whether automatic waking is paused.
 * Only a fresh, healthy service can skip a non-forced check. Never infer service readiness
 * merely from a different open port on the same physical machine. */
internal fun shouldProbeInBackground(
    configured: Boolean,
    force: Boolean,
    serviceOnline: Boolean,
    hostOnline: Boolean,
): Boolean = configured && (force || !serviceOnline || !hostOnline)

/** A resume that joined an in-flight cached connect still needs a fresh service probe.
 * A real successful API check updates lastSuccessfulCheckMs; a cached reuse does not. */
internal fun needsPostJoinResumeVerification(connected: Boolean, lastSuccessfulCheckMs: Long): Boolean =
    connected && lastSuccessfulCheckMs == 0L
