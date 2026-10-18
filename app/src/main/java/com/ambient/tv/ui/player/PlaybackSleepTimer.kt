package com.ambient.tv.ui.player

/** Elapsed-realtime policy, independent of UI lifetime, renderer and wall-clock changes. */
class PlaybackSleepTimer {
    var config = SleepTimerConfig()
        private set
    private var lastInputMs = 0L
    private var inactiveSinceMs: Long? = null

    fun arm(value: SleepTimerConfig, nowMs: Long): SleepTimerConfig {
        val minutes = value.minutes.coerceIn(1, 1440)
        config = when (value.kind) {
            SleepTimerKind.DURATION -> value.copy(minutes = minutes,
                deadlineRealtimeMs = value.deadlineRealtimeMs ?: nowMs + minutes * 60_000L)
            SleepTimerKind.PAUSE_INACTIVITY, SleepTimerKind.USER_INACTIVITY -> value.copy(minutes = minutes, deadlineRealtimeMs = null)
            SleepTimerKind.CLOCK_TIME -> value.deadlineRealtimeMs?.let { value.copy(minutes = 0, alsoStopRealtimeMs = null) } ?: SleepTimerConfig()
            SleepTimerKind.END_OF_ITEM -> value.copy(minutes = 0, deadlineRealtimeMs = null)
            SleepTimerKind.OFF -> SleepTimerConfig()
        }
        if (config.kind == SleepTimerKind.DURATION) config = config.copy(alsoStopRealtimeMs = null)
        stoppedByClock = false
        lastInputMs = nowMs
        inactiveSinceMs = null
        warnedDeadlineMs = null
        return config
    }

    fun userInput(nowMs: Long) { lastInputMs = nowMs }

    /** Deadline already announced by the pre-warning; one notice per deadline. */
    private var warnedDeadlineMs: Long? = null

    /** Timed stops only: the deadline, once [warningMs] or less remain and it was not announced yet. */
    fun warningDue(nowMs: Long, warningMs: Long): Long? {
        if (warningMs <= 0L) return null
        val deadline = config.timedDeadlineMs ?: return null
        if (deadline == warnedDeadlineMs || nowMs >= deadline || deadline - nowMs > warningMs) return null
        warnedDeadlineMs = deadline
        return deadline
    }

    /** "+30 min" from the pre-warning: moves a timed stop, the rest of the timer stays. */
    fun extend(minutes: Int, nowMs: Long): SleepTimerConfig {
        val deadline = config.timedDeadlineMs ?: return config
        val moved = maxOf(deadline, nowMs) + minutes * 60_000L
        config = when (config.kind) {
            SleepTimerKind.DURATION, SleepTimerKind.CLOCK_TIME -> config.copy(deadlineRealtimeMs = moved)
            else -> config.copy(alsoStopRealtimeMs = moved)
        }
        return config
    }

    fun expired(nowMs: Long, status: PlaybackStatus, wantsToPlay: Boolean): Boolean {
        // A renderer buffering with play intent is active playback, even before its first frame.
        val inactive = !status.playing && (!wantsToPlay || status.ended || status.error != null)
        inactiveSinceMs = if (inactive) inactiveSinceMs ?: nowMs else null
        stoppedByClock = config.alsoStopRealtimeMs?.let { nowMs >= it } == true
        if (stoppedByClock) return true
        return when (config.kind) {
            SleepTimerKind.OFF -> false
            SleepTimerKind.DURATION, SleepTimerKind.CLOCK_TIME -> nowMs >= (config.deadlineRealtimeMs ?: Long.MAX_VALUE)
            SleepTimerKind.END_OF_ITEM -> status.ended && status.error == null
            SleepTimerKind.PAUSE_INACTIVITY -> inactiveSinceMs?.let { nowMs - it >= config.minutes * 60_000L } == true
            SleepTimerKind.USER_INACTIVITY -> nowMs - lastInputMs >= config.minutes * 60_000L
        }
    }

    /**
     * Fixed-duration and no-input timers pause first and ask (like SmartTube); stopping without a
     * question would end a session someone may still be watching. Pause and end-of-item timers
     * already have nothing playing, so they stop directly.
     */
    fun asksBeforeStop(): Boolean = stoppedByClock || config.kind in setOf(SleepTimerKind.DURATION, SleepTimerKind.CLOCK_TIME, SleepTimerKind.USER_INACTIVITY)

    /** The last expiry came from the daily stop time next to an untimed timer. */
    private var stoppedByClock = false

    companion object {
        /** How long the paused prompt waits before the timer finishes the stop. */
        const val PROMPT_MS = 60_000L
    }
}
