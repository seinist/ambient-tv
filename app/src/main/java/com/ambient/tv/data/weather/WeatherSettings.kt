package com.ambient.tv.data.weather

import java.time.ZoneId
import kotlinx.serialization.Serializable

/** A deliberately selected place, never a continuously tracked device position. */
@Serializable
data class WeatherLocation(
    val id: String,
    val name: String,
    val country: String,
    val region: String = "",
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
) {
    fun normalizedOrNull(): WeatherLocation? {
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0 ||
            id.isBlank() || name.isBlank()
        ) return null
        val zone = runCatching { ZoneId.of(timezone).id }.getOrNull() ?: return null
        return copy(id = id.trim().take(100), name = name.trim().take(120),
            country = country.trim().take(100), region = region.trim().take(120), timezone = zone)
    }
}

@Serializable
data class WeatherSettings(
    val enabled: Boolean = false,
    val showInSidebar: Boolean = true,
    val locations: List<WeatherLocation> = emptyList(),
    val selectedLocationId: String? = null,
    val showInHeader: Boolean = true,
    val showInScreensaver: Boolean = false,
    val temperatureUnit: String = "celsius",
    val windUnit: String = "kmh",
    /** Existing backup keys support one id or ordered comma-separated ids. Empty means no selection. */
    val radarProvider: String = "",
    val satelliteProvider: String = "",
) {
    val selectedLocation: WeatherLocation?
        get() = locations.firstOrNull { it.id == selectedLocationId } ?: locations.firstOrNull()

    fun selectedMapProviders(kind: WeatherMapKind): List<WeatherMapProvider> =
        WeatherMapProvider.selectedMany(
            if (kind == WeatherMapKind.RADAR) radarProvider else satelliteProvider, kind)

    /** Toggle a source without replacing the other selected providers or changing their order. */
    fun withMapProvider(kind: WeatherMapKind, providerId: String, enabled: Boolean): WeatherSettings {
        if (WeatherMapProvider.selected(providerId, kind) == null) return this
        val ids = selectedMapProviders(kind).map { it.id }.toMutableList()
        if (enabled && providerId !in ids) ids += providerId
        if (!enabled) ids.remove(providerId)
        val value = ids.joinToString(",")
        return when (kind) {
            WeatherMapKind.RADAR -> copy(radarProvider = value)
            WeatherMapKind.SATELLITE -> copy(satelliteProvider = value)
        }
    }

    fun normalized(): WeatherSettings {
        val validPlaces = locations.mapNotNull(WeatherLocation::normalizedOrNull).distinctBy { it.id }.take(MAX_LOCATIONS)
        return copy(
            locations = validPlaces,
            selectedLocationId = selectedLocationId?.takeIf { id -> validPlaces.any { it.id == id } }
                ?: validPlaces.firstOrNull()?.id,
            temperatureUnit = temperatureUnit.takeIf { it in TEMPERATURE_UNITS } ?: "celsius",
            windUnit = windUnit.takeIf { it in WIND_UNITS } ?: "kmh",
            radarProvider = WeatherMapProvider.canonicalIds(radarProvider, WeatherMapKind.RADAR),
            satelliteProvider = WeatherMapProvider.canonicalIds(satelliteProvider, WeatherMapKind.SATELLITE),
        )
    }

    companion object {
        const val MAX_LOCATIONS = 5
        val TEMPERATURE_UNITS = setOf("celsius", "fahrenheit")
        val WIND_UNITS = setOf("kmh", "ms", "mph", "kn")
        val RADAR_PROVIDERS = setOf("rainviewer", "dwd")
        val SATELLITE_PROVIDERS = setOf("nasa_gibs", "eumetsat")
    }
}
