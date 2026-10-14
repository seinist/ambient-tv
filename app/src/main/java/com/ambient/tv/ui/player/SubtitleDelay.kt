package com.ambient.tv.ui.player

/**
 * Players that can shift subtitle timing (MPV `sub-delay`, VLC SPU delay). Media3 has no such
 * control, so the subtitle panel hides the row there.
 */
interface SubtitleDelayTarget {
    /** Positive: subtitles later. Negative: earlier. */
    val subtitleDelayMs: Int
    fun setSubtitleDelay(delayMs: Int)

    companion object {
        const val LIMIT_MS = 10_000
    }
}
