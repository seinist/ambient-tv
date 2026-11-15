package com.ambient.tv.data.weather

import java.time.Instant
import java.util.Locale
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class WeatherMapTest {
    private val now = Instant.parse("2026-11-15T15:00:00Z")

    @Test fun `providers require a manual selection in the correct category`() {
        assertNull(WeatherMapProvider.selected("", WeatherMapKind.RADAR))
        assertNull(WeatherMapProvider.selected("missing", WeatherMapKind.SATELLITE))
        assertNull(WeatherMapProvider.selected("rainviewer", WeatherMapKind.SATELLITE))
        assertEquals(WeatherMapProvider.DWD, WeatherMapProvider.selected("dwd", WeatherMapKind.RADAR))
        assertEquals(listOf("nasa_gibs", "eumetsat"), WeatherMapProvider.forKind(WeatherMapKind.SATELLITE).map { it.id })
    }

    @Test fun `all providers use the same mercator viewport and location marker`() {
        val view = WeatherMapViewport.at(50.98, 11.03)
        assertEquals(6, view.zoom)
        assertEquals(33, view.x)
        assertEquals(20, view.y)
        val marker = view.marker(50.98, 11.03)!!
        assertEquals(.5f, marker.first, .000001f)
        assertEquals(.5f, marker.second, .000001f)
        assertNull(view.marker(-33.87, 151.21))
        assertTrue(view.intersects(WeatherMapBounds(1.46, 45.68, 18.73, 56.22)))
        assertFalse(WeatherMapViewport.at(-33.87, 151.21).intersects(WeatherMapBounds(1.46, 45.68, 18.73, 56.22)))
        val bbox = view.mercatorBounds.split(',').map(String::toDouble)
        assertTrue(bbox[0] < bbox[2] && bbox[1] < bbox[3])
        assertEquals(bbox[2] - bbox[0], bbox[3] - bbox[1], .0001)
        assertTrue(view.widthKilometres in 350.0..450.0)
        assertEquals(0, WeatherMapViewport(7, 127, 0).pan(1, -1).x)
        assertEquals(0, WeatherMapViewport(7, 127, 0).pan(1, -1).y)
        assertEquals(7, view.zoomed(99).zoom)
        assertEquals(2, view.zoomed(-99).zoom)
        assertNotEquals(view.key, view.pan(1, 0).key)
    }

    @Test fun `location stays exactly centred through all zoom levels`() {
        listOf(50.98 to 11.03, -33.87 to 151.21, 40.71 to -74.01, 35.68 to 139.76,
            0.0 to 0.0, 10.0 to 179.9, -10.0 to -179.9).forEach { (latitude, longitude) ->
            for (zoom in WeatherMapViewport.MIN_ZOOM..WeatherMapViewport.MAX_ZOOM) {
                val view = WeatherMapViewport.at(latitude, longitude, zoom)
                assertEquals(latitude, view.centreLatitude, .000000001)
                assertEquals(longitude, view.centreLongitude, .000000001)
                val marker = checkNotNull(view.marker(latitude, longitude))
                assertEquals(.5f, marker.first, .000001f)
                assertEquals(.5f, marker.second, .000001f)
                val returned = view.pan(-1, 0).pan(1, 0)
                assertEquals(view.key, returned.key)
                assertEquals(latitude, returned.centreLatitude, .000000001)
                assertEquals(longitude, returned.centreLongitude, .000000001)
                if (zoom < WeatherMapViewport.MAX_ZOOM) {
                    var restored = view
                    repeat(4) {
                        val closer = restored.zoomed(1)
                        assertEquals(latitude, closer.centreLatitude, .000000001)
                        assertEquals(longitude, closer.centreLongitude, .000000001)
                        restored = closer.zoomed(-1)
                        assertEquals(latitude, restored.centreLatitude, .000000001)
                        assertEquals(longitude, restored.centreLongitude, .000000001)
                    }
                }
            }
        }
    }

    @Test fun `dateline windows wrap markers and request bounded WMS strips`() {
        val frame = WeatherMapFrame(now.toString(), now.toEpochMilli())
        for (longitude in listOf(180.0, -180.0, 179.9, -179.9)) {
            val view = WeatherMapViewport.at(10.0, longitude)
            val marker = checkNotNull(view.marker(10.0, longitude))
            assertEquals(.5f, marker.first, .000001f)
            assertEquals(.5f, marker.second, .000001f)
            assertTrue(view.intersects(WeatherMapBounds(179.0, 0.0, 180.0, 20.0)))
            assertTrue(view.intersects(WeatherMapBounds(-180.0, 0.0, -179.0, 20.0)))
            assertFalse(view.intersects(WeatherMapBounds(-10.0, 0.0, 10.0, 20.0)))
            for (provider in listOf(WeatherMapProvider.DWD, WeatherMapProvider.NASA, WeatherMapProvider.EUMETSAT)) {
                val parts = weatherMapImageParts(provider, view, frame)
                assertEquals(2, parts.size)
                assertEquals(0f, parts.first().left, 0f)
                assertEquals(1f, parts.last().right, 0f)
                assertEquals(parts.first().right, parts.last().left, 0f)
                assertEquals(512, parts.sumOf { it.url.toHttpUrl().queryParameter("WIDTH")!!.toInt() })
                parts.forEach { part ->
                    val bounds = part.url.toHttpUrl().queryParameter("BBOX")!!.split(',').map(String::toDouble)
                    assertTrue(bounds.all { it.isFinite() && it in -20_037_508.34278925..20_037_508.34278925 })
                    assertTrue(bounds[0] < bounds[2] && bounds[1] < bounds[3])
                    assertEquals(0f, part.top, 0f)
                    assertEquals(1f, part.bottom, 0f)
                }
            }
        }
    }

    @Test fun `base tiles exactly cover centred windows and remain registered with their marker`() {
        for ((latitude, longitude) in listOf(50.98 to 11.03, -33.87 to 151.21, 0.0 to 0.0,
            10.0 to 179.9, -10.0 to -179.9, 90.0 to 0.0, -90.0 to 0.0)) {
            val view = WeatherMapViewport.at(latitude, longitude)
            val parts = weatherBaseMapParts(view)
            assertTrue(parts.size in 1..4)
            val area = parts.sumOf { part ->
                ((minOf(1f, part.right) - maxOf(0f, part.left)) *
                    (minOf(1f, part.bottom) - maxOf(0f, part.top))).toDouble()
            }
            assertEquals(1.0, area, .000001)
            val marker = checkNotNull(view.marker(latitude, longitude))
            val positions = parts.mapNotNull { part ->
                val path = part.url.toHttpUrl().pathSegments
                val tile = WeatherMapViewport(path[0].toInt(), path[1].toInt(), path[2].substringBefore('.').toInt())
                tile.marker(latitude, longitude)?.let { (x, y) -> (part.left + x) to (part.top + y) }
            }
            assertTrue(positions.any { (x, y) -> kotlin.math.abs(x - marker.first) < .000001f && kotlin.math.abs(y - marker.second) < .000001f })
        }
    }

    @Test fun `polar navigation clamps the full window and never creates an invalid tile`() {
        for (latitude in listOf(90.0, -90.0)) {
            for (zoom in WeatherMapViewport.MIN_ZOOM..WeatherMapViewport.MAX_ZOOM) {
                val view = WeatherMapViewport.at(latitude, 179.9, zoom)
                assertNotNull(view.marker(latitude, 179.9))
                val blocked = view.pan(0, if (latitude > 0.0) -1 else 1)
                assertEquals(view, blocked)
                var moved = view
                repeat(256) {
                    moved = moved.pan(-1, if (latitude > 0.0) 1 else -1)
                    assertTrue(weatherBaseMapParts(moved).size in 1..4)
                    val bounds = moved.mercatorBounds.split(',').map(String::toDouble)
                    assertTrue(bounds[1] >= -20_037_508.34278925 && bounds[3] <= 20_037_508.34278925)
                }
                assertEquals(if (latitude > 0.0) moved.tiles - 1 else 0, moved.y)
                assertEquals(0.0, moved.offsetY, 0.0)
            }
        }
    }

    @Test fun `cached tile views remain readable and fractional positions have distinct keys`() {
        val cached = Json.decodeFromString<WeatherMapViewport>("""{"zoom":6,"x":33,"y":21}""")
        assertEquals(WeatherMapViewport(6, 33, 21), cached)
        assertEquals("6-33-21", cached.key)
        assertEquals(1, weatherBaseMapParts(cached).size)
        val first = WeatherMapViewport.at(50.98, 11.03)
        val second = WeatherMapViewport.at(50.99, 11.04)
        assertEquals(first.x, second.x)
        assertEquals(first.y, second.y)
        assertNotEquals(first.key, second.key)
        assertThrows(IllegalArgumentException::class.java) { WeatherMapViewport(6, 0, 63, offsetY = .1) }
        assertThrows(IllegalArgumentException::class.java) { WeatherMapViewport(6, 0, 0, offsetX = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { WeatherMapViewport(6, 0, 0, offsetY = 1.0) }
    }

    @Test fun `radar and coverage use the same single centred request regardless of device locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            for ((latitude, longitude) in listOf(50.98 to 11.03, 0.0 to 0.0, 10.0 to 179.9)) {
                val view = WeatherMapViewport.at(latitude, longitude)
                val frame = WeatherMapFrame(now.toString(), now.toEpochMilli(), "/v2/radar/3fbfe8dfae28")
                val parts = weatherMapImageParts(WeatherMapProvider.RAINVIEWER, view, frame)
                assertEquals(1, parts.size)
                val radar = parts.single().url.toHttpUrl().pathSegments
                val coverage = weatherMapCoverageUrl(view).toHttpUrl().pathSegments
                assertEquals(radar.subList(3, 7), coverage.subList(3, 7))
                assertTrue(radar[5].contains('.') && radar[6].contains('.'))
                assertEquals(latitude, radar[5].toDouble(), .000000001)
                assertEquals(longitude, radar[6].toDouble(), .000000001)
            }
        } finally { Locale.setDefault(original) }
    }

    @Test(expected = IllegalArgumentException::class) fun `rainviewer maximum zoom cannot be bypassed by constructing a viewport`() {
        WeatherMapViewport(8, 0, 0)
    }

    @Test fun `radar manifest ignores nowcast satellites unsafe paths and future frames`() {
        val latest = now.epochSecond
        val series = parseWeatherMapSeries(WeatherMapProvider.RAINVIEWER, """{
          "host":"https://tilecache.rainviewer.com",
          "radar":{"past":[
            {"time":${latest - 600},"path":"/v2/radar/${latest - 600}"},
            {"time":$latest,"path":"/v2/radar/3fbfe8dfae28"},
            {"time":$latest,"path":"/v2/radar/3fbfe8dfae28"},
            {"time":${latest - 1200},"path":"/../../other"},
            {"time":${latest + 3600},"path":"/v2/radar/${latest + 3600}"}
          ],"nowcast":[{"time":${latest + 600},"path":"/v2/radar/${latest + 600}"}]},
          "satellite":{"infrared":[{"time":$latest,"path":"/v2/satellite/$latest"}]}
        }""", now)
        assertEquals(2, series.frames.size)
        assertEquals(latest * 1000, series.frames.last().epochMillis)
        assertEquals("/v2/radar/3fbfe8dfae28", series.frames.last().path)
        val url = weatherMapImageUrl(WeatherMapProvider.RAINVIEWER, WeatherMapViewport.at(50.98, 11.03), series.frames.last())
        assertEquals("tilecache.rainviewer.com", url.toHttpUrl().host)
        assertTrue(url.contains("/v2/radar/3fbfe8dfae28/"))
        assertTrue(url.endsWith("/512/6/50.9800000000/11.0300000000/2/1_0.png"))
    }

    @Test fun `radar uses the published tile host for frames and coverage`() {
        val series = parseWeatherMapSeries(WeatherMapProvider.RAINVIEWER, """{
          "host":"https://tiles.rainviewer.com/",
          "radar":{"past":[{"time":${now.epochSecond},"path":"/v2/radar/frame_A-b123"}]}
        }""", now)
        val frame = series.frames.single()
        val view = WeatherMapViewport.at(50.98, 11.03)
        assertEquals("https://tiles.rainviewer.com", frame.tileHost)
        assertEquals("tiles.rainviewer.com", weatherMapImageUrl(WeatherMapProvider.RAINVIEWER, view, frame).toHttpUrl().host)
        assertEquals("tiles.rainviewer.com", weatherMapCoverageUrl(view, frame.tileHost).toHttpUrl().host)
    }

    @Test fun `existing frame caches without a tile host still load`() {
        val frame = Json.decodeFromString<WeatherMapFrame>("""{
          "time":"2026-11-15T15:00:00Z", "epochMillis":${now.toEpochMilli()}, "path":"/v2/radar/${now.epochSecond}"
        }""")
        assertEquals("https://tilecache.rainviewer.com", frame.tileHost)
        assertEquals("tilecache.rainviewer.com", weatherMapImageUrl(
            WeatherMapProvider.RAINVIEWER, WeatherMapViewport.at(50.98, 11.03), frame).toHttpUrl().host)
    }

    @Test fun `radar manifests cannot redirect image requests to unrelated or authenticated hosts`() {
        listOf("https://unexpected.invalid", "http://tilecache.rainviewer.com",
            "https://rainviewer.com.evil.invalid", "https://127.0.0.1", "https://tilecache.rainviewer.com:444",
            "https://user:secret@tilecache.rainviewer.com", "https://tilecache.rainviewer.com/path",
            "https://tilecache.rainviewer.com/?key=value", "https://tilecache.rainviewer.com/#fragment").forEach { host ->
            assertThrows("Unsafe manifest host: $host", IllegalArgumentException::class.java) {
                parseWeatherMapSeries(WeatherMapProvider.RAINVIEWER, """{
                  "host":"$host", "radar":{"past":[{"time":${now.epochSecond},"path":"/v2/radar/3fbfe8dfae28"}]}
                }""", now)
            }
        }
    }

    @Test fun `opaque radar IDs cannot escape the image path even when read from a cache`() {
        val view = WeatherMapViewport.at(50.98, 11.03)
        listOf("/v2/radar/../other", "/v2/radar/%2e%2e", "/v2/radar/id?query=1",
            "/v2/radar/id#fragment", "https://unexpected.invalid/v2/radar/id", "/v2/radar/",
            "/v2/radar/" + "x".repeat(129)).forEach { path ->
            val frame = WeatherMapFrame(now.toString(), now.toEpochMilli(), path)
            assertThrows("Unsafe cached frame path: $path", IllegalArgumentException::class.java) {
                weatherMapImageUrl(WeatherMapProvider.RAINVIEWER, view, frame)
            }
        }
        val frame = WeatherMapFrame(now.toString(), now.toEpochMilli(), "/v2/radar/3fbfe8dfae28", "https://unexpected.invalid")
        assertThrows(IllegalArgumentException::class.java) { weatherMapImageUrl(WeatherMapProvider.RAINVIEWER, view, frame) }
    }

    @Test fun `scoped DWD capabilities resolve unprefixed layer times and latitude longitude axes`() {
        val series = parseWeatherMapSeries(WeatherMapProvider.DWD, dwdCapabilities, now)
        assertEquals(13, series.frames.size)
        assertEquals("2026-11-15T14:45:00Z", series.frames.last().time)
        assertEquals("2026-11-15T13:45:00Z", series.frames.first().time)
        assertEquals(WeatherMapBounds(1.4633, 45.6846, 18.7316, 56.2194), series.bounds)
        val url = weatherMapImageUrl(WeatherMapProvider.DWD, WeatherMapViewport.at(50.98, 11.03), series.frames.last()).toHttpUrl()
        assertEquals("EPSG:3857", url.queryParameter("CRS"))
        assertEquals("dwd:Radar_wn-analysis_1x1km_ger", url.queryParameter("LAYERS"))
        assertEquals("2026-11-15T14:45:00Z", url.queryParameter("TIME"))
        assertEquals("512", url.queryParameter("WIDTH"))
        assertEquals("", url.queryParameter("STYLES"))
    }

    @Test fun `EUMETSAT global product keeps three hour times and real bounds`() {
        val xml = """<WMS_Capabilities xmlns="http://www.opengis.net/wms"><Capability><Layer>
          <Name>worldcloudmap_ir108</Name>
          <EX_GeographicBoundingBox><westBoundLongitude>-180</westBoundLongitude><eastBoundLongitude>180</eastBoundLongitude>
          <southBoundLatitude>-89.9999</southBoundLatitude><northBoundLatitude>89.9999</northBoundLatitude></EX_GeographicBoundingBox>
          <Dimension name="time" default="2026-11-15T12:00:00Z">2021-01-01T00:00:00Z/2026-11-15T12:00:00Z/PT3H</Dimension>
          </Layer></Capability></WMS_Capabilities>"""
        val series = parseWeatherMapSeries(WeatherMapProvider.EUMETSAT, xml, now)
        assertEquals(13, series.frames.size)
        assertEquals(3 * 60 * 60_000L, series.frames[1].epochMillis - series.frames[0].epochMillis)
        assertEquals(WeatherMapBounds(-180.0, -89.9999, 180.0, 89.9999), series.bounds)
        assertTrue(WeatherMapViewport.at(-33.87, 151.21).intersects(series.bounds))
    }

    @Test fun `EUMETSAT images use the scoped product endpoint for centred and wrapped views`() {
        val frame = WeatherMapFrame(now.toString(), now.toEpochMilli())
        for (view in listOf(WeatherMapViewport.at(50.98, 11.03), WeatherMapViewport.at(10.0, 179.9))) {
            weatherMapImageParts(WeatherMapProvider.EUMETSAT, view, frame).forEach { part ->
                val url = part.url.toHttpUrl()
                assertEquals("https", url.scheme)
                assertEquals("view.eumetsat.int", url.host)
                assertEquals("/geoserver/mumi/worldcloudmap_ir108/wms", url.encodedPath)
                assertEquals("GetMap", url.queryParameter("REQUEST"))
                assertEquals("mumi:worldcloudmap_ir108", url.queryParameter("LAYERS"))
                assertEquals("EPSG:3857", url.queryParameter("CRS"))
            }
        }
    }

    @Test fun `NASA domains are daily UTC images rather than live radar timestamps`() {
        val xml = """<Domains><DimensionDomain><Domain>2026-11-08/2026-11-15/P1D</Domain></DimensionDomain></Domains>"""
        val series = parseWeatherMapSeries(WeatherMapProvider.NASA, xml, now)
        assertEquals(listOf("2026-11-13", "2026-11-14", "2026-11-15"), series.frames.map { it.time })
        assertEquals(24 * 60 * 60_000L, series.frames[1].epochMillis - series.frames[0].epochMillis)
        assertTrue(weatherMapCapabilitiesUrl(WeatherMapProvider.NASA, now).endsWith("/2026-11-08--2026-11-15.xml"))
        val url = weatherMapImageUrl(WeatherMapProvider.NASA, WeatherMapViewport.at(50.98, 11.03), series.frames.last()).toHttpUrl()
        assertEquals("2026-11-15", url.queryParameter("TIME"))
        assertEquals("VIIRS_NOAA20_CorrectedReflectance_TrueColor", url.queryParameter("LAYERS"))
    }

    @Test fun `future time steps are excluded and huge dimensions stay bounded`() {
        val times = weatherMapTimes("2000-01-01T00:00:00Z/2030-01-01T00:00:00Z/PT5M", "2030-01-01T00:00:00Z", now, false)
        assertEquals(13, times.size)
        assertEquals(now.toEpochMilli(), times.last().epochMillis)
        assertTrue(times.all { it.epochMillis <= now.toEpochMilli() })
    }

    @Test(expected = IllegalArgumentException::class) fun `external XML entities are rejected`() {
        parseWeatherMapSeries(WeatherMapProvider.DWD,
            """<!DOCTYPE x [<!ENTITY e SYSTEM "https://unexpected.invalid/">]><WMS_Capabilities>&e;</WMS_Capabilities>""", now)
    }

    private val dwdCapabilities = """<WMS_Capabilities xmlns="http://www.opengis.net/wms"><Capability><Layer>
      <Name>Radar</Name><Dimension name="time" default="2026-11-15T15:00:00Z">2026-11-15T15:00:00Z</Dimension>
      <Layer><Name>Radar_wn-analysis_1x1km_ger</Name>
      <BoundingBox CRS="EPSG:4326" minx="45.6846" miny="1.4633" maxx="56.2194" maxy="18.7316"/>
      <Dimension name="time" default="2026-11-15T14:45:00Z">2026-11-12T00:00:00.000Z/2026-11-15T14:45:00.000Z/PT5M</Dimension>
      </Layer></Layer></Capability></WMS_Capabilities>"""
}
