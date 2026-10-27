package com.ambient.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.data.local.ClockSettings
import com.ambient.tv.ui.common.AmbientClock
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.RightPanelValueSlider
import com.ambient.tv.ui.common.TvSettingRow

internal fun LazyListScope.clockSettingsItems(clock: ClockSettings, onChange: ((ClockSettings) -> ClockSettings) -> Unit) {
    item { ClockPreview(clock) }
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
    item {
        SettingsGroup(title = stringResource(R.string.settings_clock_date_group)) {
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
    item {
        SettingsGroup(title = stringResource(R.string.settings_clock_look_group)) {
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
    item {
        SettingsGroup {
            TvSettingRow(
                title = stringResource(R.string.settings_clock_end_time),
                body = stringResource(R.string.settings_clock_end_time_hint),
                checked = clock.endTime,
                onCheckedChange = { v -> onChange { it.copy(endTime = v) } },
            )
        }
    }
}

/** A strip of dimmed artwork stand-in, so shadow and opacity read as they will over the hero. */
@Composable
private fun ClockPreview(clock: ClockSettings) {
    Box(
        Modifier.fillMaxWidth().height(96.dp)
            .background(
                Brush.horizontalGradient(listOf(Color(0xFF1B2433), Color(0xFF4A5B70), Color(0xFFB7C3CF))),
                RoundedCornerShape(10.dp),
            ),
    ) {
        AmbientClock(clock.copy(display = ClockSettings.DISPLAY_PLAYER), Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 18.dp))
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
