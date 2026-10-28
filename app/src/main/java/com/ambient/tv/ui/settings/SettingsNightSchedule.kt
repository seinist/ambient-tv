package com.ambient.tv.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.data.local.NightSchedule
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.RightPanelValueSlider
import com.ambient.tv.ui.common.TvSettingRow

/** Night mode by the clock: a window in which every player compresses dynamics and lifts dialogue. */
internal fun LazyListScope.nightScheduleItems(
    schedule: NightSchedule,
    onChange: ((NightSchedule) -> NightSchedule) -> Unit,
) {
    item {
        SettingsGroup(title = stringResource(R.string.night_schedule_title), subtitle = stringResource(R.string.night_schedule_hint)) {
            TvSettingRow(
                title = stringResource(R.string.night_schedule_enabled),
                checked = schedule.enabled,
                onCheckedChange = { v -> onChange { it.copy(enabled = v) } },
            )
            if (schedule.enabled) {
                TimeChoice(R.string.night_schedule_start, schedule.startMinute) { v -> onChange { it.copy(startMinute = v) } }
                TimeChoice(R.string.night_schedule_end, schedule.endMinute) { v -> onChange { it.copy(endMinute = v) } }
                RightPanelValueSlider(
                    title = stringResource(R.string.night_schedule_dialogue),
                    value = schedule.dialogue,
                    valueRange = 0..6,
                    step = 1,
                    valueLabel = if (schedule.dialogue == 0) stringResource(R.string.night_schedule_dialogue_off) else schedule.dialogue.toString(),
                    onValueChange = { v -> onChange { it.copy(dialogue = v) } },
                )
            }
        }
    }
}

@Composable
private fun TimeChoice(labelRes: Int, minute: Int, onSelected: (Int) -> Unit) {
    // Half-hour steps; a value saved off the grid (backup, older build) stays selectable.
    val minutes = (nightScheduleTimes() + minute).distinct().sorted()
    PlayerDropUpSelector(
        label = stringResource(labelRes),
        value = formatMinuteOfDay(minute),
        options = minutes.map { it.toString() to formatMinuteOfDay(it) },
        selectedKey = minute.toString(),
        onSelected = { key -> key.toIntOrNull()?.let(onSelected) },
    )
}

internal fun nightScheduleTimes(): List<Int> = (0 until NightSchedule.MINUTES_PER_DAY step 30).toList()
