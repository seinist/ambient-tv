package com.ambient.tv.ui.weather

import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherFormattingTest {
    @Test fun `missing and invalid values remain unknown rather than zero`() {
        assertEquals("—", weatherTemperature(null, "celsius", Locale.GERMANY))
        assertEquals("—", weatherMeasure(Double.NaN, "mm", Locale.US, 1))
        assertEquals("—", weatherNumber(Double.POSITIVE_INFINITY, Locale.US))
        assertEquals("0 mm", weatherMeasure(0.0, "mm", Locale.GERMANY, 1))
        assertEquals("1,2 mm", weatherMeasure(1.2, "mm", Locale.GERMANY, 1))
        assertEquals("41 °F", weatherTemperature(41.0, "fahrenheit", Locale.US))
    }

    @Test fun `forecast time uses location timezone across spring daylight saving change`() {
        val before = Instant.parse("2026-03-29T00:30:00Z").epochSecond
        val after = Instant.parse("2026-03-29T01:30:00Z").epochSecond
        assertEquals("01:30", weatherTime(before, "Europe/Berlin", Locale.GERMANY))
        assertEquals("03:30", weatherTime(after, "Europe/Berlin", Locale.GERMANY))
        assertEquals("10:30 AM", weatherTime(after, "Asia/Tokyo", Locale.US, use24Hour = false))
        assertEquals("—", weatherTime(null, "Europe/Berlin", Locale.GERMANY))
    }

    @Test fun `today and tomorrow follow selected location rather than device date`() {
        val now = Instant.parse("2026-11-15T00:30:00Z").toEpochMilli()
        assertEquals("Today", weatherDayName("2026-11-14", "America/Los_Angeles", Locale.US, "Today", "Tomorrow", now))
        assertEquals("Tomorrow", weatherDayName("2026-11-15", "America/Los_Angeles", Locale.US, "Today", "Tomorrow", now))
        assertEquals("Heute", weatherDayName("2026-11-15", "Asia/Tokyo", Locale.GERMANY, "Heute", "Morgen", now))
        assertEquals("14.11.2026, 16:30", weatherTimestamp(now, "America/Los_Angeles", Locale.GERMANY))
        assertEquals("Nov 14, 2026, 4:30 PM", weatherTimestamp(now, "America/Los_Angeles", Locale.US, use24Hour = false))
    }
}
