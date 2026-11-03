package com.ambient.tv.connectivity

/**
 * Pure rules for when a foreground resume should re-probe network, VPN and media servers.
 * Kept free of Android types so unit tests stay hermetic.
 */
object ResumeConnectivityPolicy {
    const val BACKGROUND_THRESHOLD_MS = 45_000L
    /** Matches [com.ambient.tv.server.ServerConnectionManager] reuse window. */
    const val HEALTH_STALE_MS = 30_000L

    /** A real process foreground return must validate services even if the last check
     * was recent: the server may have shut down while the phone was backgrounded. */
    fun shouldRecheckOnStart(
        wasBackgrounded: Boolean,
        backgroundedForMs: Long,
        lastHealthAgeMs: Long,
        /** Phones switch apps all the time: only a longer absence re-checks every system. */
        handheld: Boolean = false,
    ): Boolean = when {
        !wasBackgrounded -> shouldRecheck(backgroundedForMs, lastHealthAgeMs)
        handheld -> backgroundedForMs >= HANDHELD_BACKGROUND_THRESHOLD_MS
        else -> true
    }

    /** A network change still re-checks at once; this only covers a plain foreground return. */
    const val HANDHELD_BACKGROUND_THRESHOLD_MS = 10 * 60_000L

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
