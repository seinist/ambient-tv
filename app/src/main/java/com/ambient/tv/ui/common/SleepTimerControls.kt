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
    val kinds = buildList {
        add(SleepTimerKind.OFF to stringResource(R.string.player_sleep_off))
        add(SleepTimerKind.DURATION to stringResource(R.string.sleep_trigger_duration))
        if (!isLive) add(SleepTimerKind.END_OF_ITEM to stringResource(R.string.player_sleep_end_item))
        add(SleepTimerKind.PAUSE_INACTIVITY to stringResource(R.string.sleep_trigger_pause))
        add(SleepTimerKind.USER_INACTIVITY to stringResource(R.string.sleep_trigger_input))
    }
    PlayerDropUpSelector(
        label = stringResource(R.string.sleep_trigger),
        value = kinds.firstOrNull { it.first == config.kind }?.second ?: stringResource(R.string.player_sleep_off),
        options = kinds.map { it.first.name to it.second },
        selectedKey = config.kind.name,
        onSelected = { key ->
            val kind = SleepTimerKind.valueOf(key)
            if (kind != config.kind) change(sleepConfig(kind, sleepDurations(kind).firstOrNull() ?: 0, android.os.SystemClock.elapsedRealtime()))
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
}
