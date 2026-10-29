package com.ambient.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.common.PlayerOsdTimeout
import com.ambient.tv.ui.common.SettingsSectionHeader

/** Settings › Playback: how long the player controls stay visible; applies right away. */
@Composable
internal fun PlayerOsdSettingsGroup(viewModel: LiveTvExtrasSettingsViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()) {
    var seconds by remember { mutableIntStateOf(viewModel.preferences.playerOsdSeconds) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeader(stringResource(R.string.settings_playback_section_controls))
        SettingsSelect(
            label = stringResource(R.string.player_osd_duration_title),
            description = stringResource(R.string.player_osd_duration_hint),
            options = PlayerOsdTimeout.SECONDS.map { value ->
                SettingsSelectOption(
                    text = stringResource(R.string.player_osd_duration_seconds, value),
                    selected = seconds == value,
                    onClick = { seconds = value; viewModel.preferences.playerOsdSeconds = value },
                )
            },
        )
    }
}

/** Settings › Playback: what remote Back does once the OSD is hidden; applies right away. */
@Composable
internal fun PlayerBackSettingsGroup(viewModel: LiveTvExtrasSettingsViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()) {
    var minimize by remember { mutableStateOf(viewModel.preferences.playerBackMinimizes) }
    SettingsSelect(
        label = stringResource(R.string.player_back_title),
        description = stringResource(R.string.player_back_hint),
        options = listOf(false to R.string.player_back_stop, true to R.string.player_back_minimize).map { (value, label) ->
            SettingsSelectOption(
                text = stringResource(label),
                selected = minimize == value,
                onClick = { minimize = value; viewModel.preferences.playerBackMinimizes = value },
            )
        },
    )
}

/** Settings › Playback: network buffer for Media3, MPV and VLC; applies to the next playback. */
@Composable
internal fun NetworkBufferSettingsGroup(viewModel: LiveTvExtrasSettingsViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()) {
    var key by remember { mutableStateOf(viewModel.preferences.networkBufferKey) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeader(stringResource(R.string.settings_playback_section_streaming))
        SettingsSelect(
            label = stringResource(R.string.settings_network_buffer),
            description = stringResource(R.string.settings_network_buffer_hint),
            options = com.ambient.tv.ui.player.NETWORK_BUFFER_KEYS.map { value ->
                SettingsSelectOption(
                    text = stringResource(when (value) {
                        "low" -> R.string.settings_network_buffer_low
                        "high" -> R.string.settings_network_buffer_high
                        else -> R.string.settings_network_buffer_medium
                    }),
                    selected = key == value,
                    onClick = { key = value; viewModel.preferences.networkBufferKey = value },
                )
            },
        )
    }
}
