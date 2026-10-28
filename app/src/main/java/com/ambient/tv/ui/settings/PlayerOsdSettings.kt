package com.ambient.tv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.ui.common.PlayerOsdTimeout

/** Settings › Playback: how long the player controls stay visible; applies right away. */
@Composable
internal fun PlayerOsdSettingsGroup(viewModel: LiveTvExtrasSettingsViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()) {
    var seconds by remember { mutableIntStateOf(viewModel.preferences.playerOsdSeconds) }
    SettingsGroup(title = stringResource(R.string.player_osd_duration_title), subtitle = stringResource(R.string.player_osd_duration_hint)) {
        SettingsSelect(options = PlayerOsdTimeout.SECONDS.map { value ->
            SettingsSelectOption(
                text = stringResource(R.string.player_osd_duration_seconds, value),
                selected = seconds == value,
                onClick = { seconds = value; viewModel.preferences.playerOsdSeconds = value },
            )
        })
    }
}

/** Settings › Playback: what remote Back does once the OSD is hidden; applies right away. */
@Composable
internal fun PlayerBackSettingsGroup(viewModel: LiveTvExtrasSettingsViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()) {
    var minimize by remember { mutableStateOf(viewModel.preferences.playerBackMinimizes) }
    SettingsGroup(title = stringResource(R.string.player_back_title), subtitle = stringResource(R.string.player_back_hint)) {
        SettingsSelect(options = listOf(false to R.string.player_back_stop, true to R.string.player_back_minimize).map { (value, label) ->
            SettingsSelectOption(
                text = stringResource(label),
                selected = minimize == value,
                onClick = { minimize = value; viewModel.preferences.playerBackMinimizes = value },
            )
        })
    }
}
