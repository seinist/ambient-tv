package com.ambient.tv.ui.settings

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.livetv.LiveTvReminderScheduler
import com.ambient.tv.livetv.LiveTvReminderStore
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvSettingRow
import java.text.DateFormat
import java.util.Date

/** Same controls and management list regardless of the originating system. */
@Composable
internal fun ProgrammeReminderSettings(youtube: Boolean) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { LiveTvReminderStore(context) }
    val reminders by store.reminders.collectAsState()
    var lead by remember(youtube) { mutableIntStateOf(LiveTvReminderScheduler.leadMinutes(context, youtube)) }
    var auto by remember(youtube) { mutableStateOf(LiveTvReminderScheduler.autoSwitch(context, youtube)) }
    var manage by remember { mutableStateOf(false) }
    SettingsGroup(title = stringResource(if (youtube) R.string.programme_reminder_smarttube else R.string.settings_live_reminders)) {
        TvSettingRow(
            title = stringResource(R.string.reminder_auto_switch),
            body = stringResource(R.string.reminder_auto_switch_hint),
            checked = auto,
            onCheckedChange = { auto = it; LiveTvReminderScheduler.setAutoSwitch(context, it, youtube) },
        )
        SettingsSelect(
            label = stringResource(R.string.reminder_lead_title),
            options = listOf(0, 1, 2, 5, 10).map { minutes ->
                SettingsSelectOption(
                    text = if (minutes == 0) stringResource(R.string.reminder_lead_at_start) else stringResource(R.string.reminder_lead_minutes, minutes),
                    selected = lead == minutes,
                    onClick = { lead = minutes; LiveTvReminderScheduler.setLeadMinutes(context, minutes, youtube) },
                )
            },
        )
        SettingsSingleAction(text = stringResource(R.string.programme_reminder_manage), onClick = { store.refresh(); manage = true })
    }
    if (manage) AppDialog(
        title = stringResource(R.string.programme_reminder_manage),
        onDismiss = { manage = false },
        footer = { TvActionButton(stringResource(R.string.common_back), { manage = false }) },
    ) {
        val sorted = reminders.sortedBy { it.startEpochSeconds }
        if (sorted.isEmpty()) Text(stringResource(R.string.programme_reminder_empty))
        sorted.forEach { reminder ->
            key(reminder.id) {
                val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(reminder.startEpochSeconds * 1000))
                Text(reminder.title)
                Text(reminder.channelName + " · " + date)
                if (reminder.due) Text(stringResource(R.string.programme_reminder_status_due))
                TvActionButton(stringResource(R.string.reminder_remove), { store.remove(reminder.id) })
            }
        }
    }
}
