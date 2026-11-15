package com.ambient.tv.ui.settings

import androidx.annotation.StringRes
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences

@StringRes
internal fun displayModeHintRes(mode: String, adjustment: String): Int {
    val adjustsAutomatically = adjustment != AppPreferences.DISPLAY_REFRESH_OFF
    return when (mode) {
        AppPreferences.DISPLAY_MODE_AUTO_CONTENT -> R.string.settings_display_mode_content_hint
        AppPreferences.DISPLAY_MODE_AUTO_DISPLAY -> R.string.settings_display_mode_full_hint
        "system" -> if (adjustsAutomatically) R.string.settings_display_mode_system_adjusted_hint
            else R.string.settings_display_mode_system_hint
        else -> if (adjustsAutomatically) R.string.settings_display_mode_fixed_adjusted_hint
            else R.string.settings_display_mode_fixed_hint
    }
}

@StringRes
internal fun displayAdjustmentHintRes(adjustment: String): Int = when (adjustment) {
    AppPreferences.DISPLAY_REFRESH_OFF -> R.string.settings_display_adjustment_off_hint
    "always" -> R.string.settings_display_adjustment_always_hint
    AppPreferences.DISPLAY_REFRESH_START_STOP -> R.string.settings_display_adjustment_start_stop_hint
    "start" -> R.string.settings_display_adjustment_start_hint
    else -> R.string.settings_display_adjustment_hint
}
