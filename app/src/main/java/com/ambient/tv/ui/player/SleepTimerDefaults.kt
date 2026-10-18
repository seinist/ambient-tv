package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AppPreferences

/** Live TV has no "end of title": that default becomes a fixed hour there. */
internal const val LIVE_END_OF_ITEM_MINUTES = 60
internal const val SLEEP_INACTIVITY_DEFAULT_MINUTES = 60
internal const val SLEEP_EXTEND_MINUTES = 30

/**
 * Timer armed automatically when a new playback session starts (Settings → Playback).
 * Zapping, next episode and renderer switches continue the session and never re-arm it.
 */
internal fun autoSleepTimerConfig(key: String, isLive: Boolean, nowMs: Long): SleepTimerConfig = when (key) {
    AppPreferences.SLEEP_AUTO_START_END ->
        if (isLive) SleepTimerConfig(SleepTimerKind.DURATION, LIVE_END_OF_ITEM_MINUTES, nowMs + LIVE_END_OF_ITEM_MINUTES * 60_000L)
        else SleepTimerConfig(SleepTimerKind.END_OF_ITEM)
    AppPreferences.SLEEP_AUTO_START_INACTIVITY -> SleepTimerConfig(SleepTimerKind.USER_INACTIVITY, SLEEP_INACTIVITY_DEFAULT_MINUTES)
    else -> key.toIntOrNull()?.takeIf { it > 0 }?.let { SleepTimerConfig(SleepTimerKind.DURATION, it, nowMs + it * 60_000L) }
        ?: SleepTimerConfig()
}

/**
 * Whether an acquire starts a new session. A stop that kept the timer (internal retry, server
 * unreachable, failed renderer start) continues the session for [KEEP_TIMER_WINDOW_MS] only.
 */
internal fun sleepSessionStarts(hadPlayer: Boolean, replacingSource: Boolean, keptAtMs: Long?, nowMs: Long): Boolean =
    !hadPlayer && !replacingSource && (keptAtMs == null || nowMs - keptAtMs > KEEP_TIMER_WINDOW_MS)

internal const val KEEP_TIMER_WINDOW_MS = 2 * 60_000L
