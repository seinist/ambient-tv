package com.ambient.tv.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ambient.tv.data.local.AppPreferences
import java.time.ZoneId

/** What the sleep timer controls: the shared video/audio session or Spotify. Main thread. */
interface SleepTarget {
    fun sleepStatus(): PlaybackStatus
    fun sleepWantsToPlay(): Boolean
    fun sleepPause()
    fun sleepPlay()
    /** Ends playback (timer ran out or "stop now" in the prompt). */
    fun sleepStop()
    /** Device standby is never allowed for phones, tablets or automotive. */
    fun sleepAllowsDeviceStandby(): Boolean = false
    /** TV only, called after stopping playback if [sleepAllowsDeviceStandby] is true. */
    fun sleepEnterStandby() {}
}

/** Clocks behind the sleep timer; replaced in tests. */
internal interface SleepClock {
    fun realtimeMs(): Long
    fun epochMs(): Long
    fun zone(): ZoneId
}

private object SystemSleepClock : SleepClock {
    override fun realtimeMs() = android.os.SystemClock.elapsedRealtime()
    override fun epochMs() = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/**
 * The listening session's sleep timer, shared by every player (video renderers, live TV, YouTube,
 * Spotify). Holds the user's timer (manual or "start automatically"), the daily stop time, the
 * pre-warning and the pause-and-ask prompt. Only the current [target] drives and is stopped by it.
 */
object SleepSession {
    internal var clock: SleepClock = SystemSleepClock
    private val timer = PlaybackSleepTimer()
    var config by mutableStateOf(SleepTimerConfig())
        private set
    /** Set while an expired timer holds playback paused and asks how to continue; elapsed-realtime deadline. */
    var promptDeadline by mutableStateOf<Long?>(null)
        private set
    /** Set while the non-pausing pre-warning is shown; the elapsed-realtime stop deadline. */
    var warningDeadline by mutableStateOf<Long?>(null)
        private set
    /** Timer expired: allow Android TV to sleep even if the app screensaver is enabled. */
    var standbyRequested by mutableStateOf(false)
        private set
    fun cancelStandbyRequest() { standbyRequested = false }
    private var daily by mutableStateOf<DailyStopPlan?>(null)
    /** The daily stop time applies to this session; the player menu can skip it for today. */
    val dailyStopActive: Boolean get() = daily != null
    var target: SleepTarget? = null
        private set
    private var prefs: AppPreferences? = null
    private val warningMs: Long get() = (prefs?.sleepWarningMinutes ?: 0) * 60_000L
    /** The user's timer came from "start automatically", not from the player menu. */
    private var auto = false
    /** A stop that kept the timer (internal retry/error); the next start continues the session. */
    private var keptAtMs: Long? = null
    /** Daily stop setting the current plan was made from; a change in Settings re-plans at once. */
    private var dailyMinuteInUse = AppPreferences.SLEEP_DAILY_STOP_OFF

    /**
     * Playback starts on [target]. A new session (other target, nothing playing before, no kept
     * timer) arms the automatic timer and the daily stop time; zapping and next episode continue.
     */
    fun begin(target: SleepTarget, preferences: () -> AppPreferences?, isLive: Boolean, hadPlayer: Boolean, replacingSource: Boolean) {
        standbyRequested = false
        val now = clock.realtimeMs()
        val kept = keptAtMs
        keptAtMs = null
        val sameTarget = target === this.target
        this.target = target
        if (sameTarget && !sleepSessionStarts(hadPlayer, replacingSource, kept, now)) return
        reset()
        val p = runCatching(preferences).getOrNull() ?: return
        prefs = p
        dailyMinuteInUse = p.sleepDailyStopMinute
        daily = dailyStopPlan(clock.epochMs(), clock.zone(), dailyMinuteInUse, nightFallbackMinutes(p.sleepAutoStartKey), now)
        timer.arm(autoSleepTimerConfig(p.sleepAutoStartKey, isLive, now), now)
        auto = timer.userConfig.kind != SleepTimerKind.OFF
        syncDaily(now, force = true)
    }

    /** Playback on [target] ended. [keep]: an internal stop followed by a new start (see [KEEP_TIMER_WINDOW_MS]). */
    fun end(target: SleepTarget, keep: Boolean = false) {
        if (target !== this.target) return
        promptDeadline = null
        warningDeadline = null
        if (keep) {
            keptAtMs = clock.realtimeMs()
            return
        }
        reset()
        this.target = null
    }

    private fun reset() {
        promptDeadline = null
        warningDeadline = null
        keptAtMs = null
        daily = null
        dailyMinuteInUse = AppPreferences.SLEEP_DAILY_STOP_OFF
        auto = false
        timer.setDailyStop(null)
        timer.arm(SleepTimerConfig(), clock.realtimeMs())
        config = timer.config
    }

    /** The user's timer from the player menu. "Off" ends only that one; the daily stop time stays. */
    fun set(value: SleepTimerConfig) {
        standbyRequested = false
        promptDeadline = null
        warningDeadline = null
        auto = false
        timer.arm(value, clock.realtimeMs())
        publish()
    }

    /** "Heute keine Stoppzeit": the daily stop time is skipped for this session. */
    fun skipDailyStopToday() {
        daily = null
        timer.setDailyStop(null)
        warningDeadline = null
        publish()
    }

