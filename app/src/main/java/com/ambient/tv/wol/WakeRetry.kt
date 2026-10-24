package com.ambient.tv.wol

import kotlinx.coroutines.delay

/** Choices offered in Network settings for extra wake cycles after the first one. */
val WOL_RETRY_COUNTS = listOf(0, 1, 2, 3, 5)

/** Two extra cycles cover a lost burst and a slow NAS/PC boot without leaving Home waiting for minutes. */
const val WOL_RETRY_COUNT_DEFAULT = 2

internal fun normalizedWolRetryCount(value: Int): Int = value.takeIf { it in WOL_RETRY_COUNTS } ?: WOL_RETRY_COUNT_DEFAULT

/**
 * Runs the first wake cycle and then up to [retries] more: [send] a burst, then poll [reachable]
 * for [windowMs]. A retry starts only after a full wait window has passed without an answer, so
 * the loop is bounded by (retries + 1) * windowMs and never ends up retrying forever.
 *
 * [onWaiting] receives the seconds since the first burst, so a waiting counter keeps counting up
 * across retries instead of jumping back to zero. [send] gets the cycle index (0 = first burst);
 * callers pass `retry = attempt > 0` to [WolManager.sendWakeBurst] so a retry is never swallowed
 * by the burst coalescing of the first cycle.
 */
/** The longest [wakeWithRetries] can wait with these settings: one window per send. */
internal fun wakeBudgetMs(retries: Int, windowMs: Long): Long = (retries.coerceIn(0, WOL_RETRY_COUNTS.max()) + 1) * windowMs

internal suspend fun wakeWithRetries(
    retries: Int,
    windowMs: Long,
    pollMs: Long,
    send: suspend (attempt: Int) -> Unit,
    reachable: suspend () -> Boolean,
    onWaiting: suspend (elapsedSeconds: Int) -> Unit = {},
    nowMs: () -> Long = System::currentTimeMillis,
): Boolean {
    val started = nowMs()
    val cycles = retries.coerceIn(0, WOL_RETRY_COUNTS.max()) + 1
    for (attempt in 0 until cycles) {
        send(attempt)
        val windowStart = nowMs()
        while (nowMs() - windowStart < windowMs) {
            onWaiting(((nowMs() - started) / 1000).toInt())
            if (reachable()) return true
            delay(pollMs)
        }
    }
    return false
}
