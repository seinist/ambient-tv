package com.ambient.tv.ui.settings

import com.ambient.tv.livetv.initializeTimeshiftStorage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.livetv.LiveTvReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LiveTvExtrasSettingsViewModel @Inject constructor(val preferences: AppPreferences) : ViewModel()

/** Settings › Playback: live TV timeshift buffer and reminder lead time; both apply right away. */
@Composable
internal fun LiveTvExtrasSettingsGroup(showRecording: Boolean = true, viewModel: LiveTvExtrasSettingsViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()) {
    val context = LocalContext.current
    var size by remember { mutableIntStateOf(viewModel.preferences.run { initializeTimeshiftStorage(context); liveTimeshiftMb }) }
    var lead by remember { mutableIntStateOf(LiveTvReminderScheduler.leadMinutes(context)) }
    var autoSwitch by remember { mutableStateOf(LiveTvReminderScheduler.autoSwitch(context)) }
    var timeshift by remember { mutableStateOf(viewModel.preferences.liveTimeshiftEnabled) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // One Timeshift section: switch, buffer size and storage note stay together.
        SettingsGroup(title = stringResource(R.string.timeshift_title), subtitle = stringResource(R.string.timeshift_hint)) {
            com.ambient.tv.ui.common.TvSettingRow(
                title = stringResource(R.string.timeshift_title),
                body = stringResource(R.string.live_timeshift_optional_hint),
                checked = timeshift,
                onCheckedChange = {
                    viewModel.preferences.initializeTimeshiftStorage(context)
                    if (it && viewModel.preferences.liveTimeshiftMb == 0) { viewModel.preferences.liveTimeshiftMb = 512; size = 512 }
                    viewModel.preferences.liveTimeshiftEnabled = it
                    timeshift = it
                },
            )
            SettingsSelect(
                label = stringResource(R.string.settings_playback_timeshift_size),
                description = stringResource(R.string.timeshift_storage_hint),
                options = AppPreferences.LIVE_TIMESHIFT_SIZES.filter { it > 0 }.map { megabytes ->
                    SettingsSelectOption(
                        text = if (megabytes < 1024) "$megabytes MB" else "${megabytes / 1024} GB",
                        selected = size == megabytes,
                        onClick = { size = megabytes; viewModel.preferences.liveTimeshiftMb = megabytes },
                    )
                },
            )
        }
        if (showRecording) SettingsGroup(title = stringResource(R.string.settings_playback_live_functions)) {
            LiveTvFeatureSwitches(showRecording = true, showTimeshift = false)
        }
        SettingsGroup(title = stringResource(R.string.settings_live_reminders)) {
            com.ambient.tv.ui.common.TvSettingRow(
                title = stringResource(R.string.reminder_auto_switch),
                body = stringResource(R.string.reminder_auto_switch_hint),
                checked = autoSwitch,
                onCheckedChange = { autoSwitch = it; LiveTvReminderScheduler.setAutoSwitch(context, it) },
            )
            SettingsSelect(
                label = stringResource(R.string.reminder_lead_title),
                options = listOf(0, 1, 2, 5, 10).map { minutes ->
                    SettingsSelectOption(
                        text = if (minutes == 0) stringResource(R.string.reminder_lead_at_start) else stringResource(R.string.reminder_lead_minutes, minutes),
                        selected = lead == minutes,
                        onClick = { lead = minutes; LiveTvReminderScheduler.setLeadMinutes(context, minutes) },
                    )
                },
            )
        }
    }
}


@Composable
internal fun LiveTvFeatureSwitches(
    onTimeshiftChanged: (() -> Unit)? = null,
    showRecording: Boolean = true,
    showTimeshift: Boolean = true,
    viewModel: LiveTvExtrasSettingsViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(),
) {
    val context = LocalContext.current
    val preferences = viewModel.preferences
    if (showTimeshift) com.ambient.tv.ui.common.TvSettingRow(
        title = stringResource(R.string.timeshift_title),
        body = stringResource(R.string.live_timeshift_optional_hint),
        checked = preferences.liveTimeshiftEnabled,
        onCheckedChange = {
            preferences.initializeTimeshiftStorage(context)
            if (it && preferences.liveTimeshiftMb == 0) preferences.liveTimeshiftMb = 512
            preferences.liveTimeshiftEnabled = it
            onTimeshiftChanged?.invoke()
        },
    )
    if (showRecording) com.ambient.tv.ui.common.TvSettingRow(
        title = stringResource(R.string.live_direct_record),
        body = stringResource(R.string.live_direct_record_hint),
        checked = preferences.liveDirectRecordEnabled,
        onCheckedChange = { preferences.liveDirectRecordEnabled = it },
    )
}
