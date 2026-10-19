package com.ambient.tv.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyStopTimePickerTest {
    @Test fun stepsWrapAroundMidnightAndSnapToFiveMinutes() {
        assertEquals(0, stepMinuteOfDay(23 * 60, 60))
        assertEquals(23 * 60, stepMinuteOfDay(0, -60))
        assertEquals(1435, stepMinuteOfDay(0, -5))
        assertEquals(0, stepMinuteOfDay(1435, 5))
        assertEquals(65, stepMinuteOfDay(62, 5))
        assertEquals(60, stepMinuteOfDay(62, -5))
        assertEquals("01:05", formatMinuteOfDay(65))
        assertEquals("00:00", formatMinuteOfDay(0))
    }
}
