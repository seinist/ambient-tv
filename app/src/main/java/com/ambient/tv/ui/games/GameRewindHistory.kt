package com.ambient.tv.ui.games

internal const val GAME_REWIND_SECONDS = 20

/** A bounded UI snapshot; native publishes only frames with an earlier restorable state. */
internal data class GameRewindHistory(val historyFrames: Long = 0, val framesPerSecond: Double = 60.0) {
    val available: Boolean get() = historyFrames > 0
    /** Native frame distances include fast-forward and any gaps between captured states. */
    val seconds: Float get() = if (!available) 0f else
        (historyFrames.toDouble() / (framesPerSecond.takeIf { it.isFinite() && it in 1.0..240.0 } ?: 60.0))
            .coerceAtMost(GAME_REWIND_SECONDS.toDouble()).toFloat()
}

/** Closer to now than this, the paused timeline counts as live. */
internal const val GAME_REWIND_LIVE_TOLERANCE_MS = 50L

/** A timeline seek by [deltaMs] (negative = further back) from [currentBackMs] before now. */
internal fun gameRewindSeekTarget(currentBackMs: Long, deltaMs: Long, historyMs: Long): Long =
    (currentBackMs - deltaMs).coerceIn(0L, historyMs.coerceAtLeast(0L))

/** Emulated frames for a distance before now; the native seek snaps to a stored state. */
internal fun gameRewindSeekFrames(backMs: Long, framesPerSecond: Double): Long {
    val fps = framesPerSecond.takeIf { it.isFinite() && it in 1.0..240.0 } ?: 60.0
    return kotlin.math.round(backMs.coerceAtLeast(0L) * fps / 1000.0).toLong()
}
