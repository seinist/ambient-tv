package com.ambient.tv.ui.player

/**
 * Coalesces interactive seeks onto one absolute target.
 *
 * YouTube DASH/HLS reports a stale or rewound engine position while a seek is still in flight.
 * Adding another relative jump to that value sends the player backwards and cancels the previous
 * request. The OSD keeps the requested time until the engine has played up to it.
 *
 * VLC and MPV seek to the keyframe before the target and answer with the target itself while
 * the seek is in flight. Handing the OSD back on that first answer made it drop to the keyframe
 * a moment later, and the next key repeat then stacked on the keyframe instead of the target.
 * A clock that is sitting still must not do that either: buffering and a paused keyframe wait
 * are not a landing. An advancing clock may land several seconds behind the target, which is a
 * normal keyframe, but not on a position that is still the pre-seek start.
 */
class PlaybackSeekCoordinator(
    val debounceMs: Long = DEBOUNCE_MS,
    private val snapToleranceMs: Long = SNAP_TOLERANCE_MS,
    private val holdMs: Long = HOLD_MS,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private var pendingTargetMs: Long? = null
    private var lastRequestAtMs: Long = 0L
    private var lastCommittedTargetMs: Long? = null
    private var lastEngineMs: Long = -1L
    private var engineMovedAtMs: Long = 0L
    private var alignedAtMs: Long? = null
    /** Target cleared by the latest in-window ack, until the next sample confirms it. */
    private var optimisticAckTargetMs: Long? = null
    private var optimisticAckCommittedMs: Long? = null
    /** The ack above was taken on a stopped clock (paused or buffering). */
    private var optimisticAckStopped = false
    /** Engine position and time of the latest request, to tell a landing from the old clock. */
    private var requestEngineMs: Long = -1L

    val pendingTarget: Long? get() = pendingTargetMs

    fun requestRelative(deltaMs: Long, enginePositionMs: Long, durationMs: Long): Long {
        val limit = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        val base = pendingTargetMs ?: enginePositionMs.coerceAtLeast(0L)
        return requestAbsolute((base + deltaMs).coerceIn(0L, limit))
    }

    fun requestAbsolute(targetMs: Long): Long {
        val target = targetMs.coerceAtLeast(0L)
        pendingTargetMs = target
        lastRequestAtMs = nowMs()
        requestEngineMs = lastEngineMs
        // A freshly prepared Media3 player (and a native player opening a long GOP stream) can
        // keep reporting its old/start position for several seconds. Do not let an alignment from
        // the previous request hand the OSD back to that stale clock.
        alignedAtMs = null
        optimisticAckTargetMs = null
        optimisticAckCommittedMs = null
        optimisticAckStopped = false
        return target
    }

    fun displayedPosition(enginePositionMs: Long): Long {
        val engine = enginePositionMs.coerceAtLeast(0L)
        val now = nowMs()
        val movedNow = engine != lastEngineMs
        if (movedNow) {
            lastEngineMs = engine
            engineMovedAtMs = now
        }
        recoverFromOptimisticAck(engine)
        val pending = pendingTargetMs ?: return engine
        observeAlignment(pending, engine, now, movedNow)
        val sinceRequest = now - lastRequestAtMs
        // A held key pauses ~400 ms before it repeats; until then the engine is only on its way
        // to an earlier target of the same burst.
        if (sinceRequest < holdMs || !handsOver(pending, engine, now)) return pending
        optimisticAckTargetMs = pending
        optimisticAckCommittedMs = lastCommittedTargetMs
        optimisticAckStopped = now - engineMovedAtMs >= IDLE_MS
        pendingTargetMs = null
        lastCommittedTargetMs = null
        alignedAtMs = null
        return engine
    }

    private fun handsOver(pending: Long, engine: Long, now: Long): Boolean {
        // Never exchange a requested target for an arbitrarily old engine value merely because a
        // fixed timeout elapsed. At startup that produced the visible "seek forward, jump back to
        // zero, then continue" sequence on Media3; the same stale-position window exists in VLC,
        // MPV and YouTube streams. Handover needs positive evidence that playback reached the
        // target and stayed there briefly.
        val playing = now - engineMovedAtMs < IDLE_MS
        if (!playing) {
            // A frozen clock (buffering, or a paused renderer waiting on a keyframe) must not
            // replace the requested time with an earlier frame. Keep the target until the engine
            // is at or ahead of the landing, or until the clock moves and the playing path accepts
            // a keyframe within [ALIGN_BEFORE_MS].
            val ahead = engine - pending
            return ahead in 0L..snapToleranceMs
        }
        // VLC/MPV can briefly report the requested timestamp before the decoder settles on its
        // preceding keyframe. Require two position polls worth of stable alignment before handing
        // over; an exact Media3 seek still becomes live without a visible step.
        return alignedAtMs?.let { now - it >= ACK_STABILITY_MS } == true
    }

    private fun observeAlignment(target: Long, engine: Long, now: Long, movedNow: Boolean) {
        // A normal player poll can arrive a little after the target has started playing.
        // Keyframe seeks land several seconds before the requested time; that is still this
        // seek. A position further back is the pre-seek clock and must not acknowledge it.
        // A sample that is behind the target and not moving is buffering, not a landing, so
        // it must not accumulate the stability window (that walked the OSD backward).
        val lower = (target - ALIGN_BEFORE_MS).coerceAtLeast(0L)
        val upper = target.coerceAtMost(Long.MAX_VALUE - ALIGN_AFTER_MS) + ALIGN_AFTER_MS
        val inWindow = engine in lower..upper && !isOldClock(target, engine, now)
        if (inWindow && (engine >= target || movedNow)) {
            alignedAtMs = alignedAtMs ?: now
        } else {
            alignedAtMs = null
        }
    }

    /**
     * A short forward seek (under [ALIGN_BEFORE_MS]) puts the still running pre-seek clock inside
     * the behind-window. A sample that is exactly where that old clock would be by now is not a
     * landing; a real landing (target or its keyframe) is a jump away from it.
     */
    private fun isOldClock(target: Long, engine: Long, now: Long): Boolean {
        val start = requestEngineMs
        if (start < 0L || target <= start || target - start >= ALIGN_BEFORE_MS) return false
        val projected = start + (now - lastRequestAtMs)
        return engine <= target && kotlin.math.abs(engine - projected) <= CONTINUITY_MS
    }

    /**
     * Two in-window samples can be the optimistic target VLC/MPV report while the seek is still
     * in flight. If the next poll falls behind that target by more than [ALIGN_BEFORE_MS], the
     * ack was not a landing: put the target back so the OSD does not jump to the old keyframe.
     */
    private fun recoverFromOptimisticAck(engine: Long) {
        val acknowledged = optimisticAckTargetMs ?: return
        val committed = optimisticAckCommittedMs
        val stopped = optimisticAckStopped
        optimisticAckTargetMs = null
        optimisticAckCommittedMs = null
        optimisticAckStopped = false
        // On a stopped clock (paused) VLC/MPV answer with the target and then drop to the keyframe
        // 1..5 s before it. Nothing is playing towards the target, so any drop of more than the
        // snap tolerance's poll jitter is that keyframe, not a landing.
        val lower = if (stopped) (acknowledged - STOPPED_DROP_MS).coerceAtLeast(0L)
        else (acknowledged - ALIGN_BEFORE_MS).coerceAtLeast(0L)
        if (engine < lower) {
            pendingTargetMs = acknowledged
            lastCommittedTargetMs = committed
            alignedAtMs = null
        }
    }

    fun consumeCommit(now: Long = nowMs(), force: Boolean = false): Long? {
        val target = pendingTargetMs ?: return null
        if (target == lastCommittedTargetMs) return null
        val firstInBurst = lastCommittedTargetMs == null
        if (!force && !firstInBurst && now - lastRequestAtMs < debounceMs) return null
        lastCommittedTargetMs = target
        return target
    }

    companion object {
        const val DEBOUNCE_MS = 180L
        const val SNAP_TOLERANCE_MS = 2_500L
        const val HOLD_MS = 700L
        const val IDLE_MS = 1_000L
        /** Behind-window for a moving landing. Several seconds covers a normal keyframe; a stale start does not. */
        const val ALIGN_BEFORE_MS = 5_000L
        const val ALIGN_AFTER_MS = 1_000L
        const val ACK_STABILITY_MS = 500L
        /** Distance from the projected pre-seek clock that still counts as that old clock. */
        const val CONTINUITY_MS = 400L
        /** A stopped clock dropping further than this after its ack fell back to a keyframe. */
        const val STOPPED_DROP_MS = 250L
    }
}
