package com.ambient.tv.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.data.local.ClockSettings
import com.ambient.tv.ui.common.ClockWeatherDisplay
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.RightPanelValueSlider
import com.ambient.tv.ui.common.TvSettingRow

internal fun LazyListScope.clockSettingsItems(clock: ClockSettings, onChange: ((ClockSettings) -> ClockSettings) -> Unit) {
    item { ClockPreview(clock) }
    item { SettingsSubheader(stringResource(R.string.settings_section_general_clock_display)) }
    item {
        SettingsGroup {
            ClockChoice(R.string.settings_clock_display, clock.display, ClockSettings.displays, ::displayLabel) { v -> onChange { it.copy(display = v) } }
            ClockChoice(R.string.settings_clock_format, clock.format, ClockSettings.formats, ::formatLabel) { v -> onChange { it.copy(format = v) } }
            TvSettingRow(
                title = stringResource(R.string.settings_clock_seconds),
                body = stringResource(R.string.settings_clock_seconds_hint),
                checked = clock.seconds,
                onCheckedChange = { v -> onChange { it.copy(seconds = v) } },
            )
        }
    }
    item { SettingsSubheader(stringResource(R.string.settings_clock_date_group)) }
    item {
        SettingsGroup {
            ClockChoice(R.string.settings_clock_date, clock.date, ClockSettings.dates, ::dateLabel) { v -> onChange { it.copy(date = v) } }
            if (clock.date != ClockSettings.DATE_OFF) {
                TvSettingRow(
                    title = stringResource(R.string.settings_clock_date_beside),
                    body = stringResource(R.string.settings_clock_date_beside_hint),
                    checked = clock.dateBeside,
                    onCheckedChange = { v -> onChange { it.copy(dateBeside = v) } },
                )
            }
        }
    }
    item { SettingsSubheader(stringResource(R.string.settings_clock_look_group)) }
    item {
        SettingsGroup {
            ClockChoice(R.string.settings_clock_size, clock.size, ClockSettings.sizes, ::sizeLabel) { v -> onChange { it.copy(size = v) } }
            ClockChoice(R.string.settings_clock_font, clock.font, ClockSettings.fonts, ::fontLabel) { v -> onChange { it.copy(font = v) } }
            ClockChoice(R.string.settings_clock_color, clock.color, ClockSettings.colors, ::colorLabel) { v -> onChange { it.copy(color = v) } }
            RightPanelValueSlider(
                title = stringResource(R.string.settings_clock_opacity),
                value = clock.opacityPercent,
                valueRange = 40..100,
                step = 5,
                valueLabel = "${clock.opacityPercent} %",
                onValueChange = { v -> onChange { it.copy(opacityPercent = v) } },
            )
            ClockChoice(R.string.settings_clock_shadow, clock.shadow, ClockSettings.shadows, ::shadowLabel) { v -> onChange { it.copy(shadow = v) } }
        }
    }
    item { SettingsSubheader(stringResource(R.string.settings_clock_weather_group)) }
    item {
        SettingsGroup {
            ClockChoice(R.string.settings_clock_weather_layout, clock.weatherLayout, ClockSettings.weatherLayouts, ::weatherLayoutLabel) {
                value -> onChange { it.copy(weatherLayout = value) }
            }
            TvSettingRow(
                title = stringResource(R.string.settings_clock_weather_accent),
                body = stringResource(R.string.settings_clock_weather_accent_hint),
                checked = clock.weatherAccent,
                onCheckedChange = { value -> onChange { it.copy(weatherAccent = value) } },
            )
            TvSettingRow(
                title = stringResource(R.string.settings_clock_weather_motion),
                body = stringResource(R.string.settings_clock_weather_motion_hint),
                checked = clock.weatherTransitions,
                onCheckedChange = { value -> onChange { it.copy(weatherTransitions = value) } },
            )
        }
    }
    item { SettingsSubheader(stringResource(R.string.settings_section_general_playback)) }
    item {
        TvSettingRow(
            title = stringResource(R.string.settings_clock_end_time),
            body = stringResource(R.string.settings_clock_end_time_hint),
            checked = clock.endTime,
            onCheckedChange = { v -> onChange { it.copy(endTime = v) } },
        )
    }
}

/**
 * The clock in the context its display setting places it: the Home hero, a browse header or
 * the player OSD. Opacity, shadow, accent and weather layout apply as in the app; overlays are
 * scaled to the stage so their proportions match the TV screen. No forecast is fetched.
 */
