package com.ambient.tv.ui.weather

import com.ambient.tv.data.weather.WeatherMapFrame
import org.junit.Assert.assertEquals
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

    @Test fun noFramesReturnsSafeIndex() {
        assertEquals(0, weatherPlayerFrameIndex(emptyList(), 7_000))
    }
}
