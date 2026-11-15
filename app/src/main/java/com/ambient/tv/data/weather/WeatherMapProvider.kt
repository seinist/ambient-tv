package com.ambient.tv.data.weather

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan
import kotlinx.serialization.Serializable

enum class WeatherMapKind { RADAR, SATELLITE }

/** Provider ids are persisted independently for radar and satellite. Empty means no selection. */
enum class WeatherMapProvider(
    val id: String,
    val kind: WeatherMapKind,
    val title: String,
    val attribution: String,
    val sourceUrl: String,
    val endpoint: String = "",
    val layer: String = "",
    val daily: Boolean = false,
    val freshnessMillis: Long,
) {
    RAINVIEWER("rainviewer", WeatherMapKind.RADAR, "RainViewer", "RainViewer", "https://www.rainviewer.com/", freshnessMillis = 30 * 60_000L),
    DWD("dwd", WeatherMapKind.RADAR, "DWD", "Deutscher Wetterdienst (DWD)", "https://www.dwd.de/",
        "https://maps.dwd.de/geoserver/ows", "dwd:Radar_wn-analysis_1x1km_ger", freshnessMillis = 30 * 60_000L),
    NASA("nasa_gibs", WeatherMapKind.SATELLITE, "NASA GIBS", "NASA EOSDIS GIBS · NOAA-20 / VIIRS", "https://worldview.earthdata.nasa.gov/",
        "https://gibs.earthdata.nasa.gov/wms/epsg3857/best/wms.cgi", "VIIRS_NOAA20_CorrectedReflectance_TrueColor", true, 48 * 60 * 60_000L),
    EUMETSAT("eumetsat", WeatherMapKind.SATELLITE, "EUMETSAT", "EUMETSAT · Geostationary Ring", "https://view.eumetsat.int/",
        "https://view.eumetsat.int/geoserver/wms", "mumi:worldcloudmap_ir108", freshnessMillis = 6 * 60 * 60_000L);

    companion object {
        fun forKind(kind: WeatherMapKind): List<WeatherMapProvider> = entries.filter { it.kind == kind }
        fun selected(id: String, kind: WeatherMapKind): WeatherMapProvider? = entries.firstOrNull { it.id == id && it.kind == kind }
    }
}

