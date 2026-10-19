package com.ambient.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.ui.common.TvActionButton

/**
 * "Täglich stoppen um": Aus/An, then a free hh:mm with step buttons (hour, 5 minutes; D-pad left/
 * right moves between them, OK steps) and a few quick presets. Stored as minute of day.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun DailyStopTimePicker(minuteOfDay: Int, onChange: (Int) -> Unit) {
    val on = minuteOfDay in 0 until 1440
    var lastOn by rememberSaveable { mutableIntStateOf(if (on) minuteOfDay else DAILY_STOP_DEFAULT_MINUTE) }
    if (on) lastOn = minuteOfDay
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSelect(options = listOf(
            SettingsSelectOption(stringResource(R.string.settings_next_up_off), selected = !on,
                onClick = { onChange(AppPreferences.SLEEP_DAILY_STOP_OFF) }),
            SettingsSelectOption(stringResource(R.string.player_on), selected = on, onClick = { if (!on) onChange(lastOn) }),
        ))
        if (!on) return@Column
        // Wraps on narrow phones; on TV one line, left/right moves between the steps.
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TvActionButton(stringResource(R.string.settings_sleep_hour_minus), { onChange(stepMinuteOfDay(minuteOfDay, -60)) })
            TvActionButton(stringResource(R.string.settings_sleep_hour_plus), { onChange(stepMinuteOfDay(minuteOfDay, 60)) })
            Text(formatMinuteOfDay(minuteOfDay), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically).widthIn(min = 88.dp).padding(horizontal = 8.dp))
            TvActionButton(stringResource(R.string.settings_sleep_minutes_minus), { onChange(stepMinuteOfDay(minuteOfDay, -DAILY_STOP_STEP_MINUTES)) })
            TvActionButton(stringResource(R.string.settings_sleep_minutes_plus), { onChange(stepMinuteOfDay(minuteOfDay, DAILY_STOP_STEP_MINUTES)) })
        }
        Text(stringResource(R.string.settings_sleep_presets), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsSelect(options = DAILY_STOP_PRESETS.map { preset ->
            SettingsSelectOption(formatMinuteOfDay(preset), selected = minuteOfDay == preset, onClick = { onChange(preset) })
        })
    }
}

internal const val DAILY_STOP_DEFAULT_MINUTE = 60
internal const val DAILY_STOP_STEP_MINUTES = 5
/** Quick choices; any other time comes from the step buttons. */
internal val DAILY_STOP_PRESETS = listOf(22 * 60, 23 * 60, 0, 60, 2 * 60)

/** Steps around midnight in both directions. Minute steps (±5) snap a time off the 5-minute grid onto it. */
internal fun stepMinuteOfDay(minuteOfDay: Int, delta: Int): Int {
    if (delta % 60 == 0) return Math.floorMod(minuteOfDay + delta, 1440)
    val step = DAILY_STOP_STEP_MINUTES
    val off = Math.floorMod(minuteOfDay, step)
    val next = when {
        off == 0 -> minuteOfDay + delta
        delta > 0 -> minuteOfDay - off + step
        else -> minuteOfDay - off
    }
    return Math.floorMod(next, 1440)
}

internal fun formatMinuteOfDay(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
