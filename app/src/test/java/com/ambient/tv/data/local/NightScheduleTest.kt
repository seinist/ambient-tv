package com.ambient.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightScheduleTest {
    private val overnight = NightSchedule(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60)

    @Test fun windowWrapsPastMidnight() {
        assertTrue(overnight.activeAt(22 * 60))
        assertTrue(overnight.activeAt(23 * 60 + 59))
        assertTrue(overnight.activeAt(0))
        assertTrue(overnight.activeAt(6 * 60 + 59))
        assertFalse(overnight.activeAt(7 * 60))
        assertFalse(overnight.activeAt(12 * 60))
        assertFalse(overnight.activeAt(21 * 60 + 59))
    }

    @Test fun windowWithinOneDay() {
        val afternoon = NightSchedule(enabled = true, startMinute = 13 * 60, endMinute = 15 * 60)
        assertTrue(afternoon.activeAt(14 * 60))
        assertFalse(afternoon.activeAt(15 * 60))
        assertFalse(afternoon.activeAt(2 * 60))
    }

    @Test fun disabledOrEmptyWindowIsNeverActive() {
        assertFalse(overnight.copy(enabled = false).activeAt(23 * 60))
        assertFalse(NightSchedule(enabled = true, startMinute = 600, endMinute = 600).activeAt(600))
    }

    @Test fun appliesNightAndLiftsDialogueOnly() {
        val schedule = overnight.copy(dialogue = 3)
        assertEquals(AudioOptions(night = true, dialogue = 3), schedule.applyTo(AudioOptions()))
        assertEquals(5, schedule.applyTo(AudioOptions(dialogue = 5)).dialogue)
    }

    @Test fun normalizedClampsValues() {
        val n = NightSchedule(startMinute = -5, endMinute = 5000, dialogue = 9).normalized()
        assertEquals(0, n.startMinute)
        assertEquals(NightSchedule.MINUTES_PER_DAY - 1, n.endMinute)
        assertEquals(6, n.dialogue)
    }
}
