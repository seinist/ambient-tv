package com.ambient.tv.connectivity

/**
 * Pure rules for when a foreground resume should re-probe network, VPN and media servers.
 * Kept free of Android types so unit tests stay hermetic.
 */
object ResumeConnectivityPolicy {
    const val BACKGROUND_THRESHOLD_MS = 45_000L
    /** Matches [com.ambient.tv.server.ServerConnectionManager] reuse window. */
    const val HEALTH_STALE_MS = 30_000L
    /** One short WoL wait on resume; full cold-start wake remains 60s elsewhere. */
    const val BRIEF_WAKE_TIMEOUT_MS = 8_000L
    const val BRIEF_WAKE_POLL_MS = 2_000L

    fun shouldRecheck(
        backgroundedForMs: Long,
        lastHealthAgeMs: Long,
        backgroundThresholdMs: Long = BACKGROUND_THRESHOLD_MS,
        healthStaleMs: Long = HEALTH_STALE_MS,
    ): Boolean {
        if (backgroundedForMs < 0L || lastHealthAgeMs < 0L) return true
        return backgroundedForMs >= backgroundThresholdMs || lastHealthAgeMs >= healthStaleMs
    }
}
