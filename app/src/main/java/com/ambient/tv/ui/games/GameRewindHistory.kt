package com.ambient.tv.ui.games

/** A bounded UI snapshot, never the rewind ring itself. A single current frame is not history. */
internal data class GameRewindHistory(val states: Int = 0, val framesPerSecond: Double = 60.0) {
    val available: Boolean get() = states >= 2
    /** Approximate: fast-forward can capture several emulated frames between stored states. */
    val seconds: Float get() = if (!available) 0f else
        ((states - 1).coerceAtMost(4_800) / framesPerSecond.takeIf { it.isFinite() && it in 1.0..240.0 }
            .let { it ?: 60.0 }).toFloat()
}
