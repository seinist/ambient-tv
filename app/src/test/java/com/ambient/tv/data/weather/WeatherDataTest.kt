package com.ambient.tv.data.weather

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class WeatherDataTest {
    private val place = WeatherLocation("geonames:1", "Erfurt", "Deutschland", "Thüringen", 50.98, 11.03, "Europe/Berlin")
    private val settings = WeatherSettings(enabled = true, locations = listOf(place), selectedLocationId = place.id)

    @Test fun `invalid imported places and provider choices cannot become active`() {
        val restored = settings.copy(
            locations = listOf(place.copy(id = "invalid", latitude = Double.NaN), place.copy(id = "bad-zone", timezone = "No/SuchZone")) +
                (1..8).map { place.copy(id = "location:$it") },
            selectedLocationId = "removed", temperatureUnit = "kelvin", windUnit = "invalid",
            radarProvider = "eumetsat", satelliteProvider = "rainviewer",
        ).normalized()
        assertEquals(5, restored.locations.size)
        assertEquals("location:1", restored.selectedLocationId)
        assertEquals("celsius", restored.temperatureUnit)
        assertEquals("kmh", restored.windUnit)
        assertEquals("", restored.radarProvider)
        assertEquals("", restored.satelliteProvider)
    }

    @Test fun `valid manual providers and selected location survive normalization`() {
        val restored = settings.copy(showInSidebar = false, radarProvider = "dwd", satelliteProvider = "nasa_gibs", windUnit = "kn").normalized()
        assertEquals(place, restored.selectedLocation)
        assertEquals("dwd", restored.radarProvider)
        assertEquals("nasa_gibs", restored.satelliteProvider)
        assertEquals("kn", restored.windUnit)
        assertFalse(restored.showInSidebar)
        assertEquals("", WeatherSettings().radarProvider)
        assertEquals("", WeatherSettings().satelliteProvider)
    }

    @Test fun `forecast cache identity includes coordinates timezone and units`() {
        val snapshot = parseWeatherForecast(minimalForecast, place, settings, 10_000L)
        assertTrue(snapshot.matches(settings))
        assertFalse(snapshot.matches(settings.copy(temperatureUnit = "fahrenheit")))
        assertFalse(snapshot.matches(settings.copy(windUnit = "mph")))
        assertFalse(snapshot.matches(settings.copy(locations = listOf(place.copy(latitude = 51.0)))))
        assertFalse(snapshot.matches(settings.copy(locations = listOf(place.copy(timezone = "UTC")))))
        assertFalse(snapshot.isStale(10_000L + WEATHER_CACHE_TTL_MILLIS - 1))
        assertTrue(snapshot.isStale(10_000L + WEATHER_CACHE_TTL_MILLIS))
        assertTrue(snapshot.isStale(9_000L))
    }

    @Test fun `unknown and missing values never become zero rainfall or temperatures`() {
        val data = parseWeatherForecast("""{
          "timezone":"Europe/Berlin","utc_offset_seconds":3600,
          "current":{"time":1794754800,"temperature_2m":7,"weather_code":null,"is_day":0},
          "hourly":{"time":[1794754800,1794758400,1794762000],"temperature_2m":[7,null],"precipitation_probability":[null,0]},
          "daily":{"time":[1794697200],"temperature_2m_max":[9],"sunrise":[null],"sunset":[null]}
        }""", place, settings, 20_000)
        assertNull(data.current!!.weatherCode)
        assertFalse(data.current.isDay)
        assertEquals(3, data.hourly.size)
        assertNull(data.hourly[1].temperature)
        assertNull(data.hourly[2].temperature)
        assertNull(data.hourly[0].precipitationProbability)
        assertEquals(0.0, data.hourly[1].precipitationProbability!!, 0.0)
        assertNull(data.daily[0].sunriseEpochSeconds)
        assertNull(data.daily[0].sunsetEpochSeconds)
        assertNull(data.daily[0].precipitation)
    }

    @Test fun `hourly instants retain both repeated autumn hours and daily labels follow API boundaries`() {
        // A request made before DST ends uses +02:00 for all API daily boundaries. The next
        // day's API midnight is 23:00 in the actual IANA timezone: using that would mislabel it.
        val firstHour = Instant.parse("2026-10-25T00:00:00Z").epochSecond
        val repeatedHour = Instant.parse("2026-10-25T01:00:00Z").epochSecond
        val dailyBoundary = Instant.parse("2026-10-25T22:00:00Z").epochSecond
        val data = parseWeatherForecast("""{
          "timezone":"Europe/Berlin","utc_offset_seconds":7200,
          "hourly":{"time":[$firstHour,$repeatedHour],"temperature_2m":[8,7]},
          "daily":{"time":[$dailyBoundary],"temperature_2m_max":[12]}
        }""", place, settings, 30_000)
        val zone = ZoneId.of(data.timezone)
        assertEquals(2, Instant.ofEpochSecond(data.hourly[0].timeEpochSeconds).atZone(zone).hour)
        assertEquals(2, Instant.ofEpochSecond(data.hourly[1].timeEpochSeconds).atZone(zone).hour)
        assertNotEquals(data.hourly[0].timeEpochSeconds, data.hourly[1].timeEpochSeconds)
        assertEquals("2026-10-26", data.daily[0].date)
    }

    @Test fun `geocoding filters invalid coordinates and keeps location disambiguation`() {
        val result = parseWeatherLocations("""{"results":[
          {"id":1,"name":"Erfurt","country":"Deutschland","admin1":"Thüringen","latitude":50.98,"longitude":11.03,"timezone":"Europe/Berlin"},
          {"id":2,"name":"Invalid","latitude":100,"longitude":11,"timezone":"UTC"},
          {"id":3,"name":"Invalid zone","latitude":10,"longitude":11,"timezone":"Invalid/Zone"}
        ]}""")
        assertEquals(1, result.size)
        assertEquals("geonames:1", result.single().id)
        assertEquals("Thüringen", result.single().region)
        assertEquals("Deutschland", result.single().country)
        assertTrue(parseWeatherLocations("{}").isEmpty())
    }

    @Test fun `rate limit delay honours seconds dates missing headers and overflow`() {
        val now = Instant.parse("2026-11-15T18:00:00Z").toEpochMilli()
        assertEquals(now + 90_000, weatherRetryAfterMillis("90", now))
        assertEquals(now + 60_000, weatherRetryAfterMillis(null, now))
        assertEquals(now + 60_000, weatherRetryAfterMillis("0", now))
        assertEquals(now + 120_000, weatherRetryAfterMillis("Sun, 15 Nov 2026 18:02:00 GMT", now))
        assertEquals(Long.MAX_VALUE, weatherRetryAfterMillis(Long.MAX_VALUE.toString(), now))
    }

    @Test(expected = IllegalArgumentException::class) fun `empty forecast is not a successful refresh`() {
        parseWeatherForecast("{}", place, settings, 10_000)
    }

    private val minimalForecast = """{"timezone":"Europe/Berlin","current":{"time":1794754800,"temperature_2m":7}}"""
}
