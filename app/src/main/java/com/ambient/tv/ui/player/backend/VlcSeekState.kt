package com.ambient.tv.ui.player.backend

import kotlin.math.abs

/** A submitted seek is not a landed seek. Keep even zero until the native clock settles. */
internal class VlcSeekState {
    var targetMs: Long? = null
        private set
    private var readyAtMs: Long? = null
    private var submittedAtMs: Long? = null
    private var submissions = 0
    private var alignedAtMs: Long? = null

    fun request(target: Long) {
        targetMs = target.coerceAtLeast(0L)
        readyAtMs = null
        submittedAtMs = null
        submissions = 0
        alignedAtMs = null
    }

    fun beginReadyWait(now: Long) { if (targetMs != null && readyAtMs == null) readyAtMs = now }

    fun clear() { targetMs = null; submittedAtMs = null; alignedAtMs = null }

    fun shouldSubmit(ready: Boolean, now: Long): Boolean = targetMs != null && ready &&
        (submittedAtMs == null || (submissions < 2 && alignedAtMs == null && now - submittedAtMs!! >= 3_000L))

    fun submitted(accepted: Boolean, now: Long) {
        if (accepted) { submittedAtMs = now; submissions++; alignedAtMs = null }
    }

    /** Clock evidence, never the optimistic timestamp returned by status(). */
    fun observe(clockMs: Long, now: Long): Boolean {
        val target = targetMs ?: return false
        if (submittedAtMs == null) return false
        if (clockMs < 0L || abs(clockMs - target) > 1_000L) { alignedAtMs = null; return false }
        val since = alignedAtMs
        if (since == null) { alignedAtMs = now; return false }
        if (now - since < 300L) return false
        clear()
        return true
    }

    fun expired(now: Long): Boolean = targetMs != null && readyAtMs?.let { now - it >= 30_000L } == true
}
