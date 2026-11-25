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
