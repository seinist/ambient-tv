package com.ambient.tv.ui.player.backend

/** Outcome of one zero-copy frame check poll. */
internal sealed interface ZeroCopyNoFrames {
    data object Wait : ZeroCopyNoFrames
    /** Reopen on copy-back; [remember] locks zero-copy for this app version (real evidence only). */
    data class Fail(val remember: Boolean) : ZeroCopyNoFrames
}

/**
 * Zero-copy black-plane detection. Only time in which playback really runs (not paused for cache and the
 * clock advancing) counts as evidence: after [EVIDENCE_NS] of that without VO output parameters the failure
 * is real and remembered. A start that never gets going (slow network, endless buffering) is reopened on
 * copy-back after [SAFETY_NS] without locking zero-copy.
 */
internal class ZeroCopyFrameWatch {
    private var startNs = 0L
    private var lastPollNs = 0L
    private var lastPositionMs = -1L
    private var playingNs = 0L

    fun reset() { startNs = 0L; lastPollNs = 0L; lastPositionMs = -1L; playingNs = 0L }

    /** One poll while the zero-copy decoder is engaged and the VO still has no output parameters. */
    fun poll(nowNs: Long, buffering: Boolean, positionMs: Long): ZeroCopyNoFrames {
        if (startNs == 0L) { startNs = nowNs; lastPollNs = nowNs; lastPositionMs = positionMs; return ZeroCopyNoFrames.Wait }
        if (!buffering && positionMs > lastPositionMs && lastPositionMs >= 0L) playingNs += nowNs - lastPollNs
        lastPollNs = nowNs
        lastPositionMs = positionMs
        return when {
            playingNs >= EVIDENCE_NS -> ZeroCopyNoFrames.Fail(remember = true)
            nowNs - startNs >= SAFETY_NS -> ZeroCopyNoFrames.Fail(remember = false)
            else -> ZeroCopyNoFrames.Wait
        }
    }

    companion object {
        const val EVIDENCE_NS = 3_000_000_000L
        const val SAFETY_NS = 10_000_000_000L
    }
}
