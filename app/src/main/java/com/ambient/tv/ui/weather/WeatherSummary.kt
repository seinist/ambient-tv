package com.ambient.tv.ui.weather

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.weather.WeatherSnapshot
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.LoginLinkPanel
import com.ambient.tv.ui.common.rememberWallClock
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.ui.theme.appControlSurface
import androidx.compose.foundation.shape.RoundedCornerShape
import java.util.Locale

internal fun weatherIcon(code: Int?, isDay: Boolean = true): ImageVector = when (code) {
    0, 1 -> if (isDay) Icons.Filled.WbSunny else Icons.Filled.NightsStay
    45, 48, 51, 53, 55, 56, 57 -> Icons.Filled.Grain
    61, 63, 65, 66, 67, 80, 81, 82 -> Icons.Filled.WaterDrop
    71, 73, 75, 77, 85, 86 -> Icons.Filled.AcUnit
    95, 96, 99 -> Icons.Filled.Thunderstorm
    else -> Icons.Filled.Cloud
}

private data class CompactWeatherVisual(val code: Int?, val isDay: Boolean, val temperature: String)

/** Shared current conditions for the header and the screensaver. The snapshot owns its units. */
@Composable
fun WeatherCompactSummary(
    snapshot: WeatherSnapshot,
    temperatureUnit: String = snapshot.temperatureUnit,
    modifier: Modifier = Modifier,
    textColor: Color? = null,
    iconColor: Color? = null,
    animateChanges: Boolean = false,
) {
    // A setting change may briefly precede the new-unit response. Never relabel the old value.
    val unit = snapshot.temperatureUnit
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val color = textColor ?: MaterialTheme.colorScheme.onSurface
    val current = snapshot.current ?: return
    val tick by rememberWallClock()
    val nowMillis = remember(tick) { System.currentTimeMillis() }
    val value = weatherTemperature(current.temperature, unit, locale)
    val description = stringResource(R.string.weather_compact_description, snapshot.location.name, value,
        stringResource(weatherConditionRes(current.weatherCode)))
    val visual = CompactWeatherVisual(current.weatherCode, current.isDay, value)
    val conditions: @Composable (CompactWeatherVisual) -> Unit = { shown ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(weatherIcon(shown.code, shown.isDay), null, Modifier.size(20.dp), tint = iconColor ?: color)
            Text(shown.temperature, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.semantics { contentDescription = description }) {
        if (animateChanges && !LocalPerformanceProfile.current.reducedEffects) {
            Crossfade(targetState = visual, animationSpec = tween(240), label = "weatherCondition") { shown ->
                conditions(shown)
            }
        } else conditions(visual)
        if (snapshot.isStale(nowMillis) || temperatureUnit != unit) {
            Text(stringResource(R.string.weather_cached), style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = color.alpha * .8f), maxLines = 1)
        }
    }
}

/** A real source link stays usable on TV through the same QR/link panel as other web links. */
@Composable
internal fun WeatherSourceLink(label: String, url: String, modifier: Modifier = Modifier, color: Color? = null) {
    var showLink by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(LocalUiTokens.current.controlRadius)
    Text(label, style = MaterialTheme.typography.labelSmall, textDecoration = TextDecoration.Underline,
        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.appControlSurface(true, shape = shape) else Modifier)
            .clickable { showLink = true }.padding(horizontal = 4.dp, vertical = 3.dp))
    if (showLink) AppDialog(onDismiss = { showLink = false }, title = label) {
        LoginLinkPanel(url)
    }
}

@Composable
fun WeatherAttribution(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        WeatherSourceLink(stringResource(R.string.weather_source), "https://open-meteo.com/")
        WeatherSourceLink("CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/")
    }
}
