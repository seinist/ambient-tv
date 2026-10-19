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
