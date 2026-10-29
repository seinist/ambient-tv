package com.ambient.tv.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.data.local.PAUSE_REWIND_MIN_PAUSE_SECONDS
import com.ambient.tv.data.local.PAUSE_REWIND_SECONDS
import com.ambient.tv.data.local.RESUME_REWIND_SECONDS
import com.ambient.tv.data.local.SEEK_JUMP_SECONDS
import com.ambient.tv.data.local.SeekPlaybackSettings
import com.ambient.tv.ui.settings.SettingsGroup
import com.ambient.tv.ui.settings.SettingsSelect
import com.ambient.tv.ui.settings.SettingsSelectOption

/** The settings page and playback panel edit the same global preferences and use the same copy. */
@Composable
internal fun SeekPlaybackSettingsControls(
    settings: SeekPlaybackSettings,
    onSeekJumpChanged: (Int) -> Unit,
    onPauseRewindChanged: (Int) -> Unit,
    onMinPauseChanged: (Int) -> Unit,
    onResumeRewindChanged: (Int) -> Unit,
    playbackPanel: Boolean = false,
    showPauseRewind: Boolean = true,
    showSavedResumeRewind: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(if (playbackPanel) 10.dp else 14.dp)) {
        SeekPlaybackOption(
            title = stringResource(R.string.settings_seek_jump),
            description = stringResource(R.string.settings_seek_jump_hint),
            value = settings.bigSeekJumpSeconds,
            options = SEEK_JUMP_SECONDS,
            icon = Icons.Filled.SwapHoriz,
            playbackPanel = playbackPanel,
            onChanged = onSeekJumpChanged,
        )
        if (showPauseRewind) {
            SeekPlaybackOption(
                title = stringResource(R.string.settings_pause_rewind),
                description = pauseRewindDescription(settings),
                value = settings.pauseRewindSeconds,
                options = PAUSE_REWIND_SECONDS,
                icon = Icons.Filled.Replay,
                playbackPanel = playbackPanel,
                onChanged = onPauseRewindChanged,
            )
            if (settings.pauseRewindSeconds > 0) {
                SeekPlaybackOption(
                    title = stringResource(R.string.settings_pause_rewind_min_pause),
                    description = stringResource(R.string.settings_pause_rewind_min_pause_hint),
                    value = settings.pauseRewindMinPauseSeconds,
                    options = PAUSE_REWIND_MIN_PAUSE_SECONDS,
                    icon = Icons.Filled.Timer,
                    playbackPanel = playbackPanel,
                    minimumPause = true,
                    onChanged = onMinPauseChanged,
                )
            }
        }
        if (showSavedResumeRewind) {
            SeekPlaybackOption(
                title = stringResource(R.string.settings_resume_rewind),
                description = if (settings.resumeRewindSeconds == 0) stringResource(R.string.settings_resume_rewind_hint)
                    else stringResource(R.string.settings_resume_rewind_active_hint, settings.resumeRewindSeconds),
                value = settings.resumeRewindSeconds,
                options = RESUME_REWIND_SECONDS,
                icon = Icons.Filled.Replay,
                playbackPanel = playbackPanel,
                onChanged = onResumeRewindChanged,
            )
        }
    }
}

@Composable
internal fun seekPlaybackSettingsSummary(
    settings: SeekPlaybackSettings,
    showPauseRewind: Boolean = true,
    showSavedResumeRewind: Boolean = true,
): String = buildList {
    add(stringResource(R.string.settings_seek_jump_summary, settings.bigSeekJumpSeconds))
    if (showPauseRewind && settings.pauseRewindSeconds > 0) add(stringResource(R.string.settings_pause_rewind_summary, settings.pauseRewindSeconds))
    if (showSavedResumeRewind && settings.resumeRewindSeconds > 0) add(stringResource(R.string.settings_resume_rewind_summary, settings.resumeRewindSeconds))
}.joinToString(" · ")

@Composable
private fun pauseRewindDescription(settings: SeekPlaybackSettings): String = when {
    settings.pauseRewindSeconds == 0 -> stringResource(R.string.settings_pause_rewind_hint)
    settings.pauseRewindMinPauseSeconds == 0 -> stringResource(R.string.settings_pause_rewind_always_hint, settings.pauseRewindSeconds)
    else -> stringResource(R.string.settings_pause_rewind_active_hint, settings.pauseRewindSeconds, settings.pauseRewindMinPauseSeconds)
}

@Composable
private fun seekOptionValue(seconds: Int, minimumPause: Boolean): String = when {
    seconds == 0 -> stringResource(if (minimumPause) R.string.settings_pause_rewind_always else R.string.player_off)
    minimumPause && seconds == 60 -> stringResource(R.string.settings_pause_rewind_minute)
    else -> stringResource(R.string.settings_rewind_seconds, seconds)
}

@Composable
private fun SeekPlaybackOption(
    title: String,
    description: String,
    value: Int,
    options: List<Int>,
    icon: ImageVector,
    playbackPanel: Boolean,
    minimumPause: Boolean = false,
    onChanged: (Int) -> Unit,
) {
    val labels = options.map { seconds -> seconds to seekOptionValue(seconds, minimumPause) }
    if (playbackPanel) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PlayerDropUpSelector(
                label = title,
                value = seekOptionValue(value, minimumPause),
                options = labels.map { (seconds, label) -> seconds.toString() to label },
                selectedKey = value.toString(),
                icon = icon,
                onSelected = { selected -> selected.toIntOrNull()?.takeIf { it in options }?.let(onChanged) },
            )
            RightPanelInfoRow(description)
        }
    } else {
        SettingsGroup(title = title, subtitle = description) {
            SettingsSelect(options = labels.map { (seconds, label) ->
                SettingsSelectOption(label, seconds == value, onClick = { onChanged(seconds) })
            })
        }
    }
}
