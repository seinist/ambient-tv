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
 * The health message distinguishes a confirmed offline/auth failure from a transient retry.
 * It does not grant playback: DetailConnectionPolicy locks every failed metadata refresh until
 * a fresh request succeeds, even while the service's broad availability still says ONLINE.
 */
internal fun detailOfflineAfterFailure(removed: Boolean, sourceHealth: SourceHealth, retriesLeft: Boolean): Boolean = when {
    removed -> false
    sourceHealth == SourceHealth.OFFLINE || sourceHealth == SourceHealth.AUTH_REQUIRED -> true
    else -> !retriesLeft
}
