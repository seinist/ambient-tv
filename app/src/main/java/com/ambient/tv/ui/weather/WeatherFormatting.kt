package com.ambient.tv.ui.weather

import androidx.annotation.StringRes
import com.ambient.tv.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.text.NumberFormat
import java.util.Locale

internal fun weatherNumber(value: Double?, locale: Locale, decimals: Int = 0): String {
    if (value == null || !value.isFinite()) return "—"
    return NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = decimals
    }.format(value)
}

internal fun weatherTemperature(value: Double?, unit: String, locale: Locale, showUnit: Boolean = true): String {
    val number = weatherNumber(value, locale)
    if (number == "—") return number
    return number + if (showUnit) if (unit == "fahrenheit") " °F" else " °C" else "°"
}

internal fun weatherMeasure(value: Double?, unit: String, locale: Locale, decimals: Int = 0): String =
    weatherNumber(value, locale, decimals).let { if (it == "—") it else "$it $unit" }

internal fun weatherWindUnit(unit: String): String = when (unit) {
    "mph" -> "mph"
    "ms" -> "m/s"
    "kn" -> "kn"
    else -> "km/h"
}

internal fun weatherZone(timezone: String): ZoneId = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("UTC"))

internal fun weatherTime(epochSeconds: Long?, timezone: String, locale: Locale, use24Hour: Boolean = true): String {
    if (epochSeconds == null || epochSeconds <= 0L) return "—"
    return runCatching {
        DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale)
            .format(Instant.ofEpochSecond(epochSeconds).atZone(weatherZone(timezone)))
    }.getOrDefault("—")
}

internal fun weatherTimestamp(epochMillis: Long, timezone: String, locale: Locale, use24Hour: Boolean = true): String =
    runCatching {
        val datePattern = if (locale.language == "de") "dd.MM.yyyy, " else "MMM d, yyyy, "
        DateTimeFormatter.ofPattern(datePattern + if (use24Hour) "HH:mm" else "h:mm a", locale)
            .format(Instant.ofEpochMilli(epochMillis).atZone(weatherZone(timezone)))
    }.getOrDefault("—")

internal fun weatherDayName(
    isoDate: String,
    timezone: String,
    locale: Locale,
    today: String,
    tomorrow: String,
    nowMillis: Long = System.currentTimeMillis(),
): String = runCatching {
    val date = LocalDate.parse(isoDate)
    val localToday = Instant.ofEpochMilli(nowMillis).atZone(weatherZone(timezone)).toLocalDate()
    when (date) {
        localToday -> today
        localToday.plusDays(1) -> tomorrow
        else -> date.format(DateTimeFormatter.ofPattern(if (locale.language == "de") "EEE, d.M." else "EEE, MMM d", locale))
    }
}.getOrDefault(isoDate)

@StringRes
internal fun weatherConditionRes(code: Int?): Int = when (code) {
    0 -> R.string.weather_clear
    1 -> R.string.weather_mostly_clear
    2 -> R.string.weather_partly_cloudy
    3 -> R.string.weather_overcast
    45, 48 -> R.string.weather_fog
    51, 53, 55 -> R.string.weather_drizzle
    56, 57 -> R.string.weather_freezing_drizzle
    61, 63, 65 -> R.string.weather_rain
    66, 67 -> R.string.weather_freezing_rain
    71, 73, 75, 77 -> R.string.weather_snow
    80, 81, 82 -> R.string.weather_showers
    85, 86 -> R.string.weather_snow_showers
    95 -> R.string.weather_thunderstorm
    96, 99 -> R.string.weather_thunderstorm_hail
    else -> R.string.weather_unknown
}
