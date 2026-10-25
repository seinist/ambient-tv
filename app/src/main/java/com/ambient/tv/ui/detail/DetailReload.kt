package com.ambient.tv.ui.detail

import com.ambient.tv.data.browse.SourceHealth

/** Backoff between automatic detail reloads after a temporary failure (server still starting). */
internal val DETAIL_RELOAD_BACKOFF_MS = listOf(2_000L, 4_000L, 8_000L)

/** Automatic reloads after temporary failures before the page settles on "offline". */
internal const val DETAIL_MAX_AUTO_RELOADS = 10

internal fun detailReloadDelayMs(attempt: Int): Long =
    DETAIL_RELOAD_BACKOFF_MS[attempt.coerceIn(0, DETAIL_RELOAD_BACKOFF_MS.lastIndex)]

/** 404/410: the item is gone; no reload brings it back. */
internal fun detailItemRemoved(error: Throwable): Boolean =
    (error as? retrofit2.HttpException)?.code() in setOf(404, 410)

/**
 * A failure a reload can fix: no answer yet (refused, timeout, a cache switching sources) or a
 * server that is still starting (5xx, 408, 429). Authentication and "removed" are final.
 */
internal fun detailFailureTransient(error: Throwable): Boolean {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth++ < 8) {
        when (current) {
            is kotlinx.coroutines.CancellationException -> return false
            is retrofit2.HttpException -> return current.code().let { it >= 500 || it == 408 || it == 429 }
            is java.io.IOException -> return true
        }
        current = current.cause
    }
    return false
}

/**
 * "Offline" (Play hidden) comes only from the source's availability - its host is down or the
 * service refused - never from a single failed request: a 503 of a server that is still starting
 * or a timeout keeps Play visible while the page reloads with backoff.
 */
internal fun detailOfflineAfterFailure(removed: Boolean, sourceHealth: SourceHealth, retriesLeft: Boolean): Boolean = when {
    removed -> false
    sourceHealth == SourceHealth.OFFLINE || sourceHealth == SourceHealth.AUTH_REQUIRED -> true
    else -> !retriesLeft
}

/**
 * Tracks the connection for the detail page's automatic reload. Only a real recovery reloads:
 * Online after a settled Offline/WakeTimedOut/Error, or after a wake (the server was asleep).
 * Passive re-checks (Online → Checking/Connecting → Online) keep the page as it is; they would
 * otherwise reload the page on every resume or background probe.
 */
internal data class DetailConnectionTracker(
    /** The last state that was not in flight; null before the first one. */
    val settled: com.ambient.tv.server.ServerConnectionState? = null,
    /** A wake packet went out since [settled]. */
    val woke: Boolean = false,
) {
    /** The next tracker and whether the page reloads now. */
    fun next(state: com.ambient.tv.server.ServerConnectionState): Pair<DetailConnectionTracker, Boolean> = when (state) {
        com.ambient.tv.server.ServerConnectionState.SendingWakePacket,
        is com.ambient.tv.server.ServerConnectionState.Waking -> copy(woke = true) to false
        com.ambient.tv.server.ServerConnectionState.Checking,
        com.ambient.tv.server.ServerConnectionState.Connecting -> this to false
        is com.ambient.tv.server.ServerConnectionState.Online -> DetailConnectionTracker(state) to connectionCameOnline(settled, woke, state)
        else -> DetailConnectionTracker(state) to false
    }
}

/** Online after a settled failure state or after a wake: the page reloads. */
internal fun connectionCameOnline(
    lastSettled: com.ambient.tv.server.ServerConnectionState?,
    woke: Boolean,
    current: com.ambient.tv.server.ServerConnectionState,
): Boolean = current is com.ambient.tv.server.ServerConnectionState.Online && (woke ||
    lastSettled == com.ambient.tv.server.ServerConnectionState.Offline ||
    lastSettled == com.ambient.tv.server.ServerConnectionState.WakeTimedOut ||
    lastSettled is com.ambient.tv.server.ServerConnectionState.Error)
