package com.ambient.tv.ui.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ambient.tv.data.local.ClockSettings
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Clock settings shared by the page header, the app home, the player OSD and the screensaver. */
val LocalClockSettings = staticCompositionLocalOf { ClockSettings() }

/**
 * Wall time that recomposes once a minute, on the minute ([seconds]: once a second). A time or
 * zone change (TVs often get NTP time only after boot) updates it at once.
 */
@Composable
fun rememberWallClock(seconds: Boolean = false): State<LocalDateTime> {
    val context = LocalContext.current
    val changed = remember { mutableStateOf(0) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { changed.value++ }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    val step = if (seconds) 1_000L else 60_000L
    return produceState(LocalDateTime.now(), changed.value, step) {
        value = LocalDateTime.now()
        while (true) {
            delay(step - System.currentTimeMillis() % step)
            value = LocalDateTime.now()
        }
    }
}

@Composable
fun rememberUse24Hour(settings: ClockSettings = LocalClockSettings.current): Boolean =
    settings.use24Hour(DateFormat.is24HourFormat(LocalContext.current))

@Composable
fun rememberTimeFormatter(
    use24Hour: Boolean = rememberUse24Hour(),
    seconds: Boolean = false,
): DateTimeFormatter {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    return remember(locale, use24Hour, seconds) {
        val time = (if (use24Hour) "HH:mm" else "h:mm") + if (seconds) ":ss" else ""
        DateTimeFormatter.ofPattern(if (use24Hour) time else "$time a", locale)
    }
}

/** Null for [ClockSettings.DATE_OFF]. German keeps its "d. MMM" order, other languages lead with the month. */
@Composable
fun rememberDateFormatter(style: String): DateTimeFormatter? {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    return remember(locale, style) {
        val german = locale.language == "de"
        val pattern = when (style) {
            ClockSettings.DATE_OFF -> null
            ClockSettings.DATE_LONG -> if (german) "EEEE, d. MMMM" else "EEEE, MMMM d"
            ClockSettings.DATE_NUMERIC -> if (german) "dd.MM." else "MM/dd"
            else -> if (german) "EEE, d. MMM" else "EEE, MMM d"
        }
        pattern?.let { DateTimeFormatter.ofPattern(it, locale) }
    }
}

/** Time with the date beneath or beside it, styled by [settings]. */
@Composable
fun AmbientClock(settings: ClockSettings, modifier: Modifier = Modifier) {
    val now by rememberWallClock(settings.seconds)
    val timeFormatter = rememberTimeFormatter(rememberUse24Hour(settings), settings.seconds)
    val dateFormatter = rememberDateFormatter(settings.date)
    val (timeSize, dateSize) = when (settings.size) {
        ClockSettings.SIZE_SMALL -> 18.sp to 11.sp
        ClockSettings.SIZE_LARGE -> 30.sp to 14.sp
        else -> 22.sp to 12.sp
    }
    val weight = when (settings.font) {
        ClockSettings.FONT_LIGHT -> FontWeight.Light
        ClockSettings.FONT_BOLD -> FontWeight.Bold
        else -> FontWeight.Medium
    }
    val family = if (settings.font == ClockSettings.FONT_MONO) FontFamily.Monospace else null
    val opacity = settings.opacityPercent / 100f
    val timeColor = when (settings.color) {
        ClockSettings.COLOR_ACCENT -> MaterialTheme.colorScheme.primary
        ClockSettings.COLOR_DIM -> Color.White.copy(alpha = 0.7f)
        else -> Color.White
    }.let { it.copy(alpha = it.alpha * opacity) }
    val dateColor = Color.White.copy(alpha = 0.62f * opacity)
    val style = when (settings.shadow) {
        ClockSettings.SHADOW_OFF -> TextStyle.Default
        ClockSettings.SHADOW_STRONG -> TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 2f), 14f))
        else -> TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), 8f))
    }
    val time: @Composable () -> Unit = {
        if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) {
            // Large 12-hour clocks with seconds can exceed the space beside a detail
            // page's two buttons. Fit the actual text without changing the saved size
            // or increasing that overlay's fixed height. The configured size is the cap.
            BasicText(
                text = now.format(timeFormatter),
                style = LocalTextStyle.current.merge(style).merge(TextStyle(
                    color = timeColor, fontSize = timeSize, fontWeight = weight, fontFamily = family,
                )),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                autoSize = remember(timeSize) {
                    TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = timeSize, stepSize = 0.5.sp)
                },
            )
        } else {
            Text(now.format(timeFormatter), color = timeColor, fontSize = timeSize, fontWeight = weight,
                fontFamily = family, maxLines = 1, style = style)
        }
    }
    val date: @Composable (Modifier) -> Unit = { dateModifier ->
        if (dateFormatter != null) Text(now.format(dateFormatter), modifier = dateModifier, color = dateColor, fontSize = dateSize,
            fontWeight = FontWeight.Medium, fontFamily = family, maxLines = 1, overflow = TextOverflow.Ellipsis, style = style)
    }
    if (settings.dateBeside) {
        // Like Kodi: the date leads, the time closes the line at the screen edge.
        // Keep the time readable in a narrow header; a long date gives up space first.
        Row(modifier.width(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            date(Modifier.weight(1f, fill = false)); time()
        }
    } else {
        Column(modifier, horizontalAlignment = Alignment.End) { time(); date(Modifier) }
    }
}

/** The clock at the right end of a [PageHeader] and floating over the app home. */
@Composable
fun HeaderClock(modifier: Modifier = Modifier) {
    AmbientClock(LocalClockSettings.current, modifier.padding(start = 18.dp))
}