@Composable
private fun ClockPreview(clock: ClockSettings) {
    SettingsScenePreview(stringResource(R.string.settings_scene_preview_caption, stringResource(displayLabel(clock.display)))) {
        when (clock.display) {
            ClockSettings.DISPLAY_PLAYER -> ScenePlayer()
            ClockSettings.DISPLAY_EVERYWHERE -> SceneBrowseHeader()
            else -> SceneHomeHero()
        }
        if (clock.display == ClockSettings.DISPLAY_OFF) return@SettingsScenePreview
        val scale = (maxWidth / SCENE_DESIGN_WIDTH).coerceIn(.7f, 1f)
        val opacity = clock.opacityPercent / 100f
        val weatherText = Color.White.copy(alpha = .86f * opacity)
        val weatherIcon = if (clock.weatherAccent) MaterialTheme.colorScheme.primary.copy(alpha = opacity) else weatherText
        ClockWeatherDisplay(
            settings = clock, showClock = true, showWeather = true,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 14.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(1f, 0f)
                },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(com.ambient.tv.ui.weather.weatherIcon(2, isDay = true), null, Modifier.size(20.dp), tint = weatherIcon)
                Text("7°", color = weatherText, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ClockChoice(labelRes: Int, value: String, keys: List<String>, label: (String) -> Int, onSelected: (String) -> Unit) {
    val labels = keys.map { it to stringResource(label(it)) }
    PlayerDropUpSelector(
        label = stringResource(labelRes),
        value = labels.firstOrNull { it.first == value }?.second.orEmpty(),
        options = labels,
        selectedKey = value,
        onSelected = onSelected,
    )
}

private fun displayLabel(key: String) = when (key) {
    ClockSettings.DISPLAY_OFF -> R.string.settings_clock_display_off
    ClockSettings.DISPLAY_HOME -> R.string.settings_clock_display_home
    ClockSettings.DISPLAY_EVERYWHERE -> R.string.settings_clock_display_everywhere
    else -> R.string.settings_clock_display_player
}

private fun formatLabel(key: String) = when (key) {
    ClockSettings.FORMAT_12 -> R.string.settings_clock_format_12
    ClockSettings.FORMAT_SYSTEM -> R.string.settings_clock_format_system
    else -> R.string.settings_clock_format_24
}

private fun dateLabel(key: String) = when (key) {
    ClockSettings.DATE_OFF -> R.string.settings_clock_date_off
    ClockSettings.DATE_LONG -> R.string.settings_clock_date_long
    ClockSettings.DATE_NUMERIC -> R.string.settings_clock_date_numeric
    else -> R.string.settings_clock_date_short
}

private fun sizeLabel(key: String) = when (key) {
    ClockSettings.SIZE_SMALL -> R.string.settings_clock_size_small
    ClockSettings.SIZE_LARGE -> R.string.settings_clock_size_large
    else -> R.string.settings_clock_size_medium
}

private fun fontLabel(key: String) = when (key) {
    ClockSettings.FONT_LIGHT -> R.string.settings_clock_font_light
    ClockSettings.FONT_BOLD -> R.string.settings_clock_font_bold
    ClockSettings.FONT_MONO -> R.string.settings_clock_font_mono
    else -> R.string.settings_clock_font_ui
}

private fun colorLabel(key: String) = when (key) {
    ClockSettings.COLOR_ACCENT -> R.string.settings_clock_color_accent
    ClockSettings.COLOR_DIM -> R.string.settings_clock_color_dim
    else -> R.string.settings_clock_color_white
}

private fun shadowLabel(key: String) = when (key) {
    ClockSettings.SHADOW_OFF -> R.string.settings_clock_shadow_off
    ClockSettings.SHADOW_STRONG -> R.string.settings_clock_shadow_strong
    else -> R.string.settings_clock_shadow_soft
}

private fun weatherLayoutLabel(key: String) = when (key) {
    ClockSettings.WEATHER_LAYOUT_INTEGRATED -> R.string.settings_clock_weather_integrated
    ClockSettings.WEATHER_LAYOUT_ABOVE -> R.string.settings_clock_weather_above
    ClockSettings.WEATHER_LAYOUT_BELOW -> R.string.settings_clock_weather_below
    ClockSettings.WEATHER_LAYOUT_LEFT -> R.string.settings_clock_weather_left
    ClockSettings.WEATHER_LAYOUT_RIGHT -> R.string.settings_clock_weather_right
    else -> R.string.settings_clock_weather_auto
}
