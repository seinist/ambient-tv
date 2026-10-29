package com.ambient.tv.livetv

import com.ambient.tv.connectivity.HostHealth
import com.ambient.tv.notifications.FailureKind
import com.ambient.tv.notifications.classifyFailure

/** A saved TV server must not borrow the currently selected media server's health. */
internal fun liveTvHostSource(sourceId: String, system: String): String =
    if (system == "tvheadend") "tvheadend" else sourceId

/** Cache fallback, an EPG error and a rejected login are not evidence of an offline machine. */
internal fun liveTvSourceOffline(hasNetwork: Boolean, host: HostHealth): Boolean =
    !hasNetwork || host == HostHealth.OFFLINE

/** A temporary endpoint failure can be retried without mislabelling the source as offline. */
internal fun liveTvRefreshRetryable(error: Throwable): Boolean {
    val failure = classifyFailure(error)
    return failure.kind in setOf(FailureKind.HOST, FailureKind.UNREACHABLE, FailureKind.TIMEOUT, FailureKind.VPN, FailureKind.WAKE) ||
        failure.httpCode?.let { it == 408 || it in 500..599 } == true
}
