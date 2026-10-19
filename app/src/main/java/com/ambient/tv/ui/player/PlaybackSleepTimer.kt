package com.ambient.tv.ui.player

/**
 * Elapsed-realtime policy, independent of UI lifetime, renderer and wall-clock changes.
 * Two parts: the user's timer ([arm]: by hand or "start automatically") and the daily stop time
 * ([setDailyStop], kept up to date by [SleepSession] from the wall clock). Whichever comes first stops.
 */
class PlaybackSleepTimer {
    /** The user's own timer; never CLOCK_TIME. */
    private var user = SleepTimerConfig()
    private var dailyStopMs: Long? = null
    val config: SleepTimerConfig get() = combineSleepTimer(user, dailyStopMs)
    private var lastInputMs = 0L
    private var inactiveSinceMs: Long? = null
    /** Deadline already announced by the pre-warning; one notice per deadline. */
    private var warnedDeadlineMs: Long? = null
    /** The last expiry came from the daily stop time. */
    var stoppedByDailyStop = false
        private set

    fun arm(value: SleepTimerConfig, nowMs: Long): SleepTimerConfig {
        val minutes = value.minutes.coerceIn(1, 1440)
        user = when (value.kind) {
            SleepTimerKind.DURATION -> SleepTimerConfig(SleepTimerKind.DURATION, minutes,
                value.deadlineRealtimeMs ?: nowMs + minutes * 60_000L)
            SleepTimerKind.PAUSE_INACTIVITY, SleepTimerKind.USER_INACTIVITY -> SleepTimerConfig(value.kind, minutes)
            SleepTimerKind.END_OF_ITEM -> SleepTimerConfig(SleepTimerKind.END_OF_ITEM)
            SleepTimerKind.OFF, SleepTimerKind.CLOCK_TIME -> SleepTimerConfig()
        }
        lastInputMs = nowMs
        inactiveSinceMs = null
        warnedDeadlineMs = null
        stoppedByDailyStop = false
        return config
    }

    /** The user's timer alone, without the daily stop time. */
    val userConfig: SleepTimerConfig get() = user

    fun setDailyStop(realtimeMs: Long?) {
        dailyStopMs = realtimeMs
    }

    fun userInput(nowMs: Long) { lastInputMs = nowMs }

    /** Timed stops only: the deadline, once [warningMs] or less remain and it was not announced yet. */
    fun warningDue(nowMs: Long, warningMs: Long): Long? {
        if (warningMs <= 0L) return null
        val deadline = config.timedDeadlineMs ?: return null
        if (deadline == warnedDeadlineMs || nowMs >= deadline || deadline - nowMs > warningMs) return null
        warnedDeadlineMs = deadline
        return deadline
    }

    /** The earliest timed stop is the daily stop time (so "+30 min" must move that one). */
    fun dailyStopIsNext(): Boolean {
        val daily = dailyStopMs ?: return false
        val duration = user.deadlineRealtimeMs?.takeIf { user.kind == SleepTimerKind.DURATION }
        return duration == null || daily <= duration
    }

    /** "+30 min" for the user's timed stop; the rest of the timer stays. */
    fun extend(minutes: Int, nowMs: Long): SleepTimerConfig {
        if (user.kind != SleepTimerKind.DURATION) return config
        val base = maxOf(user.deadlineRealtimeMs ?: nowMs, nowMs)
        user = user.copy(deadlineRealtimeMs = base + minutes * 60_000L)
        return config
    }

    fun expired(nowMs: Long, status: PlaybackStatus, wantsToPlay: Boolean): Boolean {
        // A renderer buffering with play intent is active playback, even before its first frame.
        val inactive = !status.playing && (!wantsToPlay || status.ended || status.error != null)
        inactiveSinceMs = if (inactive) inactiveSinceMs ?: nowMs else null
        stoppedByDailyStop = dailyStopMs?.let { nowMs >= it } == true
        if (stoppedByDailyStop) return true
        return when (user.kind) {
            SleepTimerKind.OFF, SleepTimerKind.CLOCK_TIME -> false
            SleepTimerKind.DURATION -> nowMs >= (user.deadlineRealtimeMs ?: Long.MAX_VALUE)
            SleepTimerKind.END_OF_ITEM -> status.ended && status.error == null
            SleepTimerKind.PAUSE_INACTIVITY -> inactiveSinceMs?.let { nowMs - it >= user.minutes * 60_000L } == true
            SleepTimerKind.USER_INACTIVITY -> nowMs - lastInputMs >= user.minutes * 60_000L
        }
    }

    /**
     * Fixed-duration, daily-stop and no-input timers pause first and ask (like SmartTube); stopping
     * without a question would end a session someone may still be watching. Pause and end-of-item
     * timers already have nothing playing, so they stop directly.
     */
    fun asksBeforeStop(): Boolean = stoppedByDailyStop || user.kind == SleepTimerKind.DURATION || user.kind == SleepTimerKind.USER_INACTIVITY

    companion object {
        /** How long the paused prompt waits before the timer finishes the stop. */
        const val PROMPT_MS = 60_000L
    }
}

/**
 * Shown/compared form: the user's timer plus the daily stop time in [SleepTimerConfig.alsoStopRealtimeMs];
 * with no user timer the daily stop alone is CLOCK_TIME.
 */
internal fun combineSleepTimer(user: SleepTimerConfig, dailyStopMs: Long?): SleepTimerConfig = when {
    dailyStopMs == null -> user.copy(alsoStopRealtimeMs = null)
    user.kind == SleepTimerKind.OFF || user.kind == SleepTimerKind.CLOCK_TIME -> SleepTimerConfig(SleepTimerKind.CLOCK_TIME, alsoStopRealtimeMs = dailyStopMs)
    else -> user.copy(alsoStopRealtimeMs = dailyStopMs)
}
