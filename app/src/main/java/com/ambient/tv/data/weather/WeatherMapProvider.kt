package com.ambient.tv.data.weather

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.round
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
        "https://view.eumetsat.int/geoserver/mumi/worldcloudmap_ir108/wms", "mumi:worldcloudmap_ir108", freshnessMillis = 6 * 60 * 60_000L);

    companion object {
        fun forKind(kind: WeatherMapKind): List<WeatherMapProvider> = entries.filter { it.kind == kind }
        fun selected(id: String, kind: WeatherMapKind): WeatherMapProvider? = entries.firstOrNull { it.id == id && it.kind == kind }
    }
}

/**
 * A one-tile-wide Mercator window, with an exact geographic centre rather than a snapped tile.
 * The integer tile and fractional origin keep old cached viewports readable. Every layer uses
 * this same window; only indexed base tiles need cropping around its fractional origin.
 */
@Serializable
data class WeatherMapViewport(
    val zoom: Int,
    val x: Int,
    val y: Int,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
) {
    init {
        require(zoom in MIN_ZOOM..MAX_ZOOM)
        require(x in 0 until (1 shl zoom) && y in 0 until (1 shl zoom))
        require(offsetX.isFinite() && offsetX >= 0.0 && offsetX < 1.0)
        require(offsetY.isFinite() && offsetY >= 0.0 && offsetY < 1.0)
        require(y + offsetY <= (1 shl zoom) - 1.0)
    }
    val key: String get() = if (offsetX == 0.0 && offsetY == 0.0) "$zoom-$x-$y"
        else "$zoom-$x-$y-${offsetX.toBits()}-${offsetY.toBits()}"
    val tiles: Int get() = 1 shl zoom
    private val originX: Double get() = x + offsetX
    private val originY: Double get() = y + offsetY
    val west: Double get() = originX / tiles * 360.0 - 180.0
    val east: Double get() = (originX + 1.0) / tiles * 360.0 - 180.0
    val north: Double get() = tileLatitude(originY, tiles)
    val south: Double get() = tileLatitude(originY + 1.0, tiles)
    val centreLatitude: Double get() = tileLatitude(originY + .5, tiles)
    val centreLongitude: Double get() = (originX + .5).mod(tiles.toDouble()) / tiles * 360.0 - 180.0
    val widthKilometres: Double get() = 40_075.016686 * cos(centreLatitude * PI / 180) / tiles
    val mercatorBounds: String get() = mercatorBounds(originX, originX + 1.0)

    private fun mercatorBounds(left: Double, right: Double): String {
        val span = 2 * MERCATOR_HALF / tiles
        return listOf(-MERCATOR_HALF + left * span, MERCATOR_HALF - (originY + 1) * span,
            -MERCATOR_HALF + right * span, MERCATOR_HALF - originY * span).joinToString(",")
    }

    /** WMS servers receive bounded strips instead of a box extending beyond the dateline. */
    internal fun mercatorParts(): List<WeatherMapMercatorPart> {
        val firstWidth = minOf(1.0, tiles - originX)
        val first = WeatherMapMercatorPart(mercatorBounds(originX, originX + firstWidth), 0f, firstWidth.toFloat())
        return if (firstWidth == 1.0) listOf(first) else listOf(first,
            WeatherMapMercatorPart(mercatorBounds(0.0, 1.0 - firstWidth), firstWidth.toFloat(), 1f))
    }

    /** Only tiles visible in this window, with their destination positions before clipping. */
    internal fun baseTiles(): List<WeatherMapBaseTile> = buildList {
        for (row in 0..(if (offsetY > 0.0) 1 else 0)) {
            for (column in 0..(if (offsetX > 0.0) 1 else 0)) {
                add(WeatherMapBaseTile(WeatherMapViewport(zoom, (x + column) % tiles, y + row),
                    (column - offsetX).toFloat(), (row - offsetY).toFloat()))
            }
        }
    }

    fun pan(dx: Int, dy: Int): WeatherMapViewport {
        val targetY = y.toLong() + dy
        val nextY = targetY.coerceIn(0L, tiles - 1L).toInt()
        return copy(x = Math.floorMod(x.toLong() + dx, tiles.toLong()).toInt(), y = nextY,
            offsetY = if (targetY < 0 || targetY >= tiles - 1) 0.0 else offsetY)
    }
    fun zoomed(delta: Int): WeatherMapViewport {
        val level = (zoom.toLong() + delta).coerceIn(MIN_ZOOM.toLong(), MAX_ZOOM.toLong()).toInt()
        return if (level == zoom) this else at(centreLatitude, centreLongitude, level)
    }
    fun marker(latitude: Double, longitude: Double): Pair<Float, Float>? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        fun edge(value: Double): Double = when {
            abs(value) < 1e-10 -> 0.0
            abs(value - 1.0) < 1e-10 -> 1.0
            else -> value
        }
        val wrappedX = (longitudeX(longitude, tiles) - originX).mod(tiles.toDouble())
        val px = edge(if (abs(wrappedX - tiles) < 1e-10) 0.0 else wrappedX)
        val py = edge(latitudeY(latitude, tiles) - originY)
        return if (px in 0.0..1.0 && py in 0.0..1.0) px.toFloat() to py.toFloat() else null
    }
    fun intersects(bounds: WeatherMapBounds): Boolean {
        if (south >= bounds.north || north <= bounds.south) return false
        fun longitudeOverlap(left: Double, right: Double): Boolean =
            if (bounds.west <= bounds.east) left < bounds.east && right > bounds.west
            else left < bounds.east || right > bounds.west
        return longitudeOverlap(west, minOf(180.0, east)) ||
            (east > 180.0 && longitudeOverlap(-180.0, east - 360.0))
    }
    companion object {
        const val MIN_ZOOM = 2
        const val MAX_ZOOM = 7 // RainViewer public API limit, also keeps every provider interchangeable.
        private const val MERCATOR_HALF = 20_037_508.342789244
        fun at(latitude: Double, longitude: Double, zoom: Int = 6): WeatherMapViewport {
            require(latitude.isFinite() && longitude.isFinite())
            val level = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
            val count = 1 shl level
            return fromOrigin(level, longitudeX(longitude, count) - .5, latitudeY(latitude, count) - .5)
        }
        private fun fromOrigin(zoom: Int, x: Double, y: Double): WeatherMapViewport {
            val count = 1 shl zoom
            // Projection round-off must not manufacture tiny extra strips at exact tile edges.
            fun snap(value: Double): Double = round(value).takeIf { abs(value - it) < 1e-10 } ?: value
            val left = snap(x).mod(count.toDouble())
            val top = snap(y).coerceIn(0.0, count - 1.0)
            val tileX = floor(left).toInt()
            val tileY = floor(top).toInt()
            return WeatherMapViewport(zoom, tileX, tileY, left - tileX, top - tileY)
        }
        private fun longitudeX(longitude: Double, tiles: Int) = (longitude.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * tiles
        private fun latitudeY(latitude: Double, tiles: Int): Double {
            val radians = latitude.coerceIn(-85.05112878, 85.05112878) * PI / 180
            return ((1 - ln(tan(radians) + 1 / cos(radians)) / PI) / 2 * tiles).coerceIn(0.0, tiles.toDouble())
        }
        private fun tileLatitude(y: Double, tiles: Int) = atan(sinh(PI * (1 - 2 * y / tiles))) * 180 / PI
    }
}

internal data class WeatherMapMercatorPart(val bounds: String, val left: Float, val right: Float)
internal data class WeatherMapBaseTile(val viewport: WeatherMapViewport, val left: Float, val top: Float)

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
