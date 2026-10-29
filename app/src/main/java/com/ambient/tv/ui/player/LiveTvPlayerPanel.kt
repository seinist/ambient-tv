package com.ambient.tv.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.livetv.LiveTimeshift
import com.ambient.tv.livetv.initializeTimeshiftStorage
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.PlayerPanelEntry
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.common.playerPanelFocusKey
import com.ambient.tv.ui.player.teletext.TeletextPanelEntries
import com.ambient.tv.ui.player.teletext.TeletextUi
import com.ambient.tv.ui.tvheadend.LocalLiveRecordingControl
import com.ambient.tv.ui.tvheadend.LocalLiveTimeshiftChanged
import com.ambient.tv.ui.tvheadend.LocalLiveTimeshiftConfigurable
import com.ambient.tv.ui.tvheadend.jumpToLiveAvailable

/** The shared entry stays below subtitles for every live-TV renderer. */
@Composable
internal fun LiveTvPlayerPanelEntry(onOpen: () -> Unit) {
    val recording = LocalLiveRecordingControl.current
    if (LocalLiveTimeshiftChanged.current == null && !TeletextUi.hostActive && !recording.canManageRecordings) return
    val anchor by LiveTimeshift.anchor.collectAsState()
    val buffer = LiveTimeshift.current.takeIf { anchor != null }
    val summary = listOfNotNull(
        if (recording.isRecordingConfirmed) stringResource(R.string.tvh_recording_active) else null,
        when {
            buffer?.recordingId != null -> stringResource(R.string.live_recording_playback)
            buffer != null -> stringResource(R.string.player_live_tv_timeshift_active)
            else -> null
        },
    ).joinToString(" · ").ifBlank { stringResource(R.string.player_live_tv_summary) }
    PlayerPanelEntry(
        icon = Icons.Filled.LiveTv,
        title = stringResource(R.string.settings_live_tv_title),
        value = summary,
        onClick = onOpen,
    )
}

@Composable
internal fun LiveTvPlayerPanelControls() {
    TeletextPanelEntries()
    LiveTvTimeshiftControls()
    LiveTvRecordingControls()
}

@Composable
private fun LiveTvTimeshiftControls() {
    val onChanged = LocalLiveTimeshiftChanged.current ?: return
    val configurable = LocalLiveTimeshiftConfigurable.current
    val changingChannel = LocalPlayerChangingItem.current
    val context = LocalContext.current
    val preferences = videoImagePreferences(context)
    val enabled = preferences.liveTimeshiftEnabled
    var size by remember(preferences) { mutableIntStateOf(preferences.liveTimeshiftMb) }
    LaunchedEffect(preferences) {
        preferences.initializeTimeshiftStorage(context)
        size = preferences.liveTimeshiftMb
    }
    val anchor by LiveTimeshift.anchor.collectAsState()
    val buffer = LiveTimeshift.current.takeIf { anchor != null }
    val explanation = when {
        changingChannel -> R.string.player_live_tv_switching
        buffer?.recordingId != null -> R.string.player_live_tv_timeshift_recording
        !configurable -> R.string.player_live_tv_timeshift_unavailable
        enabled && buffer == null -> R.string.player_live_tv_timeshift_inactive
        else -> R.string.live_timeshift_optional_hint
    }
    TvSettingRow(
        title = stringResource(R.string.timeshift_title),
        body = stringResource(explanation),
        checked = enabled,
        enabled = configurable,
        modifier = Modifier.playerPanelFocusKey("live-tv:timeshift"),
        onCheckedChange = { requested ->
            if (configurable && requested != preferences.liveTimeshiftEnabled) {
                preferences.initializeTimeshiftStorage(context)
                if (requested && preferences.liveTimeshiftMb == 0) preferences.liveTimeshiftMb = 512
                size = preferences.liveTimeshiftMb
                preferences.liveTimeshiftEnabled = requested
                onChanged()
            }
        },
    )
    if (enabled) {
        val sizes = AppPreferences.LIVE_TIMESHIFT_SIZES.filter { it > 0 }.map { megabytes ->
            megabytes.toString() to if (megabytes < 1024) "$megabytes MB" else "${megabytes / 1024} GB"
        }
        PlayerDropUpSelector(
            label = stringResource(R.string.player_live_tv_buffer_size),
            value = sizes.firstOrNull { it.first == size.toString() }?.second ?: stringResource(R.string.timeshift_off),
            options = sizes,
            selectedKey = size.toString(),
            disabledKeys = if (configurable) emptySet() else sizes.map { it.first }.toSet(),
            onSelected = { key ->
                val selected = key.toIntOrNull()
                if (configurable && selected != null && selected > 0 && selected in AppPreferences.LIVE_TIMESHIFT_SIZES &&
                    selected != preferences.liveTimeshiftMb) {
                    size = selected
                    preferences.liveTimeshiftMb = size
                    onChanged()
                }
            },
        )
        Text(stringResource(R.string.player_live_tv_buffer_hint), style = MaterialTheme.typography.bodySmall)
    }
    val playback by BackgroundPlaybackSession.snapshot.collectAsState()
    val canGoLive = jumpToLiveAvailable(playback.metadata?.isLive == true, playback.status.positionMs)
    val navigation = LocalPlayerPanelNavigation.current
    if (buffer != null) TvActionButton(
        text = stringResource(R.string.timeshift_go_live),
        icon = Icons.Filled.LiveTv,
        enabled = canGoLive && !changingChannel,
        fillWidth = true,
        modifier = Modifier.playerPanelFocusKey("live-tv:go-live"),
        onClick = {
            LiveTimeshift.seekHandler?.invoke(null)
            navigation?.close()
        },
    )
}

@Composable
private fun LiveTvRecordingControls() {
    val recording = LocalLiveRecordingControl.current
    if (!recording.canManageRecordings) return
    val preferences = videoImagePreferences(LocalContext.current)
    val enabled = preferences.liveDirectRecordEnabled
    val changingChannel = LocalPlayerChangingItem.current
    TvSettingRow(
        title = stringResource(R.string.live_direct_record),
        body = stringResource(R.string.live_direct_record_hint),
        checked = enabled,
        enabled = !recording.busy && !changingChannel,
        modifier = Modifier.playerPanelFocusKey("live-tv:recording-enabled"),
        onCheckedChange = { preferences.liveDirectRecordEnabled = it },
    )
    if (recording.isRecordingConfirmed || recording.scheduled) Text(
        stringResource(if (recording.isRecordingConfirmed) R.string.tvh_recording_active else R.string.tvh_recording_scheduled),
        style = MaterialTheme.typography.bodySmall,
    )
    if (enabled) {
        TvActionButton(
            text = stringResource(if (recording.scheduled) R.string.player_record_stop else R.string.player_live_tv_record_now),
            icon = if (recording.scheduled) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
            enabled = recording.available && !changingChannel,
            loading = recording.busy,
            selected = recording.isRecordingConfirmed,
            fillWidth = true,
            modifier = Modifier.playerPanelFocusKey("live-tv:record"),
            onClick = recording.record,
        )
        if (!recording.available && !changingChannel) Text(
            stringResource(R.string.player_live_tv_record_unavailable),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
