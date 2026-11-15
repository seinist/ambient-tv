package com.ambient.tv.ui.settings

import androidx.annotation.StringRes
import com.ambient.tv.R
import com.ambient.tv.ui.performance.PerformanceMode
import com.ambient.tv.ui.theme.AccentStyle

@StringRes
internal fun uiPerformanceHintRes(mode: String): Int = when (PerformanceMode.fromKey(mode)) {
    PerformanceMode.AUTO -> R.string.settings_ui_performance_auto_hint
    PerformanceMode.BALANCED -> R.string.settings_ui_performance_balanced_hint
    PerformanceMode.MAXIMUM -> R.string.settings_ui_performance_maximum_hint
    PerformanceMode.LOW -> R.string.settings_ui_performance_low_hint
}

@StringRes
internal fun playbackPerformanceHintRes(mode: String): Int = when (PerformanceMode.fromKey(mode)) {
    PerformanceMode.AUTO -> R.string.settings_playback_performance_auto_hint
    PerformanceMode.BALANCED -> R.string.settings_playback_performance_balanced_hint
    PerformanceMode.MAXIMUM -> R.string.settings_playback_performance_maximum_hint
    PerformanceMode.LOW -> R.string.settings_playback_performance_low_hint
}

@StringRes
internal fun accentStyleHintRes(style: AccentStyle): Int = when (style) {
    AccentStyle.SATIN -> R.string.settings_accent_style_satin_hint
    AccentStyle.GLASS -> R.string.settings_accent_style_glass_hint
    AccentStyle.GLOW -> R.string.settings_accent_style_glow_hint
    AccentStyle.PEARL -> R.string.settings_accent_style_pearl_hint
    AccentStyle.CERAMIC -> R.string.settings_accent_style_ceramic_hint
    AccentStyle.CHROME -> R.string.settings_accent_style_chrome_hint
    AccentStyle.FACET -> R.string.settings_accent_style_facet_hint
    AccentStyle.LIGHT -> R.string.settings_accent_style_light_hint
}

@StringRes
internal fun audioVisualizerHintRes(mode: String): Int = when (mode) {
    "projectm" -> R.string.settings_audio_visualizer_projectm_hint
    "off" -> R.string.settings_audio_visualizer_off_hint
    else -> R.string.settings_audio_visualizer_spectrum_hint
}
