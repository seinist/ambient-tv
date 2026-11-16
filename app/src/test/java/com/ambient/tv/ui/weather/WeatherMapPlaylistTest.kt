package com.ambient.tv.ui.weather

import com.ambient.tv.data.weather.WeatherLocation
import com.ambient.tv.data.weather.WeatherMapFrame
import com.ambient.tv.data.weather.WeatherMapProvider
import com.ambient.tv.data.weather.WeatherMapViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherMapPlaylistTest {
    private val frames = listOf(
        WeatherMapFrame("first", 1_000L),
        WeatherMapFrame("second", 4_000L),
        WeatherMapFrame("third", 11_000L),
    )

    @Test fun latestIsDefaultButZoomKeepsTheClosestObservedTimestamp() {
        assertEquals(2, weatherPlayerFrameIndex(frames, 0))
        assertEquals(0, weatherPlayerFrameIndex(frames, 1_000))
        assertEquals(1, weatherPlayerFrameIndex(frames, 4_000))
        assertEquals(1, weatherPlayerFrameIndex(frames, 6_000))
        assertEquals(2, weatherPlayerFrameIndex(frames, 10_000))
        assertEquals(2, weatherPlayerFrameIndex(frames, 100_000))
    }

    @Test fun sharedSeekBarStepsDiscreteWeatherFramesWithoutSkippingOrStalling() {
        assertEquals(1, weatherPlayerSeekTargetIndex(frames, 0, 1))
        assertEquals(0, weatherPlayerSeekTargetIndex(frames, 1, -1))
        assertEquals(2, weatherPlayerSeekTargetIndex(frames, 0, 9_000))
        assertEquals(0, weatherPlayerSeekTargetIndex(frames, 2, -25_000))
        assertEquals(0, weatherPlayerSeekTargetIndex(frames, 0, -100))
        assertEquals(2, weatherPlayerSeekTargetIndex(frames, 2, 100))
        assertEquals(1, weatherPlayerSeekTargetIndex(frames, 1, 0))
        assertEquals(0, weatherPlayerSeekTargetIndex(emptyList(), 0, 100))
    }

    @Test fun noFramesReturnsSafeIndex() {
        assertEquals(0, weatherPlayerFrameIndex(emptyList(), 7_000))
    }

    @Test fun onlyExplicitRetryForTheSameRequestForcesAManifestRefresh() {
        val location = WeatherLocation("erfurt", "Erfurt", "DE", latitude = 50.98,
            longitude = 11.03, timezone = "Europe/Berlin")
        val previous = WeatherPlayerRequest(location, WeatherMapProvider.RAINVIEWER,
            WeatherMapViewport.at(location.latitude, location.longitude), networkAllowed = true)
        assertFalse(weatherPlayerManualRefresh(null, -1, previous, 0))
        assertFalse(weatherPlayerManualRefresh(previous, 0, previous, 0))
        assertTrue(weatherPlayerManualRefresh(previous, 0, previous, 1))
        assertFalse(weatherPlayerManualRefresh(previous, 1, previous.copy(viewport = previous.viewport.zoomed(1)), 1))
        assertFalse(weatherPlayerManualRefresh(previous, 1,
            previous.copy(provider = WeatherMapProvider.DWD), 2))
    }

    @Test fun manifestPollingRespectsHourlyVsRadarTimelines() {
        assertEquals(5 * 60_000L, weatherPlayerRefreshIntervalMillis(WeatherMapProvider.RAINVIEWER))
        assertEquals(5 * 60_000L, weatherPlayerRefreshIntervalMillis(WeatherMapProvider.DWD))
        assertEquals(60 * 60_000L, weatherPlayerRefreshIntervalMillis(WeatherMapProvider.NASA))
        assertEquals(5 * 60_000L, weatherPlayerRefreshIntervalMillis(WeatherMapProvider.EUMETSAT))
    }
}
