package com.ambient.tv.ui.intro

import kotlinx.coroutines.withTimeoutOrNull

/** The intro has its own deadline; connecting and waking servers never extend it. */
internal object IntroTiming {
    const val MAX_DURATION_MS = 5_000L
    const val FADE_DURATION_MS = 700
    const val QUICK_FADE_DURATION_MS = 220
    const val STATUS_DELAY_MS = 700L
    const val STATUS_FADE_DURATION_MS = 180
    const val FADE_START_MS = MAX_DURATION_MS - FADE_DURATION_MS

    fun remainingMillis(startedAtMillis: Long, nowMillis: Long): Long =
        (MAX_DURATION_MS - (nowMillis - startedAtMillis).coerceAtLeast(0L)).coerceAtLeast(0L)

    /** Existing styles finish their reveals by drawing second six, before the exit fade. */
    fun animationSeconds(elapsedMillis: Long): Float =
        elapsedMillis.coerceAtLeast(0L).toFloat() * 6f / FADE_START_MS
}

/**
 * Only waits for a presentation skip or its deadline, never for a connection job. The outer
 * deadline also bounds a slow/paused animation clock and a system animation-duration override.
 * Caller cancellation propagates so disposing the overlay cannot finish a replacement intro.
 */
internal suspend fun runIntroTimeline(
    startedAtMillis: Long,
    nowMillis: () -> Long,
    still: Boolean,
    awaitSkip: suspend () -> Unit,
    fadeOut: suspend (durationMillis: Int) -> Unit,
) {
    val remaining = IntroTiming.remainingMillis(startedAtMillis, nowMillis())
    if (remaining == 0L) return
    withTimeoutOrNull(remaining) {
        val untilFade = (IntroTiming.FADE_START_MS -
            (nowMillis() - startedAtMillis).coerceAtLeast(0L)).coerceAtLeast(0L)
        val skipped = if (untilFade > 0L) {
            withTimeoutOrNull(untilFade) { awaitSkip(); true } ?: false
        } else false
        val fadeDuration = minOf(
            if (skipped || still) IntroTiming.QUICK_FADE_DURATION_MS else IntroTiming.FADE_DURATION_MS,
            IntroTiming.remainingMillis(startedAtMillis, nowMillis()).toInt(),
        )
        if (fadeDuration > 0) fadeOut(fadeDuration)
    }
}
