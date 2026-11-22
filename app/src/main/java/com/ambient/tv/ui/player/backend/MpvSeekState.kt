package com.ambient.tv.ui.player.backend

import com.ambient.tv.ui.player.PlaybackSeekCoordinator

/** Owner-thread seeks issued before libmpv has finished opening the file. */
internal class MpvDeferredSeek(initialPositionMs: Long? = null) {
    private var openPositionMs = initialPositionMs?.coerceAtLeast(0L)
    private data class PendingSeek(val requestedPositionMs: Long, val openPositionMs: Long)
    private var pending: PendingSeek? = null

    fun defer(positionMs: Long, loadRequested: Boolean, fileReady: Boolean, openPositionMs: Long = positionMs): Boolean {
        if (fileReady) return false
        // Keep the logical request for replay: duration means an exact EOF tail seek, while
        // its safe loadfile start is already 1.5 s earlier and must not become a keyframe seek.
        if (loadRequested) pending = PendingSeek(positionMs.coerceAtLeast(0L), openPositionMs.coerceAtLeast(0L))
        else this.openPositionMs = openPositionMs.coerceAtLeast(0L)
        return true
    }

    fun takeOpenPosition(): Long? = openPositionMs.also { openPositionMs = null }
    fun takePendingPosition(): Long? = pending?.requestedPositionMs.also { pending = null }

    /** Output recovery preserves an in-flight request; a newer explicit seek supersedes it. */
    fun takeReopenPosition(positionMs: Long, preferPendingSeek: Boolean): Long {
        val pendingOpenPosition = pending?.openPositionMs
        pending = null
        openPositionMs = null
        return (if (preferPendingSeek) pendingOpenPosition ?: positionMs else positionMs).coerceAtLeast(0L)
    }

    fun resetForOpen(positionMs: Long?) {
        openPositionMs = positionMs?.coerceAtLeast(0L)
        pending = null
    }
}

/** A failed/unavailable property read preserves the clock; a valid zero is the file's start. */
internal fun mpvConfirmedPositionMs(seconds: Double, previousPositionMs: Long): Long =
    if (seconds.isFinite() && seconds >= 0.0) (seconds * 1_000.0).toLong()
    else previousPositionMs.coerceAtLeast(0L)

/**
 * Main-thread seek target. An accepted mpv_command only means that libmpv queued the seek.
 * Keep relative button repeats on that target until the sampled clock acknowledges a landing.
 * Use the same keyframe/old-clock rules as the player controls instead of treating a command
 * revision (or a later unrelated command) as proof that the decoder moved.
 */
internal class MpvPendingSeek(private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private var coordinator = PlaybackSeekCoordinator(nowMs = nowMs)
    private var revision: Long? = null

    val targetMs: Long? get() = coordinator.pendingTarget
    val isPending: Boolean get() = targetMs != null

    fun request(revision: Long, positionMs: Long) {
        this.revision = revision
        coordinator.requestAbsolute(positionMs)
    }

    fun observe(completedRevision: Long, positionMs: Long, failed: Boolean = false) {
        if (revision?.let { completedRevision < it } == true) return
        if (failed) {
            if (revision != null) {
                coordinator = PlaybackSeekCoordinator(nowMs = nowMs)
                revision = null
            }
            return
        }
        coordinator.displayedPosition(positionMs)
    }
}

/** A step from the unconfirmed seek target if any, else from the last sampled position. */
internal fun mpvSeekByTarget(pendingTargetMs: Long?, sampledPositionMs: Long, deltaMs: Long): Long =
    ((pendingTargetMs ?: sampledPositionMs) + deltaMs).coerceAtLeast(0L)
