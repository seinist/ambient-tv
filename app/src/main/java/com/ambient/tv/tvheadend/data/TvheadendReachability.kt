package com.ambient.tv.tvheadend.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Whether stored data was served because the server is really gone (socket/connect failure, host
 * not found, VPN route down, Wake-on-LAN timeout; [error] null = the socket check failed).
 * This classifies fallback/revalidation only. Menus and browse actions use shared host evidence;
 * an individual slow endpoint must not label an otherwise reachable machine offline.
 */
internal fun tvhFallbackMarksOffline(error: Throwable?): Boolean = error == null || when (classifyTvheadendTestFailure(error)) {
    TvheadendTestFailure.UNREACHABLE, TvheadendTestFailure.WAKE, TvheadendTestFailure.HOST, TvheadendTestFailure.VPN -> true
    else -> false
}

/**
 * One socket probe at a time, its answer shared for [windowMs] (per endpoint): a screen loading
 * many lists in parallel waits for one 0.9 s check instead of one each.
 */
internal class SharedReachabilityProbe(private val windowMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var last: Triple<Any, Long, Boolean>? = null

    suspend fun check(key: Any, probe: suspend () -> Boolean): Boolean = mutex.withLock {
        val now = clock()
        last?.let { (k, at, value) -> if (k == key && now >= at && now - at < windowMs) return@withLock value }
        probe().also { last = Triple(key, clock(), it) }
    }

    fun invalidate() { last = null }
}
