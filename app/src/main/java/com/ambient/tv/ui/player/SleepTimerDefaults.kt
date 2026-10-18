package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AppPreferences
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

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

/** A start up to this long after the daily stop time is "still the same night". */
internal const val NIGHT_WINDOW_MS = 6 * 3_600_000L
internal const val NIGHT_FALLBACK_MINUTES = 30

private fun dailyStopAt(nowEpochMs: Long, zone: ZoneId, minuteOfDay: Int, dayOffset: Long): ZonedDateTime {
    val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
    // ZonedDateTime.of moves a time inside a DST gap forward and takes the earlier offset in an overlap.
    return ZonedDateTime.of(today.plusDays(dayOffset), LocalTime.of(minuteOfDay / 60, minuteOfDay % 60), zone)
}

/** Next wall-clock occurrence of [minuteOfDay] strictly after [nowEpochMs] in [zone]. */
internal fun nextDailyStopEpochMs(nowEpochMs: Long, zone: ZoneId, minuteOfDay: Int): Long {
    val today = dailyStopAt(nowEpochMs, zone, minuteOfDay, 0).toInstant().toEpochMilli()
    return if (today > nowEpochMs) today else dailyStopAt(nowEpochMs, zone, minuteOfDay, 1).toInstant().toEpochMilli()
}

/** Last occurrence of [minuteOfDay] at or before [nowEpochMs]. */
internal fun previousDailyStopEpochMs(nowEpochMs: Long, zone: ZoneId, minuteOfDay: Int): Long {
    val today = dailyStopAt(nowEpochMs, zone, minuteOfDay, 0).toInstant().toEpochMilli()
    return if (today <= nowEpochMs) today else dailyStopAt(nowEpochMs, zone, minuteOfDay, -1).toInstant().toEpochMilli()
}

/**
 * Stop moment (epoch ms) of the daily stop time for a session starting at [nowEpochMs]; null when off.
 * Night window: a start within [NIGHT_WINDOW_MS] after the stop time gets [fallbackMinutes] instead
 * of running until the next night.
 */
internal fun dailyStopEpochMs(nowEpochMs: Long, zone: ZoneId, minuteOfDay: Int, fallbackMinutes: Int): Long? {
    if (minuteOfDay !in 0 until 1440) return null
    if (nowEpochMs - previousDailyStopEpochMs(nowEpochMs, zone, minuteOfDay) < NIGHT_WINDOW_MS) {
        return nowEpochMs + fallbackMinutes * 60_000L
    }
    return nextDailyStopEpochMs(nowEpochMs, zone, minuteOfDay)
}

/** Night-window length: the automatic duration if one is set, otherwise 30 min. */
internal fun nightFallbackMinutes(autoStartKey: String): Int =
    autoStartKey.toIntOrNull()?.takeIf { it > 0 } ?: NIGHT_FALLBACK_MINUTES

/** Automatic timer plus daily stop time ([stopRealtimeMs]): the earlier stop wins. */
internal fun withDailyStop(auto: SleepTimerConfig, stopRealtimeMs: Long?): SleepTimerConfig = when {
    stopRealtimeMs == null -> auto
    auto.kind == SleepTimerKind.OFF -> SleepTimerConfig(SleepTimerKind.CLOCK_TIME, 0, stopRealtimeMs)
    auto.kind == SleepTimerKind.DURATION ->
        if ((auto.deadlineRealtimeMs ?: Long.MAX_VALUE) <= stopRealtimeMs) auto
        else SleepTimerConfig(SleepTimerKind.CLOCK_TIME, 0, stopRealtimeMs)
    else -> auto.copy(alsoStopRealtimeMs = stopRealtimeMs)
}

/** Wall-clock "HH:mm" of an elapsed-realtime stop moment, for timer summaries. */
internal fun sleepClockLabel(deadlineRealtimeMs: Long?, nowRealtimeMs: Long = android.os.SystemClock.elapsedRealtime(),
                             nowEpochMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(nowEpochMs + ((deadlineRealtimeMs ?: nowRealtimeMs) - nowRealtimeMs)).atZone(zone)
    return "%02d:%02d".format(at.hour, at.minute)
}
