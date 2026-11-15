package com.ambient.tv.data.weather

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

const val WEATHER_CACHE_TTL_MILLIS = 30 * 60 * 1_000L

@Serializable
data class WeatherCurrent(
    val timeEpochSeconds: Long,
    val temperature: Double?,
    val apparentTemperature: Double?,
    val weatherCode: Int?,
    val isDay: Boolean,
    val humidity: Double?,
    val windSpeed: Double?,
    val windDirection: Double?,
)

@Serializable
data class WeatherHour(
    val timeEpochSeconds: Long,
    val temperature: Double?,
    val weatherCode: Int?,
    val isDay: Boolean,
    val precipitationProbability: Double?,
    val precipitation: Double?,
)

@Serializable
data class WeatherDay(
    /** Calendar day of the API's daily aggregation, not the UTC date of its midnight. */
    val date: String,
    val timeEpochSeconds: Long,
    val weatherCode: Int?,
    val temperatureMax: Double?,
    val temperatureMin: Double?,
    val precipitationProbability: Double?,
    val precipitation: Double?,
    val windSpeedMax: Double?,
    val windGustsMax: Double?,
    val uvIndexMax: Double?,
    val sunriseEpochSeconds: Long?,
    val sunsetEpochSeconds: Long?,
)

@Serializable
data class WeatherSnapshot(
    val location: WeatherLocation,
    val timezone: String,
    val temperatureUnit: String,
    val windUnit: String,
    val fetchedAtMillis: Long,
    val current: WeatherCurrent?,
    val hourly: List<WeatherHour>,
    val daily: List<WeatherDay>,
) {
    fun isStale(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - fetchedAtMillis !in 0 until WEATHER_CACHE_TTL_MILLIS

    fun matches(settings: WeatherSettings): Boolean = settings.selectedLocation?.let { place ->
        location.id == place.id && location.latitude == place.latitude && location.longitude == place.longitude &&
            location.timezone == place.timezone && temperatureUnit == settings.temperatureUnit && windUnit == settings.windUnit
    } == true
}

enum class WeatherError { DISABLED, LOCAL_ONLY, OFFLINE, RATE_LIMITED, UNAVAILABLE, INVALID_DATA }

data class WeatherState(
    val data: WeatherSnapshot? = null,
    val loading: Boolean = false,
    val error: WeatherError? = null,
    /** Absolute epoch milliseconds; a blocked retry must not reset the stored image/data age. */
    val retryAfterMillis: Long? = null,
)

class WeatherRequestException(val reason: WeatherError, val retryAfterMillis: Long? = null) :
    java.io.IOException(reason.name)

/** Includes coordinates and units: an edited/imported place ID must not reuse a different forecast. */
internal fun weatherCacheKey(location: WeatherLocation, settings: WeatherSettings): String =
    listOf(location.id, location.latitude, location.longitude, location.timezone,
        settings.temperatureUnit, settings.windUnit).joinToString("|")

/** Pure parser shared by the network boundary and focused JVM tests. Unknown/missing values remain unknown. */
internal fun parseWeatherForecast(
    text: String,
    location: WeatherLocation,
    settings: WeatherSettings,
    fetchedAtMillis: Long,
): WeatherSnapshot {
    val root = Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("Expected weather object")
    require((root["error"] as? JsonPrimitive)?.booleanOrNull != true)
    val timezone = root.string("timezone")?.let { runCatching { ZoneId.of(it).id }.getOrNull() } ?: location.timezone
    val offset = root.number("utc_offset_seconds")?.toInt()?.takeIf { it in -64_800..64_800 } ?: 0
    val dailyOffset = ZoneOffset.ofTotalSeconds(offset)
    val current = (root["current"] as? JsonObject)?.let { value ->
        value.epoch("time")?.let { time ->
            WeatherCurrent(time, value.number("temperature_2m"), value.number("apparent_temperature"),
                value.code("weather_code"), value.number("is_day") == 1.0, value.number("relative_humidity_2m")?.coerceIn(0.0, 100.0),
                value.number("wind_speed_10m")?.coerceAtLeast(0.0), value.number("wind_direction_10m"))
        }
    }
    val hourly = (root["hourly"] as? JsonObject)?.let { value ->
        value.array("time").mapIndexedNotNull { index, element ->
            element.epoch()?.let { time ->
                WeatherHour(time, value.numberAt("temperature_2m", index), value.codeAt("weather_code", index),
                    value.numberAt("is_day", index) == 1.0,
                    value.numberAt("precipitation_probability", index)?.coerceIn(0.0, 100.0),
                    value.numberAt("precipitation", index)?.coerceAtLeast(0.0))
            }
        }.distinctBy { it.timeEpochSeconds }.sortedBy { it.timeEpochSeconds }.take(192)
    }.orEmpty()
    val daily = (root["daily"] as? JsonObject)?.let { value ->
        value.array("time").mapIndexedNotNull { index, element ->
            element.epoch()?.let { time ->
                // Open-Meteo explicitly uses the request's fixed offset for every daily boundary.
                // Converting those boundaries with a changing DST offset would shift some labels a day.
                val date = Instant.ofEpochSecond(time).atOffset(dailyOffset).toLocalDate().toString()
                WeatherDay(date, time, value.codeAt("weather_code", index), value.numberAt("temperature_2m_max", index),
                    value.numberAt("temperature_2m_min", index),
                    value.numberAt("precipitation_probability_max", index)?.coerceIn(0.0, 100.0),
                    value.numberAt("precipitation_sum", index)?.coerceAtLeast(0.0),
                    value.numberAt("wind_speed_10m_max", index)?.coerceAtLeast(0.0),
                    value.numberAt("wind_gusts_10m_max", index)?.coerceAtLeast(0.0),
                    value.numberAt("uv_index_max", index)?.coerceAtLeast(0.0),
                    value.array("sunrise").getOrNull(index).epoch(), value.array("sunset").getOrNull(index).epoch())
            }
        }.distinctBy { it.date }.sortedBy { it.date }.take(7)
    }.orEmpty()
    require(current?.temperature != null || hourly.any { it.temperature != null } || daily.any { it.temperatureMax != null }) {
        "Empty weather response"
    }
    return WeatherSnapshot(location, timezone, settings.temperatureUnit, settings.windUnit, fetchedAtMillis, current, hourly, daily)
}

internal fun parseWeatherLocations(text: String): List<WeatherLocation> {
    val root = Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("Expected location object")
    require((root["error"] as? JsonPrimitive)?.booleanOrNull != true)
    return root.array("results").mapNotNull { item ->
        val value = item as? JsonObject ?: return@mapNotNull null
        val latitude = value.number("latitude") ?: return@mapNotNull null
        val longitude = value.number("longitude") ?: return@mapNotNull null
        WeatherLocation(
            id = value.string("id")?.let { "geonames:$it" } ?: "coordinates:$latitude,$longitude",
            name = value.string("name").orEmpty(), country = value.string("country").orEmpty(),
            region = value.string("admin1").orEmpty(), latitude = latitude, longitude = longitude,
            timezone = value.string("timezone") ?: "UTC",
        ).normalizedOrNull()
    }.distinctBy { it.id }.take(10)
}

private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(name: String): Double? = (get(name) as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)
private fun JsonObject.code(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull
private fun JsonObject.epoch(name: String): Long? = get(name).epoch()
private fun JsonObject.array(name: String): JsonArray = get(name) as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.numberAt(name: String, index: Int): Double? =
    (array(name).getOrNull(index) as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)
private fun JsonObject.codeAt(name: String, index: Int): Int? = (array(name).getOrNull(index) as? JsonPrimitive)?.intOrNull
private fun JsonElement?.epoch(): Long? = (this as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..32_503_680_000L }

/** Honour both Retry-After forms, with a quiet one-minute minimum when the service omits it. */
internal fun weatherRetryAfterMillis(value: String?, nowMillis: Long): Long {
    val minimum = nowMillis + 60_000L
    val seconds = value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
    if (seconds != null) return runCatching { Math.addExact(nowMillis, Math.multiplyExact(seconds, 1_000L)) }
        .getOrDefault(Long.MAX_VALUE).coerceAtLeast(minimum)
    val date = value?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
    return maxOf(minimum, date ?: minimum)
}