    /** Another title starts in the same session. */
    fun itemChanged(target: SleepTarget) {
        if (target !== this.target) return
        val user = timer.userConfig
        if (user.kind != SleepTimerKind.END_OF_ITEM) return
        val wasAuto = auto
        set(sleepTimerForNextItem(user, wasAuto))
        auto = wasAuto
    }

    /** "OK: +30 min" on the pre-warning or "Extend" in a notification: moves the next timed stop. */
    fun extend() {
        val now = clock.realtimeMs()
        val plan = daily
        if (plan != null && timer.dailyStopIsNext()) {
            daily = plan.extended(SLEEP_EXTEND_MINUTES, now)
            syncDaily(now, force = true)
        } else timer.extend(SLEEP_EXTEND_MINUTES, now)
        warningDeadline = null
        timer.userInput(now)
        publish()
    }

    fun userInput() = timer.userInput(clock.realtimeMs())

    /** Answer to the prompt: resume; [rearm] starts the user's timer again. A passed daily stop does not come back. */
    fun continueAfter(rearm: Boolean) {
        val byDaily = timer.stoppedByDailyStop
        val user = timer.userConfig
        promptDeadline = null
        warningDeadline = null
        if (byDaily) {
            daily = null
            timer.setDailyStop(null)
            if (rearm) set(user.copy(deadlineRealtimeMs = null))
            else if (user.kind == SleepTimerKind.DURATION &&
                user.deadlineRealtimeMs?.let { it <= clock.realtimeMs() } == true) {
                // Both deadlines may expire together, or the duration expires while the
                // daily-stop prompt is open. Continuing must acknowledge both.
                set(SleepTimerConfig())
            }
        } else set(if (rearm) user.copy(deadlineRealtimeMs = null) else SleepTimerConfig())
        publish()
        target?.sleepPlay()
        userInput()
    }

    /** "Stop now" in the prompt must perform the same timer-triggered standby as expiry. */
    fun stopNow() {
        if (promptDeadline != null) stopForSleep()
        else target?.sleepStop()
    }

    /** Once a second from the target's service loop. */
    fun tick(target: SleepTarget) {
        if (target !== this.target) return
        val now = clock.realtimeMs()
        prefs?.let { p ->
            val minute = p.sleepDailyStopMinute
            if (minute != dailyMinuteInUse && promptDeadline == null) {
                dailyMinuteInUse = minute
                daily = dailyStopPlan(clock.epochMs(), clock.zone(), minute, nightFallbackMinutes(p.sleepAutoStartKey), now)
                warningDeadline = null
                syncDaily(now, force = true)
            }
        }
        syncDaily(now)
        val status = target.sleepStatus()
        val wants = target.sleepWantsToPlay()
        val prompt = promptDeadline
        if (prompt != null) {
            // Play pressed outside the dialog (player, media key, notification) counts as
            // "keep watching"; no answer at all lets the sleep timer go ahead.
            // The first moments are skipped: some renderers report the pause a tick late.
            val settled = now - (prompt - PlaybackSleepTimer.PROMPT_MS) >= 1_500L
            if (settled && wants) continueAfter(rearm = false)
            else if (now >= prompt) stopForSleep()
            return
        }
        timer.warningDue(now, warningMs)?.let { warningDeadline = it }
        warningDeadline?.let { warned ->
            if (now >= warned || timer.config.timedDeadlineMs != warned) warningDeadline = null
        }
        if (timer.expired(now, status, wants)) {
            if (timer.asksBeforeStop()) {
                target.sleepPause()
                promptDeadline = now + PlaybackSleepTimer.PROMPT_MS
            } else stopForSleep()
        }
        publish()
    }

    /**
     * Daily stop time → elapsed realtime. A clock or time-zone change moves it; small differences
     * between the two clocks are ignored, so the warning keeps one deadline.
     */
    private fun syncDaily(now: Long, force: Boolean = false) {
        val target = daily?.realtimeMs(now, clock.epochMs(), clock.zone())
        val current = timer.config.alsoStopRealtimeMs
        if (force || target == null || current == null || kotlin.math.abs(target - current) > DAILY_STOP_RESYNC_MS) {
            timer.setDailyStop(target)
            publish()
        }
    }

    private fun publish() {
        val value = timer.config
        if (value != config) config = value
    }

    private fun stopForSleep() {
        val ending = target ?: return
        ending.sleepStop()
        // Visible after the player closes, and in the history for whoever finds the TV stopped.
        runCatching {
            com.ambient.tv.notifications.AppNotifications.post(com.ambient.tv.notifications.AppNotice(
                "playback:sleep", com.ambient.tv.R.string.notice_sleep_stopped,
                kind = com.ambient.tv.notifications.NoticeKind.PLAYBACK,
            ))
        }
        // A phone timer must stop audio/video only. No lock, power or standby request.
        if (ending.sleepAllowsDeviceStandby()) {
            standbyRequested = true
            ending.sleepEnterStandby()
        }
    }

    /** Tests: back to a clean state. */
    internal fun resetForTest(clock: SleepClock) {
        standbyRequested = false
        this.clock = clock
        target = null
        prefs = null
        reset()
    }

    private const val DAILY_STOP_RESYNC_MS = 2_000L
}