/** One Web-Mercator tile is the shared, exact extent of every image and its base map. */
@Serializable
data class WeatherMapViewport(val zoom: Int, val x: Int, val y: Int) {
    init {
        require(zoom in MIN_ZOOM..MAX_ZOOM)
        require(x in 0 until (1 shl zoom) && y in 0 until (1 shl zoom))
    }
    val key: String get() = "$zoom-$x-$y"
    val tiles: Int get() = 1 shl zoom
    val west: Double get() = x.toDouble() / tiles * 360.0 - 180.0
    val east: Double get() = (x + 1.0) / tiles * 360.0 - 180.0
    val north: Double get() = tileLatitude(y.toDouble(), tiles)
    val south: Double get() = tileLatitude(y + 1.0, tiles)
    val centreLatitude: Double get() = tileLatitude(y + .5, tiles)
    val centreLongitude: Double get() = (x + .5) / tiles * 360.0 - 180.0
    val widthKilometres: Double get() = 40_075.016686 * cos(centreLatitude * PI / 180) / tiles
    val mercatorBounds: String get() {
        val span = 2 * MERCATOR_HALF / tiles
        return listOf(-MERCATOR_HALF + x * span, MERCATOR_HALF - (y + 1) * span,
            -MERCATOR_HALF + (x + 1) * span, MERCATOR_HALF - y * span).joinToString(",")
    }
    fun pan(dx: Int, dy: Int) = copy(x = Math.floorMod(x + dx, tiles), y = (y + dy).coerceIn(0, tiles - 1))
    fun zoomed(delta: Int) = at(centreLatitude, centreLongitude, (zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM))
    fun marker(latitude: Double, longitude: Double): Pair<Float, Float>? {
        val px = longitudeX(longitude, tiles) - x
        val py = latitudeY(latitude, tiles) - y
        return if (px in 0.0..1.0 && py in 0.0..1.0) px.toFloat() to py.toFloat() else null
    }
    fun intersects(bounds: WeatherMapBounds): Boolean = west < bounds.east && east > bounds.west && south < bounds.north && north > bounds.south
    companion object {
        const val MIN_ZOOM = 2
        const val MAX_ZOOM = 7 // RainViewer public API limit, also keeps every provider interchangeable.
        private const val MERCATOR_HALF = 20_037_508.342789244
        fun at(latitude: Double, longitude: Double, zoom: Int = 6): WeatherMapViewport {
            require(latitude.isFinite() && longitude.isFinite())
            val level = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
            val count = 1 shl level
            return WeatherMapViewport(level, floor(longitudeX(longitude, count)).toInt().coerceIn(0, count - 1),
                floor(latitudeY(latitude, count)).toInt().coerceIn(0, count - 1))
        }
        private fun longitudeX(longitude: Double, tiles: Int) = (longitude.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * tiles
        private fun latitudeY(latitude: Double, tiles: Int): Double {
            val radians = latitude.coerceIn(-85.05112878, 85.05112878) * PI / 180
            return (1 - ln(tan(radians) + 1 / cos(radians)) / PI) / 2 * tiles
        }
        private fun tileLatitude(y: Double, tiles: Int) = atan(sinh(PI * (1 - 2 * y / tiles))) * 180 / PI
    }
}

@Serializable
data class WeatherMapBounds(val west: Double = -180.0, val south: Double = -85.05, val east: Double = 180.0, val north: Double = 85.05)

@Serializable
data class WeatherMapFrame(
    val time: String,
    val epochMillis: Long,
    val path: String = "",
    // The manifest controls RainViewer's tile host. The default also reads existing disk caches.
    val tileHost: String = "https://tilecache.rainviewer.com",
)

@Serializable
data class WeatherMapSeries(
    val providerId: String,
    val frames: List<WeatherMapFrame>,
    val fetchedAtMillis: Long,
    val bounds: WeatherMapBounds = WeatherMapBounds(),
)

/** Expands only the tail of a time dimension. Multi-year capabilities never allocate all steps. */
internal fun weatherMapTimes(dimension: String, defaultTime: String?, now: Instant, daily: Boolean): List<WeatherMapFrame> {
    val result = mutableListOf<WeatherMapFrame>()
    fun add(time: String) {
        val epoch = weatherMapInstant(time) ?: return
        if (epoch <= now.plusSeconds(300) && (daily || epoch >= now.minus(Duration.ofDays(3)))) {
            result += WeatherMapFrame(if (daily) epoch.atZone(ZoneOffset.UTC).toLocalDate().toString() else epoch.toString(), epoch.toEpochMilli())
        }
    }
    dimension.split(',').takeLast(32).forEach { item ->
        val parts = item.trim().split('/')
        if (parts.size == 3) {
            val start = weatherMapInstant(parts[0]) ?: return@forEach
            val end = weatherMapInstant(parts[1]) ?: return@forEach
            val step = runCatching { Duration.parse(parts[2]) }.getOrNull()?.takeIf { it.toMillis() >= 60_000 } ?: return@forEach
            var time = minOf(end, now)
            val steps = Duration.between(start, time).toMillis() / step.toMillis()
            time = start.plusMillis(steps.coerceAtLeast(0) * step.toMillis())
            repeat(if (daily) 3 else 13) {
                if (time >= start && time <= end) add(time.toString())
                time = time.minus(step)
            }
        } else if (parts.size == 1) add(parts[0])
    }
    defaultTime?.let(::add)
    return result.distinctBy { it.epochMillis }.sortedBy { it.epochMillis }.takeLast(if (daily) 3 else 13)
}

internal fun weatherMapInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
    ?: runCatching { LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()
