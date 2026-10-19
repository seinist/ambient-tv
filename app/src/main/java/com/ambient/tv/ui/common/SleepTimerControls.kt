package com.ambient.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.ui.player.SleepTimerConfig
import com.ambient.tv.ui.player.SleepTimerKind
import com.ambient.tv.ui.player.pauseSleepTimerMinutes

internal fun sleepDurations(kind: SleepTimerKind): List<Int> = when (kind) {
    SleepTimerKind.PAUSE_INACTIVITY -> pauseSleepTimerMinutes
    SleepTimerKind.USER_INACTIVITY -> listOf(30, 60, 90, 120)
    SleepTimerKind.DURATION -> listOf(15, 30, 45, 60, 90)
    else -> emptyList()
}

internal fun sleepConfig(kind: SleepTimerKind, minutes: Int, now: Long): SleepTimerConfig =
    SleepTimerConfig(kind, minutes, if (kind == SleepTimerKind.DURATION) now + minutes * 60_000L else null)

@Composable
internal fun SleepTimerControls(config: SleepTimerConfig, isLive: Boolean, change: (SleepTimerConfig) -> Unit) {
    // CLOCK_TIME: only the daily stop time runs; the user's own timer is off.
    val userKind = if (config.kind == SleepTimerKind.CLOCK_TIME) SleepTimerKind.OFF else config.kind
    val kinds = buildList {
        add(SleepTimerKind.OFF to stringResource(R.string.player_sleep_off))
        add(SleepTimerKind.DURATION to stringResource(R.string.sleep_trigger_duration))
        if (!isLive) add(SleepTimerKind.END_OF_ITEM to stringResource(R.string.player_sleep_end_item))
        add(SleepTimerKind.PAUSE_INACTIVITY to stringResource(R.string.sleep_trigger_pause))
        add(SleepTimerKind.USER_INACTIVITY to stringResource(R.string.sleep_trigger_input))
    }
    PlayerDropUpSelector(
        label = stringResource(R.string.sleep_trigger),
        value = kinds.firstOrNull { it.first == userKind }?.second ?: stringResource(R.string.player_sleep_off),
        options = kinds.map { it.first.name to it.second },
        selectedKey = userKind.name,
        onSelected = { key ->
            val kind = SleepTimerKind.valueOf(key)
            if (kind != userKind) change(sleepConfig(kind, sleepDurations(kind).firstOrNull() ?: 0, android.os.SystemClock.elapsedRealtime()))
        },
    )
    val durations = sleepDurations(config.kind)
    if (durations.isNotEmpty()) PlayerDropUpSelector(
        label = stringResource(R.string.player_sleep_after_time),
        value = "${config.minutes} min",
        options = durations.map { it.toString() to "$it min" },
        selectedKey = config.minutes.toString(),
        onSelected = { change(sleepConfig(config.kind, it.toInt(), android.os.SystemClock.elapsedRealtime())) },
    )
    when (config.kind) {
        SleepTimerKind.PAUSE_INACTIVITY -> RightPanelSectionTitle(stringResource(R.string.player_sleep_pause_hint))
        SleepTimerKind.USER_INACTIVITY -> RightPanelSectionTitle(stringResource(R.string.player_sleep_input_hint))
        else -> Unit
    }
    // Daily stop time (settings) runs next to the timer above; it can be skipped for today.
    config.alsoStopRealtimeMs?.let {
        RightPanelSectionTitle(stringResource(R.string.player_sleep_clock_summary, com.ambient.tv.ui.player.sleepClockLabel(it)))
        PlayerDropUpSelector(
            label = stringResource(R.string.player_sleep_daily_stop),
            value = stringResource(R.string.player_on),
            options = listOf("on" to stringResource(R.string.player_on), "skip" to stringResource(R.string.player_sleep_skip_today)),
            selectedKey = "on",
            onSelected = { key -> if (key == "skip") com.ambient.tv.ui.player.SleepSession.skipDailyStopToday() },
        )
    }
}
