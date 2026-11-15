package com.ambient.tv.data.weather

import java.time.Instant
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
        assertEquals(21, view.y)
        val marker = view.marker(50.98, 11.03)!!
        assertTrue(marker.first in 0f..1f && marker.second in 0f..1f)
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
        assertTrue(url.endsWith("/512/6/33/21/2/1_0.png"))
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
