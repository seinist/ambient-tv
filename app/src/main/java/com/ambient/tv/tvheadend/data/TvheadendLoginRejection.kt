package com.ambient.tv.tvheadend.data

/**
 * Decides when a TVHeadend 401 proves the login wrong, so a waking or busy server does not take
 * TVHeadend out of the menu. Only an explicit settings test, or two 401s on [PROBE_PATH] at least
 * [CONFIRM_GAP_MS] apart, with the port reachable and outside [WAKE_GRACE_MS] after a wake, reject.
 * Other paths can answer 401 for a missing right (recordings, profiles) and never reject.
 */
internal class TvheadendLoginRejection {
    private var first401AtMs: Long? = null

    @Synchronized
    fun onSuccess() { first401AtMs = null }

    /** True when this 401 means the login is rejected. */
    @Synchronized
    fun on401(path: String, explicit: Boolean, portReachable: Boolean, lastWakeAtMs: Long, nowMs: Long): Boolean {
        if (explicit) return true.also { first401AtMs = null }
        if (path != PROBE_PATH || !portReachable) return false
        if (lastWakeAtMs > 0L && nowMs - lastWakeAtMs < WAKE_GRACE_MS) return false
        val first = first401AtMs
        if (first == null || nowMs < first) {
            first401AtMs = nowMs
            return false
        }
        if (nowMs - first < CONFIRM_GAP_MS) return false
        first401AtMs = null
        return true
    }

    companion object {
        const val PROBE_PATH = "api/serverinfo"
        const val CONFIRM_GAP_MS = 30_000L
        const val WAKE_GRACE_MS = 60_000L
    }
}
