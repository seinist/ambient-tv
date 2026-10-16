package com.ambient.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.display.sdrToHdrPlaybackControlVisible
import com.ambient.tv.ui.settings.rememberHdrControlFacts

/** Shared by every video panel host and backed by the global conversion preference. */
@Composable
internal fun PlayerSdrToHdrControl() {
    val context = LocalContext.current
    val preferences = remember(context) { videoImagePreferences(context) }
    val selection by produceState(
        initialValue = preferences.sdrToHdrEnabled to preferences.sdrToHdrPeakNits,
        key1 = preferences,
    ) {
        preferences.changes().collect {
            value = preferences.sdrToHdrEnabled to preferences.sdrToHdrPeakNits
        }
    }
    val facts = rememberHdrControlFacts(preferences.uiHdrModeKey)
    if (!sdrToHdrPlaybackControlVisible(facts)) return
    val options = listOf("off" to stringResource(R.string.player_off)) +
        (listOf(0, 300, 400, 500, 600) + selection.second).distinct().sorted().map { nits ->
            nits.toString() to if (nits == 0) stringResource(R.string.settings_ui_hdr_auto) else "$nits nits"
        }
    val selected = if (selection.first) selection.second.toString() else "off"
    PlayerDropUpSelector(
        label = stringResource(R.string.settings_sdr_to_hdr),
        value = options.first { it.first == selected }.second,
        options = options,
        selectedKey = selected,
        onSelected = { key ->
            if (key == "off") {
                preferences.sdrToHdrEnabled = false
            } else {
                preferences.sdrToHdrPeakNits = key.toInt()
                preferences.sdrToHdrEnabled = true
            }
        },
    )
}
