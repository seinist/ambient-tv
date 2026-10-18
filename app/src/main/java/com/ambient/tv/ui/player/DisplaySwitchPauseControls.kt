package com.ambient.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import java.util.Locale

/** "3,0 s" style seconds with one decimal, matching the 0.5 s grid. */
internal fun displaySwitchPauseSeconds(valueMs: Int, locale: Locale = Locale.getDefault()): String =
    String.format(locale, "%.1f", AppPreferences.normalizeDisplaySwitchPauseMs(valueMs) / 1000f)

/** Compact D-pad stepper: left/right moves by 0.5 s between Off and 20 s. */
@Composable
internal fun DisplaySwitchPauseSlider(valueMs: Int, onValueChange: (Int) -> Unit) {
    val value = AppPreferences.normalizeDisplaySwitchPauseMs(valueMs)
    val seconds = stringResource(R.string.settings_display_switch_pause_seconds, displaySwitchPauseSeconds(value))
    val label = when (value) {
        0 -> stringResource(R.string.settings_display_switch_pause_off)
        AppPreferences.DISPLAY_SWITCH_PAUSE_RECOMMENDED_MS -> stringResource(R.string.settings_display_switch_pause_recommended, seconds)
        else -> seconds
    }
    com.ambient.tv.ui.common.RightPanelValueSlider(
        title = stringResource(R.string.settings_display_switch_pause),
        subtitle = stringResource(R.string.settings_display_switch_pause_hint),
        value = value,
        valueRange = 0..AppPreferences.DISPLAY_SWITCH_PAUSE_MAX_MS,
        step = AppPreferences.DISPLAY_SWITCH_PAUSE_STEP_MS,
        valueLabel = label,
        onValueChange = { onValueChange(AppPreferences.normalizeDisplaySwitchPauseMs(it)) },
    )
}

/** Without automatic matching or a fixed mode the app never switches, so the option is hidden. */
internal fun displaySwitchPauseVisible(modeCount: Int, adjustmentKey: String, preferredModeKey: String): Boolean =
    modeCount > 1 && !(adjustmentKey == AppPreferences.DISPLAY_REFRESH_OFF && preferredModeKey == "system")
