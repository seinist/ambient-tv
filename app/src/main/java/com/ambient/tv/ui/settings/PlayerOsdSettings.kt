package com.ambient.tv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
